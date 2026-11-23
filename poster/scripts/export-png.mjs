#!/usr/bin/env node
/**
 * 无头浏览器打开海报生成器，点击「下载 PNG」并把 2 倍图落盘到 poster/out/。
 *
 * 依赖 web/node_modules 的 playwright-core；与 capture-web.mjs 共用同一套浏览器回退逻辑。
 * 运行：node poster/scripts/export-png.mjs [海报 id]
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
const outDir = path.join(posterRoot, 'out')
const PORT = Number(process.env.EXPORT_PORT || 5176)
const BASE_URL = `http://127.0.0.1:${PORT}`
/** 可选：要切到的海报名称（按钮文案）；不传则导出清单里的第一张 */
const posterName = process.argv[2] || ''

const requireFromWeb = createRequire(path.join(webRoot, 'package.json'))
const { chromium } = requireFromWeb('playwright-core')

function resolveChromiumExecutable() {
  const candidates = [
    process.env.CAPTURE_BROWSER_PATH,
    chromium.executablePath(),
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)
  return candidates.find((candidate) => existsSync(candidate))
}

async function waitForServer(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      if (res.ok) return
    } catch {
      // 等 vite 起来
    }
    await sleep(300)
  }
  throw new Error(`海报生成器未在 ${timeoutMs}ms 内就绪：${url}`)
}

function startDevServer() {
  const viteBin = path.join(posterRoot, 'node_modules', 'vite', 'bin', 'vite.js')
  return spawn(process.execPath, [viteBin, '--port', String(PORT), '--strictPort', '--host', '127.0.0.1'], {
    cwd: posterRoot,
    stdio: 'ignore',
    env: { ...process.env, BROWSER: 'none' }
  })
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
    browser = await chromium.launch({ headless: true, executablePath: resolveChromiumExecutable() })
    const context = await browser.newContext({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 })
    const page = await context.newPage()
    await page.goto(BASE_URL, { waitUntil: 'networkidle' })

    if (posterName) {
      const button = page.getByRole('button', { name: posterName, exact: false })
      if (await button.count()) await button.first().click()
      await sleep(400)
    }

    // 等海报里的位图全部解码完成，避免导出空白图
    await page.evaluate(async () => {
      await Promise.all(
        [...document.images].filter((img) => !img.complete).map((img) => img.decode().catch(() => {}))
      )
    })
    await sleep(600)

    const downloadPromise = page.waitForEvent('download', { timeout: 60_000 })
    await page.getByRole('button', { name: /下载 PNG/ }).click()
    const download = await downloadPromise
    const target = path.join(outDir, download.suggestedFilename())
    await download.saveAs(target)
    console.log(`已导出：${target}`)
  } finally {
    await browser?.close()
    stopProcess(server)
  }
}

main().catch((err) => {
  console.error(err)
  process.exitCode = 1
})
