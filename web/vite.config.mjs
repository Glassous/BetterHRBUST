import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { readFileSync } from 'node:fs'
import path from 'node:path'

// 构建期注入应用版本（桌面端更新模块的版本回退值，权威值仍取 Tauri 运行时 getVersion()）
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))

// 教务在线地址（开发 / 自托管代理目标）：默认官方，可用 BETTER_HRBUST_ACADEMIC 覆盖。
// 支持粘贴完整主页链接或裸域名，这里统一归一化为 origin。
const OFFICIAL_ACADEMIC_ORIGIN = 'http://jwzx.hrbust.edu.cn'

function resolveAcademicOrigin(raw) {
  const text = String(raw || '').trim()
  if (!text) return OFFICIAL_ACADEMIC_ORIGIN
  try {
    const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `http://${text}`
    const url = new URL(withScheme)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('仅支持 http/https')
    return url.origin
  } catch (err) {
    console.warn(`[vite] BETTER_HRBUST_ACADEMIC 无法解析（${text}）：${err.message}，已回退官方教务地址`)
    return OFFICIAL_ACADEMIC_ORIGIN
  }
}

const academicOrigin = resolveAcademicOrigin(process.env.BETTER_HRBUST_ACADEMIC)
const academicAuthority = new URL(academicOrigin).host

// 前端在运行时切换教务地址时附带的提示头（设置页保存自定义地址后开始发送）
const ACADEMIC_BASE_HEADER = 'x-betterhrbust-academic-base'

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// 与 Rust 侧 rewrite_location 等价：把指向上游的 302 绝对地址收敛回本地相对路径
function academicLocationPattern(authority) {
  return new RegExp(`^https?:\\/\\/${escapeRegExp(authority)}\\/academic\\/?`, 'i')
}

// configure 时捕获代理实例，供运行时按请求改写上游（换地址无需重启 dev server）
let academicProxyServer = null

const academicProxy = {
  target: academicOrigin,
  changeOrigin: true,
  autoRewrite: true,
  cookieDomainRewrite: '',
  headers: {
    Host: academicAuthority,
    Referer: `${academicOrigin}/academic/`
  },
  configure: (proxy, _options) => {
    academicProxyServer = proxy
    proxy.on('proxyRes', (proxyRes, req, res) => {
      const location = proxyRes.headers['location'];
      if (location) {
        // 关键：教务系统 302 跳转会带上完整域名 http://jwzx.hrbust.edu.cn/academic/...
        // 现代浏览器会触发 HTTPS 自动升级或跨域拦截，由于教务系统未开启 443 HTTPS 端口会导致 ERR_CONNECTION_CLOSED
        // 此处将重定向地址统一重写为本地相对路径 /academic/...（自定义上游同样适用）
        const authority = req.__academicAuthority || academicAuthority
        proxyRes.headers['location'] = location.replace(academicLocationPattern(authority), '/academic/');
      }
    });
  }
};

/**
 * 运行时上游中间件：网页版在设置页换教务地址后，前端会给 `/academic` 请求带上
 * `x-betterhrbust-academic-base`，这里按请求把同一个代理实例指向该 origin。
 *
 * 为什么需要它：教务站点普遍不返回 CORS 头，浏览器不允许页面直接读取跨域响应。
 * 直连被拦下时前端自动改走同源 `/academic/`，由这里转发——Cookie 与 302 都留在本地，
 * 登录流程与官方默认地址完全一致，也不需要为了换地址重启 dev server。
 */
function academicRuntimeProxy() {
  const handle = (req, res, next) => {
    const raw = req.headers[ACADEMIC_BASE_HEADER]
    if (!raw || !req.url || !req.url.startsWith('/academic') || !academicProxyServer) {
      return next()
    }
    const origin = resolveAcademicOrigin(raw)
    const authority = new URL(origin).host
    req.__academicAuthority = authority
    academicProxyServer.web(req, res, {
      target: origin,
      changeOrigin: true,
      autoRewrite: true,
      cookieDomainRewrite: '',
      headers: {
        Host: authority,
        Referer: `${origin}/academic/`
      }
    })
  }
  return {
    name: 'betterhrbust-academic-runtime-proxy',
    configureServer(server) {
      server.middlewares.use(handle)
    },
    configurePreviewServer(server) {
      server.middlewares.use(handle)
    }
  }
}

export default defineConfig({
  plugins: [vue(), tailwindcss(), academicRuntimeProxy()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
  resolve: {
    alias: {
      '@': path.resolve(process.cwd(), './src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 注意顺序：'/api/probe'（探测工具）必须位于 '/api' 之前，前缀更具体者优先匹配
      '/api/probe': {
        target: 'http://127.0.0.1:7788',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api\/probe/, '')
      },
      // BetterHRBUST 服务端（Go + Gin）：意见反馈与管理端接口
      '/api': {
        target: process.env.BETTER_HRBUST_API || 'http://127.0.0.1:9191',
        changeOrigin: true
      },
      '/academic': academicProxy
    }
  },
  preview: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.BETTER_HRBUST_API || 'http://127.0.0.1:9191',
        changeOrigin: true
      },
      '/academic': academicProxy
    }
  }
})
