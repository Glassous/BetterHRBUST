#!/usr/bin/env node
/**
 * 空教室查询 · 定向探测脚本
 *
 * 「教室时间占用」是级联表单：教学区(aid) → 教学楼(buildingid) → 教室(room)，
 * 且查询页自身的下拉框带 `onChange="this.form.submit()"`，浏览器提交的是 **POST**。
 * 因此这里的级联请求一律按 POST 回放表单字段，并额外用 GET 对照一次，
 * 以免文档里的 `?aid=` 写法与实际服务端行为不一致。
 *
 * 流程（串行，请求间隔默认 300ms）：
 *   ① 恢复 output/session.json 会话并校验
 *   ② GET  roomschedulequery.jsdo                 → 01-query.html
 *   ③ POST roomschedulequery.jsdo (aid)           → 02-buildings-*.html（+GET 对照）
 *   ④ POST roomschedulequery.jsdo (aid+buildingid)→ 03-rooms-*.html
 *   ⑤ POST roomschedule_week.jsdo (整楼 + 周次)    → 04-week-*.html（+ week=-1 对照）
 *   ⑥ POST roomschedule.jsdo      (单个教室)       → 05-room-*.html
 *   ⑦ 生成 classroom/report.md 与 classroom/structure.json
 *
 * 产物全部落在 `tools/probe/output/classroom/`（已被 .gitignore 忽略），默认脱敏。
 *
 * 用法：
 *   node tools/probe/classroom-probe.mjs                 # 自动选第一个教学区/教学楼/教室
 *   node tools/probe/classroom-probe.mjs --week 6 --day 1
 *   node tools/probe/classroom-probe.mjs --aid 353 --building 1688 --room 12345
 *   node tools/probe/classroom-probe.mjs --all-areas      # 每个教学区都抓一次教学楼列表
 *   node tools/probe/classroom-probe.mjs --verbose
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HttpClient } from './lib/http.mjs';
import { loadSession, verifySession } from './lib/auth.mjs';
import { assertSafeOutDir, ensureOutDir, detectErrorPage } from './lib/dumper.mjs';
import { createRedactor } from './lib/redact.mjs';
import { stripTags, stripHtmlComments, decodeEntities, normalizeText } from './lib/text.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_OUT = path.join(HERE, 'output');

const QUERY_PATH = 'teacher/teachresource/roomschedulequery.jsdo';
const ROOM_PATH = 'teacher/teachresource/roomschedule.jsdo';
const WEEK_PATH = 'teacher/teachresource/roomschedule_week.jsdo';
const CALENDAR_PATH = 'calendarinfo/viewCalendarInfo.do';

/** 不在自动选择范围内的占位项 */
const PLACEHOLDER_VALUES = new Set(['', '-1', '0', 'null', 'undefined']);

/* ------------------------------------------------------------------ *
 * 命令行
 * ------------------------------------------------------------------ */

const HELP = `空教室查询 · 定向探测

用法：node tools/probe/classroom-probe.mjs [选项]

选项：
  --out <目录>       输出目录（默认 tools/probe/output）
  --session <文件>   会话文件（默认 <out>/session.json）
  --base <URL>       教务基址（默认 http://jwzx.hrbust.edu.cn/academic/）
  --timeout <ms>     单请求超时（默认 20000）
  --delay <ms>       请求间隔（默认 300）
  --week <n>         第几周 whichweek（默认取当前教学周，取不到则 1）
  --day <n>          星期几 week，1=周一（默认 1）
  --aid <id>         指定教学区
  --building <id>    指定教学楼
  --room <id>        指定教室
  --rooms <n>        额外探测的教室数量（默认 1）
  --all-areas        对每个教学区都抓一次教学楼列表
  --no-redact        关闭产物脱敏（产物将含真实姓名等隐私，请勿分享）
  --verbose          打印每个请求的详细日志
  -h, --help         显示帮助

说明：
  · 参数语义易错：whichweek 是「第几周」，week 是「星期几」。
  · buildingid 与 aid 绑定，脚本不会跨教学区复用教学楼。
  · 产物：<out>/classroom/{01-query.html,…,report.md,structure.json}
`;

