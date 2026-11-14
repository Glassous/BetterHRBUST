const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { readFileSync } = require('node:fs');

const playwrightModule = process.env.PLAYWRIGHT_MODULE_PATH || 'playwright';
const { chromium } = require(playwrightModule);

const webRoot = path.resolve(__dirname, '..');
const bundlePath = path.resolve(process.argv[2] || path.join(webRoot, 'dist-userscript', 'better-hrbust.user.js'));

let gbkMap;
function encodeGbk(text) {
  if (!gbkMap) {
    gbkMap = new Map();
    const decoder = new TextDecoder('gbk');
    for (let first = 0x81; first <= 0xfe; first += 1) {
      for (let second = 0x40; second <= 0xfe; second += 1) {
        if (second === 0x7f) continue;
        const character = decoder.decode(Uint8Array.of(first, second));
        if (character.length === 1 && character !== '\ufffd' && !gbkMap.has(character)) {
          gbkMap.set(character, [first, second]);
        }
      }
    }
  }

  const bytes = [];
  for (const character of String(text)) {
    const code = character.codePointAt(0);
    if (code < 128) bytes.push(code);
    else if (gbkMap.has(character)) bytes.push(...gbkMap.get(character));
    else throw new Error(`Cannot encode GBK character: ${character}`);
  }
  return Buffer.from(bytes);
}

