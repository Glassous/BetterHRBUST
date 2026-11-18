# BetterHRBUST 自部署

把 BetterHRBUST 网页版部署到自己的服务器：**校内机器只负责穿透，公网服务器负责网页、反向代理与 HTTPS**。

> [!WARNING]
> 这不是一个可以对外运营的服务。部署完成后，访问者的学号、密码、验证码与成绩都会经过你的服务器。
> 只给自己和信任的同学使用：不要公开传播地址、不要做搜索引擎收录、不要收集或导出任何人的数据。
> 中国大陆的公网服务器对外提供 Web 服务需要 ICP 备案；没有备案请使用非大陆节点，或只在组网内访问。

## 架构

```
浏览器
  │ HTTPS (443)
  ▼
公网服务器
  ├── Caddy 或 1Panel 的 OpenResty
  ├── /                → 前端构建产物
  ├── /academic/*      → 127.0.0.1:18080（frp 隧道出口）
  └── /api/*           → 503（本方案不部署意见反馈后端）

frps（公网服务器，控制端口 7000）
  │  frp TLS + token；proxyBindAddr = 127.0.0.1，18080 不对外
  ▲
frpc（校内机器，只出站）
  ▼
jwzx.hrbust.edu.cn:80
```

| 位置 | 运行什么 | 不运行什么 |
| --- | --- | --- |
| 公网服务器 | `frps`、`Caddy` 或 `1Panel 的 OpenResty`、前端构建产物 | 不跑校内机器的任何服务 |
| 校内机器 | 只跑 `frpc` | 不跑网页、不跑反向代理 |

## 两条服务器路线（二选一）

| 路线 | 适用场景 | 特点 | 教程 |
| --- | --- | --- | --- |
| Caddy | 服务器没有 1Panel 网站，80/443 空闲 | 单二进制、自动 HTTPS、配置集中在一个文件 | [Caddy.md](Caddy.md) |
| 1Panel | 已安装 1Panel，80/443 已被 OpenResty 占用 | 面板管站点、证书与反代，配置在 1Panel 里完成 | [1Panel.md](1Panel.md) |

两条路线共用同一套 `frps`、证书脚本、`frpc` 与前端构建产物；区别只在于服务器上谁监听 80/443、谁负责 `/academic` 反代。

**不要同时安装 Caddy 与 1Panel 的 OpenResty**，两者会抢 80/443。Caddy 路线不使用 nginx；1Panel 路线使用其自带的 OpenResty，是该路线无法回避的实现方式。

## 目录结构

```
selfhost-frp/
├── README.md          # 本说明
├── Caddy.md           # 路线一：Caddy 服务器教程
├── 1Panel.md          # 路线二：1Panel 服务器教程
├── docs/
│   ├── client.md          # 校内机器 frpc
│   ├── tls-and-domain.md  # 域名、HTTPS、访问控制
│   └── troubleshooting.md # 故障排查
├── server/            # 服务器侧配置与脚本（两条路线共用）
│   ├── frps.toml.example
│   ├── Caddyfile.example
│   ├── install-frps.sh
│   ├── certs/gen-certs.sh
│   └── systemd/frps.service
└── client/            # 校内机器配置与脚本
    ├── frpc.toml.example
    ├── start.ps1 / stop.ps1 / start.cmd / stop.cmd
    └── systemd/frpc.service
```

`server/` 与 `client/` 由两条路线共用；`selfhost-frp/` 不放前端构建产物，前端仍从仓库 `web/` 构建。

## 前置条件

1. 一台有公网 IPv4 的 Linux 服务器（Debian / Ubuntu），可开放 80、443、7000。
2. 一个解析到该服务器的域名；证书签发需要 80 端口可访问。
3. 一台能正常访问 `http://jwzx.hrbust.edu.cn/academic/` 的校内机器。
4. 服务器安装 Git 与 Node.js 20+（Node 只用于构建前端，不参与运行）。
5. 服务器与校内机器使用**同一版本**的 frp（v0.52+，TOML 配置）。

## 已知限制

- **自定义教务地址不生效。** 本方案固定把 `/academic/*` 转发到 frp 隧道上游。设置页仍可填写自定义地址，但反向代理会剥离
  `x-betterhrbust-academic-base` 请求头，请求仍然落到隧道指向的教务站。
- **意见反馈不可用。** `/api/*` 固定返回 503；需要反馈功能请另行部署 `server/`，本方案不包含。
- **只支持一套隧道。** 一个 frpc 对应一个上游；要接第二个教务站需要再开一条隧道并改反向代理。
- **frps 原生安装。** 不要用 1Panel 应用商店的 frp，也不要把它放进容器：`proxyBindAddr = 127.0.0.1` 依赖宿主机回环网络。

## 安全与合规

- `FRP_TOKEN` 使用 `openssl rand -hex 32` 生成，服务器与校内机器保持一致；不要提交到仓库。
- `ca.key` 只留在服务器；校内机器只需要 `ca.crt`。
- `18080` 通过 `proxyBindAddr` 与防火墙双重限制，公网不可直连。
- 建议在反向代理上加一层访问控制：Caddy 的 Basic Auth 见 [docs/tls-and-domain.md](docs/tls-and-domain.md)；1Panel 可在站点设置里启用访问限制，或在其 OpenResty 配置中使用 `auth_basic`。
- 不记录请求体与 Cookie；反向代理默认访问日志不含这些内容。
- 学校教务是 HTTP 老系统，不要爬取、不要压测、不要多人同时全量刷新。

## 文档

| 文档 | 内容 |
| --- | --- |
| [Caddy.md](Caddy.md) | 路线一：frps、证书、Caddy、前端构建、防火墙 |
| [1Panel.md](1Panel.md) | 路线二：1Panel 网站、OpenResty 反代、证书 |
| [docs/client.md](docs/client.md) | 校内机器：frpc、启停、开机自启、WebVPN |
| [docs/tls-and-domain.md](docs/tls-and-domain.md) | 域名、HTTPS、访问控制、备案 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见故障与逐项排查命令 |