function parseArgs(argv) {
  const opts = {
    outDir: DEFAULT_OUT,
    sessionFile: '',
    baseUrl: 'http://jwzx.hrbust.edu.cn/academic/',
    timeout: 20000,
    delay: 300,
    week: 0,
    day: 1,
    aid: '',
    building: '',
    room: '',
    rooms: 1,
    allAreas: false,
    redact: true,
    verbose: false,
    help: false
  };

  const valueFlags = new Map([
    ['--out', 'outDir'],
    ['--session', 'sessionFile'],
    ['--base', 'baseUrl'],
    ['--timeout', 'timeout'],
    ['--delay', 'delay'],
    ['--week', 'week'],
    ['--day', 'day'],
    ['--aid', 'aid'],
    ['--building', 'building'],
    ['--room', 'room'],
    ['--rooms', 'rooms']
  ]);

  const numericKeys = new Set(['timeout', 'delay', 'week', 'day', 'rooms']);

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') {
      opts.help = true;
      continue;
    }
    if (arg === '--all-areas') {
      opts.allAreas = true;
      continue;
    }
    if (arg === '--no-redact') {
      opts.redact = false;
      continue;
    }
    if (arg === '--verbose') {
      opts.verbose = true;
      continue;
    }
    const key = valueFlags.get(arg);
    if (key) {
      const raw = argv[i + 1];
      if (raw === undefined || raw.startsWith('--')) {
        throw new Error(`参数 ${arg} 缺少取值`);
      }
      opts[key] = numericKeys.has(key) ? Number(raw) : raw;
      i += 1;
      continue;
    }
    throw new Error(`未知参数：${arg}（使用 --help 查看用法）`);
  }

  return opts;
}

/* ------------------------------------------------------------------ *
 * 通用小工具
 * ------------------------------------------------------------------ */

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 单元格 / 选项文本清洗 */
function cleanText(html) {
  return normalizeText(decodeEntities(stripTags(stripHtmlComments(html))));
}