function decodeFormComponent(component) {
  const bytes = [];
  for (let i = 0; i < component.length; i += 1) {
    const ch = component[i];
    if (ch === '+') bytes.push(32);
    else if (ch === '%') {
      bytes.push(Number.parseInt(component.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      bytes.push(ch.charCodeAt(0));
    }
  }
  return new TextDecoder('gbk').decode(Uint8Array.from(bytes));
}

function parseFormBody(body) {
  const entries = new Map();
  for (const pair of String(body || '').split('&')) {
    if (!pair) continue;
    const index = pair.indexOf('=');
    const key = decodeFormComponent(index >= 0 ? pair.slice(0, index) : pair);
    const value = decodeFormComponent(index >= 0 ? pair.slice(index + 1) : '');
    if (!entries.has(key)) entries.set(key, []);
    entries.get(key).push(value);
  }
  return entries;
}

function html(body) {
  return encodeGbk(`<!doctype html><html><head><meta charset="gbk"><title>stub</title></head><body>${body}</body></html>`);
}

function coursePage() {
  return html('<script>var studentid="100001"; var year="2025";</script><select name="term"><option value="2" selected>秋</option></select><table class="infolist_tab"></table>');
}

function taskList(completed = false) {
  const status = completed ? '已评估' : '未评估';
  const link = completed ? '' : '<a href="questionnaire.jsdo?task=1">进入评价</a>';
  return html(`<table class="infolist_tab">
    <tr><th>教师</th><th>课程</th><th>状态</th><th>操作</th></tr>
    <tr><td>张老师</td><td>分布式系统</td><td>${status}</td><td>${link}</td></tr>
  </table>`);
}

function formPage(token) {
  return html(`<form method="post" action="save.jsdo?task=1" enctype="application/x-www-form-urlencoded">
    <input type="hidden" name="token" value="${token}">
    <input type="hidden" name="courseId" value="C-42">
    <table>
      <tr><td>教学态度</td><td>
        <label><input type="radio" name="rating" value="5">优秀</label>
        <label><input type="radio" name="rating" value="4">良好</label>
      </td></tr>
      <tr><td>文字评价</td><td><textarea name="comment" maxlength="80"></textarea></td></tr>
    </table>
  </form>`);
}

function waitForRequest(server) {
  return new Promise((resolve) => server.once('request', (req, res) => resolve({ req, res })));
}

async function startUnusedServer() {
  const server = http.createServer((req, res) => {
    res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('This server should not be reached by the browser test.');
  });
  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  return server;
}

async function runScenario({ failSubmit = false } = {}) {
  const server = await startUnusedServer();
  let browser;
  try {
  browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: [`--host-resolver-rules=MAP jwzx.hrbust.edu.cn 127.0.0.1:${server.address().port}`]
  });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const state = { listReads: 0, formReads: 0, posts: [], completed: false, pageErrors: [], unexpectedRequests: [] };
  page.on('pageerror', (error) => state.pageErrors.push(error.message));

  // 拦截全部请求，测试不访问真实学校服务器，也不放行 HTTPS 跳转。
  await page.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.origin !== 'http://jwzx.hrbust.edu.cn') {
      state.unexpectedRequests.push(request.url());
      return route.abort();
    }
    const response = (status, body) => route.fulfill({
      status,
      headers: { 'content-type': 'text/html; charset=GBK', 'cache-control': 'no-store' },
      body
    });

    if (url.pathname === '/academic/student/currcourse/currcourse.jsdo') {
      await response(200, coursePage());
    } else if (url.pathname === '/academic/eva/index/resultlist.jsdo') {
      state.listReads += 1;
      await response(200, taskList(state.completed));
    } else if (url.pathname === '/academic/eva/index/questionnaire.jsdo') {
      state.formReads += 1;
      await response(200, formPage(state.formReads === 1 ? 'preview-token' : 'submit-token'));
    } else if (url.pathname === '/academic/eva/index/save.jsdo' && request.method() === 'POST') {
      state.posts.push({
        headers: request.headers(),
        body: request.postData() || ''
      });
      if (!failSubmit) state.completed = true;
      if (failSubmit) await response(500, '');
      else await route.fulfill({ status: 302, headers: {
        location: 'https://jwzx.hrbust.edu.cn/academic/eva/index/resultlist.jsdo'
      }, body: '' });
    } else {
      await response(404, html(`missing ${url.pathname}`));
    }
  });

  const unexpectedNetwork = waitForRequest(server);
  await page.goto('http://jwzx.hrbust.edu.cn/academic/student/currcourse/currcourse.jsdo');
  await page.evaluate(() => {
    localStorage.setItem('better_hrbust_has_session', 'true');
    const fixed = new Date(2026, 9, 9, 22, 0).getTime();
    const OriginalDate = Date;
    window.Date = class extends OriginalDate {
      constructor(...args) { super(...(args.length ? args : [fixed])); }
      static now() { return fixed; }
    };
    const score = (courseId, value, credit, courseGroup = '', property = '必修') => ({
      courseId, courseName: courseId, score: String(value), credit, courseGroup, property,
      year: '2026', term: '1', examType: '正常考试', passed: value >= 60
    });
    localStorage.setItem('better_hrbust_cache_scores', JSON.stringify([
      score('测试基础课', 65, 3), score('E最高', 95, 2, 'E类', '任选'),
      score('E第二', 90, 2, 'E类', '任选'), score('A候选', 89, 2, 'A类', '任选'),
      score('颜色69', 69, 1), score('颜色70', 70, 1)
    ]));
    localStorage.setItem('better_hrbust_cache_program_plan', JSON.stringify({totalRequiredCredits:158.5,
      groups:[{id:'direction',name:'专业限选',property:'限选',requiredCredits:10,requiredCourses:4,
      courses:Array.from({length:10},(_,i)=>({code:'DIR'+i,name:'方向课'+i,credit:2.5}))}]}));
    localStorage.setItem('better_hrbust_cache_timetable', JSON.stringify({cells:[{
      courseName:'已结束测试课程',day:5,sectionIndex:1,sectionLabel:'第一大节',weeks:'1-20'
    }],unarranged:[]}));

    localStorage.setItem('better_hrbust_cached_profile', JSON.stringify({
      studentNumber: 'test-student',
      internalId: '100001',
      realName: '测试用户',
      grade: '2024级',
      status: '在籍'
    }));
  });
  await page.addScriptTag({ content: readFileSync(bundlePath, 'utf8') });
  await page.getByRole('button', { name: '学籍档案与隐私', exact: true }).click();
  await page.getByText('2024级', { exact: true }).waitFor();
  assert.equal(await page.getByText('2024级 级', { exact: true }).count(), 0);
  await page.emulateMedia({ colorScheme: 'dark' });
  // 跟随系统模式遇系统深色应自动生效，顶栏快捷主题开关应同步展示并可快速切换
  await page.waitForFunction(() => document.documentElement.classList.contains('dark'));
  await page.getByRole('button', { name: '切换浅色模式', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '切换浅色模式', exact: true }).count(), 1);
  await page.getByRole('button', { name: '切换浅色模式', exact: true }).click();
  assert.equal(await page.locator('html').evaluate(element => element.classList.contains('dark')), false);
  await page.getByRole('button', { name: '切换深色模式', exact: true }).click();
  assert.equal(await page.locator('html').evaluate(element => element.classList.contains('dark')), true);
  // 设置页「外观与个性化设置」同步生效
  await page.getByRole('button', { name: '设置与系统状态', exact: true }).click();
  await page.getByText('外观与个性化设置', { exact: true }).waitFor();
  await page.getByRole('button', { name: '浅色', exact: true }).click();
  assert.equal(await page.locator('html').evaluate(element => element.classList.contains('dark')), false);
  await page.emulateMedia({ colorScheme: 'light' });
  await page.getByRole('button', { name: '深色', exact: true }).click();
  assert.equal(await page.locator('html').evaluate(element => element.classList.contains('dark')), true);
  await page.getByRole('button', { name: '跟随系统', exact: true }).click();
  assert.equal(await page.locator('html').evaluate(element => element.classList.contains('dark')), false);
  await page.getByRole('button', { name: '概览', exact: true }).click();
  await page.getByText('今日剩余课程', { exact: true }).waitFor();
  await page.getByText('GPA 计算说明', { exact: true }).click();
  assert.equal(await page.locator('details[open]').count(), 1);
  assert.match(await page.locator('details[open]').innerText(), /剩余 A–E/);
  await page.getByText('GPA 计算说明', { exact: true }).click();
  for (const scale of [1.25, 1.5, 1.75, 2]) {
    await page.setViewportSize({width:Math.floor(1920/scale),height:Math.floor(1080/scale)});
    await page.evaluate(() => new Promise(requestAnimationFrame));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `缩放 ${scale*100}% 的有效宽度不得溢出`);
    if (scale === 1.5) {
      assert.equal(await page.locator('.academic-summary-grid').last().evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length), 2, '缩放后按主内容宽度使用两列卡片');
    }
  }
  await page.setViewportSize({width:1440,height:1050});

  assert.equal(await page.getByText('已结束测试课程', { exact: true }).count(), 0);
  assert.match(await page.getByText('今日剩余课程', { exact: true }).locator('..').innerText(), /0\s*门/);
  assert.equal(await page.getByText('提前毕业', { exact: true }).count(), 0);
  assert.match(await page.locator('main').innerText(), /158\.5/);
  await page.getByRole('button', { name: '成绩与GPA分析', exact: true }).click();
  const low = page.getByText('69', { exact: true });
  const boundary = page.getByText('70', { exact: true });
  assert.match(await low.getAttribute('class'), /text-rose/);
  assert.doesNotMatch(await boundary.getAttribute('class'), /text-rose/);
  assert.equal(await page.getByText('提前毕业判定', { exact: true }).count(), 0);
  await page.getByRole('button', { name: '培养方案与学分', exact: true }).click();
  await page.getByText('毕业方案总学分达成进度', { exact: true }).waitFor();
  assert.match(await page.locator('main').innerText(), /10 选 4/);
  assert.doesNotMatch(await page.locator('main').innerText(), /studentScheduleShowByTerm\.do|级级/);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => new Promise(requestAnimationFrame));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, '培养方案手机排版不得横向溢出');
  await page.setViewportSize({ width: 1440, height: 1050 });

  assert.match(await page.locator('main').innerText(), /158\.5/);
  for (const [label, heading] of [['考试日程与倒计时', '全部考试日程列表'], ['学籍档案与隐私', '学籍详细档案'], ['空教室与自习', '教室占用矩阵'], ['教学公告与校历', '教学运行公告']]) {
    await page.getByRole('button', { name: label, exact: true }).click();
    await page.getByText(heading, { exact: true }).waitFor();
    assert.doesNotMatch(await page.locator('main').innerText(), /[a-zA-Z][a-zA-Z/]*\.(?:jsdo|do)/);
  }
  assert.equal(await page.getByRole('button', { name: '我的课程名录', exact: true }).count(), 1);
  await page.getByRole('button', { name: '资料查找', exact: true }).click();
  await page.getByLabel('搜索资料').fill('补办学生证');
  await page.getByText('补办学生证申请（新版）', { exact: true }).waitFor();
  await page.getByLabel('资料分类').click();
  await page.getByRole('option', { name: '学籍管理', exact: true }).click();
  assert.match(await page.getByLabel('资料分类').innerText(), /学籍管理/);
  const file = page.getByRole('link', { name: /补办学生证申请.*\.doc/ });
  assert.match(await file.getAttribute('href'), /^http:\/\/jwzx\.hrbust\.edu\.cn\/homepage\/downloadTheolFile\.do\?id=/);
  await page.getByLabel('搜索资料').fill('不存在的资料关键词');
  await page.getByRole('status').filter({ hasText: '未找到匹配资料' }).waitFor();
  await page.getByLabel('搜索资料').fill('');
  await page.getByLabel('资料分类').click();
  await page.getByRole('option', { name: '全部分类', exact: true }).click();
  assert.match(await page.getByLabel('资料分类').innerText(), /全部分类/);
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await page.getByText('2 / 5', { exact: true }).waitFor();
  await page.getByRole('button', { name: '教学评价助手' }).click();
  await page.getByText('分布式系统 · 张老师').waitFor({ timeout: 10000 });
  await page.getByRole('button', { name: '读取问卷并配置' }).click();
  await page.getByLabel('评价项目 1').selectOption('1');
  await page.getByLabel('文字评价 1').fill('教学建议');
  await page.getByRole('button', { name: '检查配置并开始批量提交' }).click();
  await page.getByText('确认批量教学评价').waitFor();
  assert.equal(state.posts.length, 0, 'Preview must not submit the evaluation form');

  await page.getByRole('button', { name: '确认使用以上配置提交' }).click();
  await page.waitForFunction(() => document.body.innerText.includes('批量任务已停止') || document.body.innerText.includes('已核对完成 1 门课程'), null, { timeout: 10000 });

  await Promise.race([
    unexpectedNetwork.then(() => { throw new Error('Browser attempted real network instead of Playwright route'); }),
    new Promise((resolve) => setTimeout(resolve, 250))
  ]);

  return state;
  } finally {
    if (browser) await browser.close().catch(() => {});
    server.close();
  }
}

