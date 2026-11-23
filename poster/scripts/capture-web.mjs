#!/usr/bin/env node
/**
 * 用模拟教务数据启动 Web 端（Vite dev），再用无头浏览器截取「课表增强」相关截图。
 *
 * 产出（写入 poster/public/）：
 *   web-timetable.png       整周课表 + 调课模式（已调出 / 调课 / 实验 / 自定义课程）
 *   web-reschedule.png      确认单周临时调课弹窗
 *
 * 依赖 web/node_modules 里的 playwright-core 与本机 Chromium（ms-playwright）。
 * 运行：node poster/scripts/capture-web.mjs
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const posterRoot = path.resolve(here, '..')
const repoRoot = path.resolve(posterRoot, '..')
const webRoot = path.join(repoRoot, 'web')
const outDir = path.join(posterRoot, 'public')
const PORT = Number(process.env.CAPTURE_PORT || 5199)
const BASE_URL = `http://127.0.0.1:${PORT}`
/** 页面缩放：把桌面布局压进固定画幅，海报里再按比例缩小仍然清晰 */
const PAGE_ZOOM = Number(process.env.CAPTURE_ZOOM || 1)
const VIEWPORT = {
  width: Number(process.env.CAPTURE_WIDTH || 1440),
  height: Number(process.env.CAPTURE_HEIGHT || 1000)
}

const requireFromWeb = createRequire(path.join(webRoot, 'package.json'))
const { chromium } = requireFromWeb('playwright-core')

const WEEK = 13 // 2026-11-23 所在教学周（校历 2026-08-31 开学）

function todayKey() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function combineCell(day, sectionIndex, courseName, courseSeq, location, teacher, extra = {}) {
  return {
    id: `${day}-${sectionIndex}-${courseName}`,
    courseId: courseSeq,
    day,
    sectionIndex,
    sectionLabel: `第${sectionIndex}大节`,
    courseName,
    courseSeq,
    location,
    teacher,
    weeks: '1-16周',
    hoursType: '讲课学时',
    ...extra
  }
}

const combineCells = [
  combineCell(1, 1, '高等数学 A(上)', '01', '西区-新D101', '张伟'),
  combineCell(1, 3, '线性代数', '02', '西区-新D202', '王强'),
  combineCell(2, 1, '大学英语（三）', '03', '南区-教2-201', '李娜'),
  combineCell(3, 1, '大学物理（下）', '04', '西区-新D303', '陈晨'),
  combineCell(3, 2, '数据结构', '05', '东区-计算中心B302', '刘洋'),
  combineCell(3, 4, '体育（三）', '06', '体育馆', '赵磊'),
  combineCell(4, 2, '程序设计基础', '07', '东区-计算中心A401', '孙浩'),
  combineCell(4, 5, '思想道德与法治', '08', '南区-教1-101', '周敏'),
  combineCell(5, 1, '离散数学', '09', '西区-新D201', '吴迪'),
  combineCell(5, 3, '数字逻辑', '10', '东区-计算中心B305', '郑凯')
]

/** 小节视图缓存：由大节拆成两小节，保证切到「小节明细」也不空白 */
const baseCells = combineCells.flatMap((cell) => {
  const baseStart = (cell.sectionIndex - 1) * 2 + 1
  return [baseStart, baseStart + 1].map((period) => ({
    ...cell,
    id: `${cell.id}-base${period}`,
    sectionIndex: period,
    sectionLabel: `第${period}节`
  }))
})

