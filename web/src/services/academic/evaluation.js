import { createDom } from './parser.js';
import { getAcademicBase, request } from './client.js';
import { recordAcademicDiagnostic } from './diagnostics.js';

const OFFICIAL_BASE = 'http://jwzx.hrbust.edu.cn/academic/';
function evaluationUrl(value, page) {
  // 教务地址可自定义：解析基准与 host 白名单都跟随当前生效地址
  const allowed = new URL(getAcademicBase() || OFFICIAL_BASE);
  const source = new URL(page, allowed.href);
  const base = new URL(source.pathname + source.search, allowed.href);
  const url = new URL(value, base);
  if (url.hostname !== allowed.hostname || url.username || url.password || !['http:', 'https:'].includes(url.protocol)
      || !url.pathname.startsWith('/academic/eva/')) {
    throw new Error('评价地址不属于学校教学评价模块');
  }
  // 协议与端口跟随当前教务地址：官方仅 HTTP，自建镜像通常是 HTTPS
  url.protocol = allowed.protocol; url.port = allowed.port;
  return url.href;
}
export function parseEvaluationTasks(html, page = 'eva/index/resultlist.jsdo') {
  const doc = createDom(html);
  const table = doc.querySelector('table.infolist_tab');
  if (!table) throw new Error('教学评价列表结构不符，请打开原版教务核对');
  const seen = new Map();
  return Array.from(table.querySelectorAll('tr')).flatMap(row => {
    const cells = row.querySelectorAll('td');
    if (cells.length < 4) return [];
    const teacher = cells[0].textContent.trim(), course = cells[1].textContent.trim(), status = cells[2].textContent.trim();
    if (!teacher || !course) return [];
    const identity = teacher + '|' + course;
    const occurrence = seen.get(identity) || 0; seen.set(identity, occurrence + 1);
    const key = identity + '|' + occurrence;
    const pending = /未评估|未评价|未完成/.test(status);
    const completed = /已评估|已评价|已完成/.test(status);
    const link = cells[3].querySelector('a[href]');
    const url = pending && link ? evaluationUrl(link.getAttribute('href'), page) : '';
    return [{ key, teacher, course, status, pending, completed, url }];
  });
}
export function parseEvaluationForm(html, page, encoding = 'gbk') {
  const doc = createDom(html);
  const form = Array.from(doc.forms).find(element => element.querySelector('input[type="radio"]'));
  if (!form || form.method.toLowerCase() !== 'post') throw new Error('未取得可提交的评价问卷');
  if (form.enctype !== 'application/x-www-form-urlencoded') throw new Error('该问卷的提交编码暂不支持批量，请在原版页面完成');
  if (form.querySelector('input[type="file"], input[name*="captcha" i]')) throw new Error('该问卷含附件或验证码，请在原版页面完成');
  const groups = [];
  const questionFor = (element, fallback) => Array.from(element.closest('tr')?.querySelectorAll('td, th') || [])
    .find(cell => !cell.querySelector('input, textarea, select') && cell.textContent.trim() && !/^\d+[.、]?$/u.test(cell.textContent.trim()))?.textContent.trim() || fallback;
  for (const input of form.querySelectorAll('input[type="radio"]')) {
    if (!input.name || input.disabled) continue;
    let group = groups.find(item => item.name === input.name);
    if (!group) {
      group = { name: input.name, question: questionFor(input, `评价项目 ${groups.length + 1}`), options: [] };
      groups.push(group);
    }
    let label = input.id ? Array.from(doc.querySelectorAll('label[for]')).find(element => element.getAttribute('for') === input.id)?.textContent.trim() : '';
    if (!label) {
      for (let next = input.nextSibling; next && !(next.nodeType === 1 && (next.tagName === 'INPUT' || next.querySelector?.('input'))); next = next.nextSibling) {
        label = (label || '') + (next.textContent || '');
      }
    }
    group.options.push({ value: input.value, label: label?.trim() || `选项 ${group.options.length + 1}` });
  }
  const comments = Array.from(form.querySelectorAll('textarea[name]')).filter(element => !element.disabled).map((element, index) => ({
    name: element.name, question: questionFor(element, `文字评价 ${index + 1}`), maxLength: element.maxLength
  }));
  const excluded = new Set([...groups.map(group => group.name), ...comments.map(comment => comment.name)]);
  const fields = [];
  for (const element of Array.from(form.elements)) {
    if (!element.name || element.matches(':disabled') || excluded.has(element.name) || ['submit', 'button', 'reset', 'file'].includes(element.type)) continue;
    if (['radio', 'checkbox'].includes(element.type) && !element.checked) continue;
    if (element.tagName === 'SELECT') for (const option of element.selectedOptions) fields.push([element.name, option.value]);
    else fields.push([element.name, element.value]);
  }
  if (!groups.length) throw new Error('问卷中没有可配置的评价选项');
  return { url: evaluationUrl(page, page), action: evaluationUrl(form.getAttribute('action') || page, page), encoding, groups, comments, fields };
}
export function evaluationSignature(form) {
  return JSON.stringify({ groups: form.groups.map(group => [group.question, group.options.map(option => option.label)]), comments: form.comments.map(comment => comment.question) });
}
export function findUniqueEvaluationTask(list, task) {
  const matches = list.filter(item => item.teacher === task.teacher && item.course === task.course);
  if (matches.length > 1) throw new Error('同一教师和课程存在多条评价记录，无法可靠核对，已停止，请在原版逐项处理');
  return matches[0];
}
let gbkMap;
function encodeGbk(value) {
  if (!gbkMap) {
    gbkMap = new Map(); const decoder = new TextDecoder('gbk');
    for (let first = 0x81; first <= 0xfe; first++) for (let second = 0x40; second <= 0xfe; second++) {
      if (second === 0x7f) continue;
      const character = decoder.decode(Uint8Array.of(first, second));
      if (character.length === 1 && character !== '\ufffd' && !gbkMap.has(character)) gbkMap.set(character, [first, second]);
    }
  }
  const bytes = [];
  for (const character of value) {
    if (character.codePointAt(0) < 128) bytes.push(character.codePointAt(0));
    else if (gbkMap.has(character)) bytes.push(...gbkMap.get(character));
    else throw new Error('问卷使用 GBK，评语中含无法编码的字符，请修改后提交');
  }
  return bytes.map(byte => byte === 32 ? '+' : /[A-Za-z0-9*_.-]/.test(String.fromCharCode(byte)) ? String.fromCharCode(byte) : '%' + byte.toString(16).toUpperCase().padStart(2, '0')).join('');
}
export function evaluationBody(form, settings) {
  if (settings.ratings.length !== form.groups.length || settings.comments.length !== form.comments.length) throw new Error('评价配置与问卷项目数量不一致');
  const fields = [...form.fields];
  form.groups.forEach((group, index) => {
    const option = group.options[Number(settings.ratings[index])];
    if (!/^\d+$/.test(String(settings.ratings[index])) || !option) throw new Error('请为每个评价项目选择选项');
    fields.push([group.name, option.value]);
  });
  form.comments.forEach((comment, index) => {
    const text = String(settings.comments[index] || '').trim();
    if (!text) throw new Error('请填写每项文字评价');
    if (comment.maxLength >= 0 && text.length > comment.maxLength) throw new Error('文字评价超过原问卷长度限制');
    fields.push([comment.name, text.replace(/\r?\n/g, '\r\n')]);
  });
  if (/^gb/i.test(form.encoding)) return fields.map(([name, value]) => encodeGbk(String(name)) + '=' + encodeGbk(String(value))).join('&');
  return new URLSearchParams(fields).toString();
}
export async function getEvaluationTasks() {
  const response = await request(`eva/index/resultlist.jsdo?_t=${Date.now()}`, { encoding: 'gbk', headers: { 'Cache-Control': 'no-cache' } });
  const tasks = parseEvaluationTasks(response.html, response.url);
  recordAcademicDiagnostic({ path: response.url, status: response.status, outcome: '教学评价列表:parsed',
    counts: { tasks: tasks.length, pending: tasks.filter(task => task.pending).length, completed: tasks.filter(task => task.completed).length } });
  return tasks;
}
export async function getEvaluationForm(task) {
  const url = new URL(task.url);
  url.searchParams.set('_t', String(Date.now()));
  const response = await request(url.href, { encoding: 'gbk', headers: { 'Cache-Control': 'no-cache' } });
  const form = parseEvaluationForm(response.html, task.url, response.encoding);
  recordAcademicDiagnostic({ path: response.url, status: response.status, outcome: '教学评价问卷:parsed',
    counts: { questions: form.groups.length, comments: form.comments.length } });
  return form;
}
export async function runEvaluationBatch(tasks, template, settings, { stopped = () => false, progress = () => {} } = {}) {
  // 开始前校验配置；每门课重新取得隐藏字段，不复用表单令牌，不重试提交。
  evaluationBody(template, settings);
  const signature = evaluationSignature(template);
  let completed = 0;
  for (const task of tasks.filter(item => item.pending && item.url)) {
    if (stopped()) break;
    const before = findUniqueEvaluationTask(await getEvaluationTasks(), task);
    if (before?.completed) { completed++; progress(task, '已完成'); continue; }
    if (!before?.pending || !before.url) throw new Error('评价列表发生变化，已停止，请刷新后核对');
    const form = await getEvaluationForm(before);
    if (evaluationSignature(form) !== signature) throw new Error('该课程的问卷结构不同，已停止，请单独配置');
    if (stopped()) break;
    progress(task, '正在提交');
    let submissionError;
    try {
      await request(form.action, { method: 'POST', body: evaluationBody(form, settings),
        headers: { 'Content-Type': `application/x-www-form-urlencoded; charset=${/^gb/i.test(form.encoding) ? 'GBK' : 'UTF-8'}` }, referrer: form.url });
    } catch (error) { submissionError = error; }
    const after = findUniqueEvaluationTask(await getEvaluationTasks(), task);
    if (!after?.completed) throw new Error(submissionError?.message || '服务器未确认评价完成，已停止，请刷新列表核对，勿重复提交');
    completed++; progress(task, '已完成');
  }
  return completed;
}