(async () => {
  const success = await runScenario();
  assert.equal(success.posts.length, 1, 'Successful batch must submit exactly once');
  assert.equal(success.formReads, 2, 'Batch must re-read the form and hidden token before submitting');
  assert.ok(success.listReads >= 3, 'Batch must re-read the list before and after submitting');
  assert.equal(success.pageErrors.length, 0, success.pageErrors.join('\n'));
  assert.deepEqual(success.unexpectedRequests, [], '禁止跟随 HTTPS 跳转或访问其他服务器');
  const posted = parseFormBody(success.posts[0].body);
  assert.equal(posted.get('token')?.[0], 'submit-token');
  assert.equal(posted.get('rating')?.[0], '4');
  assert.equal(posted.get('comment')?.[0], '教学建议');
  assert.match(success.posts[0].headers['content-type'], /charset=GBK/i);

  const failure = await runScenario({ failSubmit: true });
  assert.equal(failure.posts.length, 1, 'Failed submit must not be retried automatically');
  assert.equal(failure.completed, false, 'Failure scenario must not mark the task as completed');
  assert.equal(failure.pageErrors.length, 0, failure.pageErrors.join('\n'));
  assert.deepEqual(failure.unexpectedRequests, []);

  console.log(JSON.stringify({
    checks: [
      '评教列表解析通过',
      '资料标题搜索、分类、附件链接、空结果与分页通过',
      '顶栏快捷主题开关、设置页主题切换、跟随系统变化与实际配色切换通过',
      '配置预览无 POST 通过',
      '提交前重新读取隐藏令牌通过',
      '选择的评分和中文评语按 GBK 表单提交通过',
      '单次 POST 后读取列表核对完成通过',
      '评教 302 不访问 HTTPS 跳转目标通过',
      '提交失败停止且不自动重试通过'
    ],
    success: { listReads: success.listReads, formReads: success.formReads, posts: success.posts.length },
    failure: { listReads: failure.listReads, formReads: failure.formReads, posts: failure.posts.length }
  }, null, 2));
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