const seed = {
  better_hrbust_has_session: 'true',
  better_hrbust_cached_profile: JSON.stringify({
    internalId: '2023020311',
    studentNumber: '2023020311',
    name: '李知远'
  }),
  better_hrbust_last_login_at: String(Date.now()),
  better_hrbust_theme: 'light',
  better_hrbust_cache_sync_meta: JSON.stringify({
    lastSyncAt: Date.now() - 12 * 60 * 1000,
    lastSyncDate: todayKey(),
    lastError: ''
  }),
  better_hrbust_cache_timetable: JSON.stringify({ cells: combineCells, unarranged: [] }),
  better_hrbust_cache_timetable_base: JSON.stringify({ cells: baseCells, unarranged: [] }),
  better_hrbust_cache_timetable_reschedules: JSON.stringify([
    {
      id: 'resched_demo',
      week: WEEK,
      sourceMatch: {
        cellId: '3-2-数据结构',
        courseId: '05',
        courseName: '数据结构',
        courseSeq: '05',
        originDay: 3,
        originCombineSection: 2,
        originBaseSections: [3, 4],
        originLocation: '东区-计算中心B302',
        originTeacher: '刘洋'
      },
      targetDay: 4,
      targetSectionMode: 'combine',
      targetCombineSection: 3,
      targetBaseSection: 5,
      location: '东区-2号楼405',
      teacher: '刘洋',
      note: '教师临时调课',
      createdAt: Date.now() - 26 * 60 * 60 * 1000,
      updatedAt: Date.now() - 26 * 60 * 60 * 1000
    }
  ]),
  better_hrbust_cache_timetable_custom: JSON.stringify([
    {
      id: 'custom_demo_lab',
      kind: 'custom',
      category: 'lab',
      courseName: '数字逻辑实验',
      location: '实验楼 B304',
      teacher: '郑凯',
      week: WEEK,
      day: 4,
      timeMode: 'custom',
      slotType: 'combine',
      slotPeriod: 4,
      slotSpan: 1,
      startTime: '15:30',
      endTime: '17:10',
      note: '单周实验',
      createdAt: Date.now() - 5 * 24 * 60 * 60 * 1000,
      updatedAt: Date.now() - 5 * 24 * 60 * 60 * 1000
    },
    {
      id: 'custom_demo_innovation',
      kind: 'custom',
      category: 'custom',
      courseName: '创新创业实践',
      location: '南区-教1-205',
      teacher: '周敏',
      week: WEEK,
      day: 6,
      timeMode: 'slot',
      slotType: 'combine',
      slotPeriod: 3,
      slotSpan: 1,
      startTime: '',
      endTime: '',
      note: '',
      createdAt: Date.now() - 9 * 24 * 60 * 60 * 1000,
      updatedAt: Date.now() - 9 * 24 * 60 * 60 * 1000
    }
  ]),
  better_hrbust_cache_current_courses: JSON.stringify([
    { courseName: '高等数学 A(上)', courseSeq: '01', credit: 5, teacher: '张伟' },
    { courseName: '线性代数', courseSeq: '02', credit: 3, teacher: '王强' },
    { courseName: '大学英语（三）', courseSeq: '03', credit: 2, teacher: '李娜' },
    { courseName: '大学物理（下）', courseSeq: '04', credit: 3.5, teacher: '陈晨' },
    { courseName: '数据结构', courseSeq: '05', credit: 4, teacher: '刘洋' },
    { courseName: '体育（三）', courseSeq: '06', credit: 1, teacher: '赵磊' },
    { courseName: '程序设计基础', courseSeq: '07', credit: 3, teacher: '孙浩' },
    { courseName: '思想道德与法治', courseSeq: '08', credit: 3, teacher: '周敏' },
    { courseName: '离散数学', courseSeq: '09', credit: 3, teacher: '吴迪' },
    { courseName: '数字逻辑', courseSeq: '10', credit: 3, teacher: '郑凯' }
  ]),
  better_hrbust_cache_scores: JSON.stringify([]),
  better_hrbust_cache_exams: JSON.stringify([]),
  better_hrbust_cache_notices: JSON.stringify([]),
  better_hrbust_timetable_only_current_week: 'false'
}

async function waitForServer(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      if (res.ok) return
    } catch {
      // 服务还没起来
    }
    await sleep(300)
  }
  throw new Error(`Vite dev server 未在 ${timeoutMs}ms 内就绪：${url}`)
}

function startDevServer() {
  const viteBin = path.join(webRoot, 'node_modules', 'vite', 'bin', 'vite.js')
  const child = spawn(process.execPath, [viteBin, '--port', String(PORT), '--strictPort', '--host', '127.0.0.1'], {
    cwd: webRoot,
    stdio: 'ignore',
    env: { ...process.env, BROWSER: 'none' }
  })
  return child
}