/** Markdown 表格单元格转义 */
function mdCell(text) {
  return String(text ?? '').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

function toNumber(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

/* ------------------------------------------------------------------ *
 * 表单结构解析（不依赖 DOM，Node 端零依赖）
 * ------------------------------------------------------------------ */

/**
 * 取出指定 name 的 select 选项
 * @returns {Array<{value:string,label:string,selected:boolean}>|null} 找不到该 select 时返回 null
 */
function parseSelectOptions(html, name) {
  const clean = stripHtmlComments(html);
  const selectRe = new RegExp(
    `<select\\b[^>]*\\bname\\s*=\\s*["']?${name}["']?[^>]*>([\\s\\S]*?)<\\/select>`,
    'i'
  );
  const block = clean.match(selectRe);
  if (!block) return null;

  const options = [];
  const optRe = /<option\b([^>]*)>([\s\S]*?)<\/option>/gi;
  let match;
  while ((match = optRe.exec(block[1]))) {
    const attr = match[1] || '';
    const label = cleanText(match[2]);
    const valueMatch = attr.match(/value\s*=\s*["']?([^"'>\s]*)/i);
    options.push({
      value: valueMatch ? valueMatch[1] : label,
      label,
      selected: /\bselected\b/i.test(attr)
    });
  }
  return options;
}

/** 排除「请选择」类占位项 */
function realOptions(options) {
  return (options || []).filter(
    (opt) => !PLACEHOLDER_VALUES.has(String(opt.value).trim()) && !/^请选择/.test(opt.label)
  );
}

/** 表单里已选中的值（含 hidden 字段），用于原样回放 */
function collectFormValues(html) {
  const values = {};

  const hiddenRe = /<input\b([^>]*\btype\s*=\s*["']?hidden["']?[^>]*)>/gi;
  let match;
  while ((match = hiddenRe.exec(stripHtmlComments(html)))) {
    const attr = match[1];
    const name = (attr.match(/name\s*=\s*["']?([^"'>\s]+)/i) || [])[1];
    if (!name) continue;
    values[name] = (attr.match(/value\s*=\s*["']([^"']*)["']/i) || [])[1] ?? '';
  }

  for (const name of ['aid', 'buildingid', 'room', 'whichweek', 'week']) {
    const options = parseSelectOptions(html, name);
    if (!options) continue;
    const selected = options.find((opt) => opt.selected) || options[0];
    if (selected) values[name] = selected.value;
  }

  return values;
}

/* ------------------------------------------------------------------ *
 * 表格结构分析
 * ------------------------------------------------------------------ */

function parseTableRows(tableHtml) {
  const rows = [];
  const trRe = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi;
  let trMatch;
  while ((trMatch = trRe.exec(tableHtml))) {
    const cells = [];
    const cellRe = /<t([hd])\b([^>]*)>([\s\S]*?)<\/t\1>/gi;
    let cellMatch;
    while ((cellMatch = cellRe.exec(trMatch[1]))) {
      const attr = cellMatch[2] || '';
      cells.push({
        tag: cellMatch[1].toLowerCase(),
        text: cleanText(cellMatch[3]),
        colspan: toNumber((attr.match(/colspan\s*=\s*["']?(\d+)/i) || [])[1], 1),
        rowspan: toNumber((attr.match(/rowspan\s*=\s*["']?(\d+)/i) || [])[1], 1)
      });
    }
    if (cells.length) rows.push(cells);
  }
  return rows;
}

/** 所有表格的规模与首行/前几行文本 */
function analyzeTables(html) {
  const clean = stripHtmlComments(html)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ');

  const tables = [];
  const tableRe = /<table\b([^>]*)>([\s\S]*?)<\/table>/gi;
  let tableMatch;
  while ((tableMatch = tableRe.exec(clean))) {
    const attr = tableMatch[1] || '';
    const rows = parseTableRows(tableMatch[2]);
    if (!rows.length) continue;

    const columnCount = rows.reduce(
      (max, row) => Math.max(max, row.reduce((sum, cell) => sum + cell.colspan, 0)),
      0
    );

    tables.push({
      className: (attr.match(/class\s*=\s*["']([^"']+)["']/i) || [])[1] || '',
      id: (attr.match(/id\s*=\s*["']([^"']+)["']/i) || [])[1] || '',
      rowCount: rows.length,
      columnCount,
      // 首行是否为表头（全是 th 或首行文本与后续不同）
      header: rows[0].map((cell) => cell.text),
      preview: rows.slice(1, 4).map((row) => row.map((cell) => cell.text)),
      // 行列规模足够且存在空单元格 → 很像课表矩阵
      matrixLikely:
        rows.length >= 4 &&
        columnCount >= 6 &&
        rows.flat().filter((cell) => !cell.text).length >= 3
    });
  }

  return tables;
}

/** 从 URP「提示信息」页取出错误文案 */
function extractUrupError(html) {
  const content = stripHtmlComments(String(html || ''));
  const cell = content.match(/<td[^>]*\bid\s*=\s*["']content_margin["'][^>]*>([\s\S]*?)<\/td>/i);
  if (cell) {
    const text = cleanText(cell[1]);
    if (text) return text;
  }
  const title = content.match(/<title>\s*([^<]+?)\s*<\/title>/i);
  return title ? normalizeText(title[1]) : '';
}

/** 课表里常见的课程/教师痕迹，用来判断表格是否真是排课数据 */
function scheduleSignals(html) {
  const text = cleanText(html);
  return {
    hasWeekday: /周一|星期二|周三|周四|周五|周六|周日|星期一/.test(text),
    hasSection: /第\s*\d+\s*节|大节|节次/.test(text),
    hasCourse: /课程|教师|老师|班级|上课/.test(text),
    hasFreeHint: /空闲|占用|无课/.test(text)
  };
}

/* ------------------------------------------------------------------ *
 * 会话与请求
 * ------------------------------------------------------------------ */

async function resolveCurrentWeek(client, opts) {
  if (opts.week) return { week: opts.week, source: '命令行 --week' };
  const res = await client.get(CALENDAR_PATH, { timeout: opts.timeout });
  if (res.ok) {
    const text = cleanText(res.text);
    const patterns = [
      /当前(?:教学)?周(?:次)?\s*[：:]?\s*第?\s*(\d{1,2})\s*周/,
      /第\s*(\d{1,2})\s*周/,
      /week\s*[=:]\s*["']?(\d{1,2})/i
    ];
    for (const re of patterns) {
      const m = text.match(re);
      if (m) {
        const week = Number(m[1]);
        if (week >= 1 && week <= 30) return { week, source: `校历页 ${CALENDAR_PATH}` };
      }
    }
  }
  return { week: 1, source: '校历页未解析出周次，回退 1' };
}

/* ------------------------------------------------------------------ *
 * 主流程
 * ------------------------------------------------------------------ */

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(HELP);
    return 0;
  }

  const outRoot = ensureOutDir(opts.outDir);
  const samplesDir = assertSafeOutDir(path.join(outRoot, 'classroom'));
  fs.mkdirSync(samplesDir, { recursive: true });

  const sessionFile = opts.sessionFile || path.join(outRoot, 'session.json');
  const client = new HttpClient({
    baseUrl: opts.baseUrl,
    timeout: opts.timeout,
    verbose: opts.verbose,
    log: (msg) => console.log(msg)
  });

  const session = loadSession(client, sessionFile);
  if (!session) {
    console.error(`\n找不到会话文件：${sessionFile}`);
    console.error('请先运行 `node tools/probe/server.mjs --open` 登录，然后重跑本脚本。\n');
    return 2;
  }

  console.log(`\n会话文件：${sessionFile}（保存于 ${session.savedAt || '未知时间'}）`);
  console.log('校验会话…');
  const alive = await verifySession(client);
  if (!alive.loggedIn) {
    console.error('\n会话已失效。请运行 `node tools/probe/server.mjs --open` 重新登录后重跑。\n');
    return 2;
  }
  console.log(`会话有效（状态 ${alive.status}）。\n`);

  const redactor = createRedactor({ enabled: opts.redact, username: session.username || '' });
  const records = [];
  const delay = Math.max(0, opts.delay);

  /** 发起一次请求、落盘样本、记录结构 */
  async function hit({ name, method = 'GET', apiPath, params = {}, note = '' }) {
    await sleep(delay);
    const query = new URLSearchParams(
      Object.entries(params).filter(([, v]) => v !== undefined && v !== null)
    ).toString();
    let res;
    if (method === 'POST') {
      res = await client.post(apiPath, params, {
        headers: { Referer: client.resolve(QUERY_PATH) },
        timeout: opts.timeout
      });
    } else {
      res = await client.get(query ? `${apiPath}?${query}` : apiPath, {
        headers: { Referer: client.resolve(QUERY_PATH) },
        timeout: opts.timeout
      });
    }

    const file = `${name}.html`;
    const text = redactor.redact(res.text || '');
    fs.writeFileSync(path.join(samplesDir, file), text, 'utf8');

    const isError = detectErrorPage(res.status, res.text);
    const record = {
      name,
      file,
      method,
      path: apiPath,
      params,
      status: res.status,
      charset: res.charset || '',
      bytes: res.buffer?.length || 0,
      elapsedMs: res.elapsedMs || 0,
      isErrorPage: isError,
      urupError: isError ? extractUrupError(res.text) : '',
      tables: analyzeTables(text),
      signals: scheduleSignals(text),
      options: {
        aid: realOptions(parseSelectOptions(text, 'aid')),
        buildingid: realOptions(parseSelectOptions(text, 'buildingid')),
        room: realOptions(parseSelectOptions(text, 'room')),
        whichweek: realOptions(parseSelectOptions(text, 'whichweek')),
        week: realOptions(parseSelectOptions(text, 'week'))
      },
      preview: cleanText(text).slice(0, 200),
      note,
      networkError: res.error || ''
    };
    records.push(record);

    const flag = isError ? `错误页：${record.urupError || '未取到文案'}` : `${record.tables.length} 个表格`;
    console.log(
      `  ${method.padEnd(4)} ${String(res.status).padEnd(3)} ` +
        `${String(record.bytes).padStart(6)}B ${String(record.elapsedMs).padStart(5)}ms  ` +
        `${name}.html  ${flag}`
    );

    return { res, text, record };
  }

  const weekInfo = await resolveCurrentWeek(client, opts);
  console.log(`周次：第 ${weekInfo.week} 周（来源：${weekInfo.source}），星期：${opts.day}\n`);

  // ① 查询页
  console.log('① 抓取查询页');
  const query = await hit({ name: '01-query', apiPath: QUERY_PATH });

  const areas = realOptions(parseSelectOptions(query.text, 'aid'));
  if (!areas.length) {
    console.error('\n查询页没有解析出教学区选项，后续级联无法继续。请检查会话权限或页面结构是否变化。\n');
  }
  const formDefaults = collectFormValues(query.text);
  console.log(`  教学区 ${areas.length} 个；表单默认 ${JSON.stringify(formDefaults)}\n`);

  const areaTargets = opts.aid
    ? areas.filter((a) => a.value === String(opts.aid))
    : opts.allAreas
      ? areas
      : areas.slice(0, 1);

  if (opts.aid && !areaTargets.length) {
    throw new Error(`指定教学区 ${opts.aid} 不在查询页的选项里`);
  }

  // ② 教学楼（POST 为主，GET 对照）
  console.log('② 抓取教学楼列表（按教学区）');
  const areaBuildings = new Map();
  for (const area of areaTargets) {
    const params = {
      ...formDefaults,
      aid: area.value,
      buildingid: '-1',
      room: '-1',
      whichweek: '-1',
      week: String(opts.day)
    };
    const post = await hit({
      name: `02-buildings-post-a${area.value}`,
      method: 'POST',
      apiPath: QUERY_PATH,
      params,
      note: `POST 级联：${area.label}`
    });
    const buildings = realOptions(parseSelectOptions(post.text, 'buildingid'));
    areaBuildings.set(area.value, buildings);

    await hit({
      name: `02b-buildings-get-a${area.value}`,
      method: 'GET',
      apiPath: QUERY_PATH,
      params: { aid: area.value },
      note: `GET 对照：${area.label}`
    });

    console.log(`  ${area.label}（${area.value}）：教学楼 ${buildings.length} 个\n`);
  }

  // ③ 教室
  const chosenArea = areaTargets[0];
  const buildings = areaBuildings.get(chosenArea.value) || [];
  const building =
    buildings.find((b) => b.value === String(opts.building)) || buildings[0];

  if (!building) {
    console.error('没有可选教学楼，无法继续探测教室与课表。\n');
    return writeReport({ opts, outRoot, samplesDir, records, weekInfo, areaBuildings, session });
  }

  console.log(`③ 抓取教室列表（${chosenArea.label} / ${building.label}）`);
  const roomPageParams = {
    ...formDefaults,
    aid: chosenArea.value,
    buildingid: building.value,
    room: '-1',
    whichweek: '-1',
    week: String(opts.day)
  };
  const roomPage = await hit({
    name: `03-rooms-a${chosenArea.value}-b${building.value}`,
    method: 'POST',
    apiPath: QUERY_PATH,
    params: roomPageParams,
    note: '选定教学楼后取教室列表'
  });
  const rooms = realOptions(parseSelectOptions(roomPage.text, 'room'));
  console.log(`  教室 ${rooms.length} 个\n`);

  if (!rooms.length) {
    console.error('教学楼下没有解析出教室选项，可能是该楼无排课或结构变化。\n');
    return writeReport({ opts, outRoot, samplesDir, records, weekInfo, areaBuildings, session });
  }

  const roomTargets = opts.room
    ? rooms.filter((r) => r.value === String(opts.room))
    : rooms.slice(0, Math.max(1, opts.rooms));

  // ④ 按周查询（整楼）
  console.log('④ 按周查询（整栋教学楼）');
  const weekParams = {
    ...roomPageParams,
    room: '-1',
    whichweek: String(weekInfo.week),
    week: String(opts.day)
  };
  await hit({
    name: `04-week-a${chosenArea.value}-b${building.value}-w${weekInfo.week}-d${opts.day}`,
    method: 'POST',
    apiPath: WEEK_PATH,
    params: weekParams,
    note: '按周：整楼 × 指定周次 × 指定星期'
  });

  const weekAllParams = { ...weekParams, week: '-1' };
  await hit({
    name: `04b-week-a${chosenArea.value}-b${building.value}-w${weekInfo.week}-dall`,
    method: 'POST',
    apiPath: WEEK_PATH,
    params: weekAllParams,
    note: '按周：week=-1，确认是否返回整周矩阵'
  });

  const weekAllWeekParams = { ...weekParams, whichweek: '-1', week: '-1' };
  await hit({
    name: `04c-week-a${chosenArea.value}-b${building.value}-wall-dall`,
    method: 'POST',
    apiPath: WEEK_PATH,
    params: weekAllWeekParams,
    note: '按周：whichweek=-1 & week=-1，确认参数语义'
  });

  // ⑤ 按教室查询（全周排课）
  console.log('\n⑤ 按教室查询（单个教室）');
  for (const room of roomTargets) {
    await hit({
      name: `05-room-b${building.value}-r${room.value}-w${weekInfo.week}`,
      method: 'POST',
      apiPath: ROOM_PATH,
      params: {
        ...roomPageParams,
        room: room.value,
        whichweek: String(weekInfo.week)
      },
      note: `按教室：${room.label}`
    });
  }

  return writeReport({ opts, outRoot, samplesDir, records, weekInfo, areaBuildings, session });
}

/* ------------------------------------------------------------------ *
 * 报告
 * ------------------------------------------------------------------ */

function writeReport({ opts, outRoot, samplesDir, records, weekInfo, areaBuildings, session }) {
  const generatedAt = new Date().toISOString();
  const structure = {
    generatedAt,
    baseUrl: opts.baseUrl,
    session: { savedAt: session?.savedAt || '', user: session?.username || '' },
    targets: {
      week: weekInfo.week,
      weekSource: weekInfo.source,
      day: opts.day,
      aid: opts.aid || '',
      building: opts.building || '',
      room: opts.room || ''
    },
    areaBuildings: Object.fromEntries(areaBuildings),
    redaction: { enabled: opts.redact },
    records: records.map((record) => ({
      name: record.name,
      file: record.file,
      method: record.method,
      path: record.path,
      params: record.params,
      status: record.status,
      charset: record.charset,
      bytes: record.bytes,
      elapsedMs: record.elapsedMs,
      isErrorPage: record.isErrorPage,
      urupError: record.urupError,
      networkError: record.networkError,
      tables: record.tables,
      signals: record.signals,
      optionCounts: Object.fromEntries(
        Object.entries(record.options).map(([key, value]) => [key, Array.isArray(value) ? value.length : 0])
      ),
      // 选项全集只保留一份（查询页/教室页），避免报告冗长
      note: record.note
    }))
  };

  // 选项全集单独落盘，便于前端直接对照
  const optionSnapshots = {};
  for (const record of records) {
    optionSnapshots[record.name] = record.options;
  }
  structure.optionSnapshots = optionSnapshots;

  fs.writeFileSync(
    path.join(samplesDir, 'structure.json'),
    JSON.stringify(structure, null, 2),
    'utf8'
  );

  fs.writeFileSync(
    path.join(samplesDir, 'report.md'),
    renderMarkdown({ records, weekInfo, areaBuildings, samplesDir, generatedAt }),
    'utf8'
  );

  console.log(`\n产物目录：${samplesDir}`);
  console.log('  report.md    人类可读探测报告');
  console.log('  structure.json  结构化结果（表格规模 / 选项 / 参数）');
  console.log('\n下一步：把 report.md 与 structure.json 交给实现方，按真实表结构写解析。\n');
  return 0;
}

/** 报告里统计脱敏替换量（仅在启用时可用） */
function renderMarkdown({ records, weekInfo, areaBuildings, samplesDir, generatedAt }) {
  const lines = [];
  lines.push('# 空教室查询 · 定向探测报告');
  lines.push('');
  lines.push(`- 生成时间：${generatedAt}`);
  lines.push(`- 样本目录：${samplesDir}`);
  lines.push(`- 周次：第 ${weekInfo.week} 周（${weekInfo.source}）`);
  lines.push('');

  lines.push('## 结论速览');
  lines.push('');
  lines.push('| 样本 | 方法 | 状态 | 编码 | 字节 | 表格 | 判定 |');
  lines.push('| --- | --- | --- | --- | --- | --- | --- |');
  for (const record of records) {
    const verdict = record.networkError
      ? `网络错误：${record.networkError}`
      : record.isErrorPage
        ? `错误页：${record.urupError || '未取到文案'}`
        : `${record.tables.length} 个表格`;
    lines.push(
      `| \`${record.file}\` | ${record.method} | ${record.status} | ${record.charset || '-'} | ` +
        `${record.bytes} | ${record.tables.length} | ${mdCell(verdict)} |`
    );
  }
  lines.push('');

  lines.push('## 教学区 → 教学楼');
  lines.push('');
  for (const [aid, buildings] of areaBuildings) {
    const list = buildings.map((b) => `${b.label}(${b.value})`).join('、') || '（无）';
    lines.push(`- **${aid}**：${list}`);
  }
  lines.push('');

  lines.push('## 逐条详情');
  lines.push('');
  for (const record of records) {
    lines.push(`### ${record.name}`);
    lines.push('');
    lines.push(`- 请求：\`${record.method} ${record.path}\``);
    lines.push(`- 参数：\`${JSON.stringify(record.params)}\``);
    lines.push(`- 响应：${record.status}，${record.charset || '未知编码'}，${record.bytes} 字节，${record.elapsedMs}ms`);
    if (record.note) lines.push(`- 说明：${record.note}`);
    if (record.urupError) lines.push(`- 错误页文案：\`${record.urupError}\``);

    const optionLines = Object.entries(record.options)
      .filter(([, value]) => Array.isArray(value) && value.length)
      .map(([key, value]) => `${key} ${value.length} 项（如 ${value.slice(0, 5).map((o) => `${o.label}(${o.value})`).join('、')}）`);
    if (optionLines.length) lines.push(`- 选项：${optionLines.join('；')}`);

    const signals = Object.entries(record.signals)
      .filter(([, hit]) => hit)
      .map(([key]) => key);
    if (signals.length) lines.push(`- 关键字命中：${signals.join('、')}`);

    if (record.tables.length) {
      lines.push('');
      lines.push('| 表 | 行 | 列 | class | 疑似矩阵 | 表头 |');
      lines.push('| --- | --- | --- | --- | --- | --- |');
      record.tables.forEach((table, index) => {
        lines.push(
          `| #${index + 1} | ${table.rowCount} | ${table.columnCount} | ${mdCell(table.className || '-')} | ` +
            `${table.matrixLikely ? '是' : '否'} | ${mdCell(table.header.slice(0, 10).join(' / '))} |`
        );
      });

      for (const [index, table] of record.tables.entries()) {
        lines.push('');
        lines.push(`表 #${index + 1} 前 3 行：`);
        lines.push('');
        for (const row of table.preview) {
          lines.push(`- ${mdCell(row.slice(0, 12).join(' | ') || '（空行）')}`);
        }
      }
    }

    lines.push('');
  }

  lines.push('## 判读提示');
  lines.push('');
  lines.push('- `04-week-*` 若返回「教室 × 星期/节次」矩阵，主形态选「按周查全楼空闲」；');
  lines.push('  若只返回单日或单教室，则改为「按教室全周排课」或对教室列表串行聚合。');
  lines.push('- `04b/04c` 用于确认 `whichweek` / `week` 的语义：`week=-1` 能否拿到整周。');
  lines.push('- 判定为错误页但状态是 200 属正常现象（URP 错误页固定标题为「提示信息」）。');
  lines.push('- 若样本中姓名等字段显示为占位符，说明脱敏已生效，实现解析时以结构与列序为准。');
  lines.push('');

  return lines.join('\n');
}

main()
  .then((code) => {
    process.exitCode = typeof code === 'number' ? code : 0;
  })
  .catch((err) => {
    console.error(`\n探测失败：${err.message}\n`);
    if (process.env.DEBUG) console.error(err.stack);
    process.exitCode = 1;
  });
