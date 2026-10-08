# BetterHRBUST 油猴版(Tampermonkey)

将 BetterHRBUST 打包为单个油猴脚本,安装后访问教务在线(`jwzx.hrbust.edu.cn`)
时自动接管旧版 JSP 页面,渲染现代化客户端。**所有请求同源直连教务系统**,
Cookie 会话、验证码、GBK 编码解码与原生登录完全一致,无任何中转。

## 构建

```bash
cd web
npm install
npm run build:userscript
# 产物: web/dist-userscript/better-hrbust.user.js (约 390KB, gzip 158KB)
```

构建链三个部分:

| 文件 | 职责 |
| --- | --- |
| `src/main.user.js` | 油猴专用入口:清理宿主 JSP 页面(含 frameset 框架页换 body)、注入样式、挂载应用;业务代码与 Web 版共用零改动 |
| `vite.config.userscript.mjs` | 以 JS 为入口构建 IIFE 单文件;样式 `?inline` 内联、图片全部内联为 data URI |
| `scripts/wrap-userscript.mjs` | 套上 userscript 元数据头与「查看原版」菜单开关,产出 `.user.js` |

## 安装

产物 `better-hrbust.user.js` 是**全资源内联的单文件**(应用 JS + CSS + 校徽
base64 都在里面,运行时只请求教务系统本身)。

**方式 0 · 从仓库 dist 分支安装(推荐,CI 自动构建发布)**

```
https://raw.githubusercontent.com/Glassous/BetterHRBUST/dist/better-hrbust.user.js
```

浏览器打开该地址,油猴弹出安装页。脚本已内置 `@updateURL`/`@downloadURL`,
后续上游更新、CI 重新构建后**油猴会自动检查并提示更新**(版本号含构建号,
即使上游不 bump `package.json` 版本也能被识别)。

**无 CI 产物/自行构建时**,任选一种本地方式:

**方式 A · 本地服务器安装(推荐,免任何配置)**

```bash
cd web
npm run serve:userscript
```

浏览器打开它打印的地址(`http://127.0.0.1:8139/better-hrbust.user.js`),
油猴会弹出安装页,点安装。装完 Ctrl+C 关服务器即可,不影响已装脚本。

**方式 B · 粘贴(永远可用)**

油猴管理面板 → 「+」新建脚本 → 全选删掉模板 → 把 `.user.js` 文件全部内容
粘进去 → Ctrl+S 保存。

**方式 C · 拖拽文件**

`edge://extensions`(或 chrome://extensions)→ 油猴 → 详细信息 → 打开
「允许访问文件网址」→ 把 `.user.js` 拖进浏览器窗口,弹出安装页。

> 注意:油猴「实用工具 → 导入」是导入**备份压缩包**用的,不能装 .user.js 源文件。

## CI 自动发布

`.github/workflows/build-userscript.yml`:push 到 `main` 且改动涉及 `web/**`
时自动构建,并把 `better-hrbust.user.js` 发布到 `dist` 分支(force_orphan,
每次单提交,不污染历史)。构建时注入:

- `USERSCRIPT_VERSION_SUFFIX = <run_number>` → 版本形如 `1.0.0.42`,保证油猴可识别每次重建;
- `USERSCRIPT_DOWNLOAD_URL = <本仓库 dist 分支 raw 地址>` → 写入 `@updateURL`/`@downloadURL`。

## 发布到 Greasy Fork(可选)

Greasy Fork 支持从 URL 同步发布,与 CI 产物天然衔接:

1. 注册 [greasyfork.org](https://greasyfork.org/) 账号;
2. 「发布脚本」→ 填入 dist 分支 raw 地址作为代码来源,选择定期同步;
3. 版本号变化时 GF 自动拉取新版本。

注意两点:
- GF 要求可追溯未压缩源码——本脚本头部 `@homepageURL` 指向仓库,构建链在
  `web/` 下,满足要求;
- 本仓库尚未声明开源许可证,**发布前需先确定 `@license`**(作者决定),避免
 授权争议;建议在 GF 发布时同步补上。

## 使用说明

- **查看原版**:应用是只读客户端,选课等写操作仍需原版页面。点击浏览器
  Tampermonkey 图标 → 菜单「查看原版教务系统(选课等操作)」即可临时还原
  当前标签页的原版页面;再次点击菜单「启用 BetterHRBUST 界面」恢复。
  该开关用 `sessionStorage` 记忆,只对当前标签页生效,关掉标签页自动复位。
- **必须走 HTTP**:教务系统无 HTTPS。若浏览器强制 HTTPS-First 模式导致打不开,
  请在地址栏显式输入 `http://` 并关闭该站点的自动升级。
- **校外使用**:需先连校园 VPN;WebVPN(域名改写型)场景不适用本脚本的
  `@match` 规则,需另行适配。

## 已验证场景(本地桩服务器冒烟测试)

- 普通 JSP 页面接管:标题/DOM 替换、登录卡片渲染、验证码加载、会话探测请求发出;
- URP frameset 框架页(main.jsp 类页面)接管:frameset 自动替换为 body;
- 「查看原版」开关与恢复接管;
- 明细见 `scripts/smoke-server.mjs`(`node scripts/smoke-server.mjs` 可复现)。