/**
 * Playwright 默认的 headless shell 未安装时，回退到完整 Chromium / 本机 Edge / Chrome。
 * 只影响截图脚本，不改变 web 端依赖。
 */
function resolveChromiumExecutable() {
  const candidates = [
    process.env.CAPTURE_BROWSER_PATH,
    chromium.executablePath(),
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)
  return candidates.find((candidate) => existsSync(candidate))
}

function stopProcess(child) {
  if (!child || child.killed) return
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

async function main() {
  mkdirSync(outDir, { recursive: true })
  const server = startDevServer()
  let browser
  try {
    await waitForServer(BASE_URL)
    browser = await chromium.launch({
      headless: true,
      executablePath: resolveChromiumExecutable()
    })
    const context = await browser.newContext({
      viewport: VIEWPORT,
      deviceScaleFactor: 2,
      locale: 'zh-CN',
      reducedMotion: 'reduce',
      colorScheme: 'light'
    })
    await context.addInitScript((values) => {
      for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value)
    }, seed)

    const page = await context.newPage()
    page.on('console', (msg) => {
      if (msg.type() === 'error') console.warn('[web]', msg.text())
    })

    await page.goto(BASE_URL, { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '智能课程表' }).click()
    await page.getByText(`第 ${WEEK} 周`).first().waitFor({ timeout: 15_000 })
    await sleep(800)

    if (PAGE_ZOOM !== 1) {
      await page.evaluate((zoom) => {
        document.documentElement.style.zoom = String(zoom)
      }, PAGE_ZOOM)
      await sleep(400)
    }

    /**
     * 截取到第 4 大节下边缘：海报只需要展示上部有内容的四行，
     * 同时避免底边切在课程卡中间。
     */
    async function captureShell(file) {
      const clip = await page.evaluate((zoom) => {
        const cells = [...document.querySelectorAll('.tt-grid-cell')]
        // 7 天 × 4 行：取第 4 行（索引 21..27）所有单元格的最大下边缘
        const bottom = Math.max(...cells.slice(21, 28).map((cell) => cell.getBoundingClientRect().bottom))
        // getBoundingClientRect 的坐标已被 CSS zoom 缩放，截图 clip 用的是未缩放坐标
        return {
          x: 0,
          y: 0,
          width: Math.ceil(document.documentElement.clientWidth / zoom),
          height: Math.ceil((bottom + 1) / zoom)
        }
      }, PAGE_ZOOM)
      await page.screenshot({ path: file, clip })
    }

    // ① 整周课表（已调出 / 调课 / 实验 / 自定义课程都在网格里）
    await captureShell(path.join(outDir, 'web-timetable.png'))

    // ② 调课模式：选中一门课后悬停目标格
    await page.getByRole('button', { name: '调课模式' }).click()
    await page.getByText('离散数学', { exact: true }).first().click()
    await sleep(3600) // 等可能出现的 toast 自动消失

    // 悬停「周六 第 2 大节」，让目标格显示「点击/拖至此处」
    const targetCell = page.locator('.tt-grid-cell').nth((2 - 1) * 7 + (6 - 1))
    await targetCell.hover()
    await sleep(400)
    await captureShell(path.join(outDir, 'web-reschedule-mode.png'))

    // ③ 点击目标格 → 确认单周临时调课弹窗
    await targetCell.click()
    const dialog = page.locator('dialog[open]')
    await dialog.waitFor({ timeout: 10_000 })
    await page.locator('input[name="location"]').fill('东区-2号楼405')
    await page.locator('input[name="note"]').fill('教师临时调课')
    await sleep(600)
    await page.screenshot({ path: path.join(outDir, 'web-reschedule.png') })
    await dialog.screenshot({ path: path.join(outDir, 'web-reschedule-dialog.png') })

    console.log('已生成：')
    for (const name of [
      'web-timetable.png',
      'web-reschedule-mode.png',
      'web-reschedule.png',
      'web-reschedule-dialog.png'
    ]) {
      console.log(`  poster/public/${name}`)
    }
  } finally {
    await browser?.close()
    stopProcess(server)
  }
}

main().catch((err) => {
  console.error(err)
  process.exitCode = 1
})
