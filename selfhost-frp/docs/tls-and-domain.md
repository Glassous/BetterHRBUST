# 域名、HTTPS 与访问控制

> 本篇以 Caddy 路线为例，证书由 Caddy 自动申请与续期。
> 使用 1Panel 时证书在面板里申请，见 [1Panel.md](../1Panel.md) 的「申请证书并开启 HTTPS」。

这套部署里有**两层完全不同的 TLS**，排障时不要混淆：

| 层 | 谁和谁之间 | 证书 | 作用 |
| --- | --- | --- | --- |
| frp 传输层 | frpc ↔ frps（7000 端口） | 自建 CA 签发的 `jwzx-frps` 证书 | 加密隧道内容，防止 token/教务数据在公网裸奔 |
| 公网 HTTPS | 浏览器 ↔ Caddy / 1Panel OpenResty（443 端口） | Let's Encrypt（Caddy 自动申请；1Panel 在证书页申请） | 让浏览器信任站点，避免密码明文与混合内容 |

`server/certs/gen-certs.sh` 生成的是第一层；第二层由 Caddy 自动完成，1Panel 路线则在面板的「网站 → 证书」里申请。

## 1. 域名解析

在 DNS 服务商添加 A 记录：

```
<DOMAIN>  A  <服务器公网 IPv4>
```

检查：

```bash
dig +short <DOMAIN>
```

使用 Cloudflare 时，首次签发证书建议先把橙云关掉（DNS only），确认 HTTPS 正常后再决定是否开启代理。

## 2. Caddy 自动 HTTPS

Caddyfile 的站点地址直接写域名即可：

```caddyfile
<DOMAIN> {
    # ...
}
```

Caddy 会：

1. 监听 80，完成 HTTP-01 挑战；
2. 申请并安装证书；
3. 把 HTTP 请求 308 跳转到 HTTPS；
4. 到期前自动续期。

日志：

```bash
sudo journalctl -u caddy -f
```

### 证书申请失败

按顺序排查：

```bash
# 域名是否指向本机
dig +short <DOMAIN>

# 80 端口是否放行（云安全组 + ufw）
sudo ufw status
curl -I http://<DOMAIN>/

# Caddy 是否在跑
sudo systemctl status caddy --no-pager
```

常见原因：

- 80 被云厂商安全组挡住。
- 用了 Cloudflare 橙云且开启了「Always Use HTTPS」，导致 ACME 挑战被重定向。
- 域名填错，或 DNS 还没生效。
- 同一域名已有一条 Caddy 配置在跑（重复站点）。

### 80 端口不可用怎么办

HTTP-01 必须用到 80。如果 80 被封，只能改用 DNS-01：

- 需要用 `xcaddy` 或 Caddy 官方下载页构建**带 DNS 插件**的 Caddy（官方 apt 包不含第三方 DNS 插件）。
- 在 Caddyfile 里配置 `tls { dns <provider> ... }`。

这属于进阶用法，本目录不展开；大多数 VPS 放行 80 即可。

## 3. 可选：Basic Auth

如果不想让拿到域名的人都能打开，可以在 Caddy 上加一层账号口令。它是整站生效的（包括 `/academic` 与静态资源），浏览器首次输入后会一直携带。

生成密码哈希：

```bash
caddy hash-password --plaintext '你的密码'
```

把输出的 `$2a$14$...` 整段复制到 Caddyfile 的站点块里，放在 `handle` 之前：

```caddyfile
<DOMAIN> {
    basic_auth {
        admin $2a$14$替换成上一步的输出
    }

    encode gzip

    handle /academic/* {
        # ...
    }
}
```

校验并重载：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

之后打开站点会先弹出浏览器原生登录框。账号口令通过 HTTPS 传输，不会明文暴露。

## 4. 合规

- 中国大陆的公网服务器对公网提供 80/443 Web 服务，需要 ICP 备案；未备案可能被服务商封端口。
- 不想备案时，可以选择非大陆节点，或只用 Tailscale / ZeroTier 等组网工具访问，不把 80/443 暴露到公网。
- 本项目只做教务信息查询的体验改善；不要用它做收费服务、公开运营或批量采集。

## 5. 端口清单

| 端口 | 协议 | 对谁开放 | 用途 |
| --- | --- | --- | --- |
| 22 | TCP | 管理 IP（建议） | SSH |
| 80 | TCP | 公网 | Caddy 证书挑战与 HTTP 跳转 |
| 443 | TCP | 公网 | 站点 HTTPS |
| 443 | UDP | 公网（可选） | HTTP/3 |
| 7000 | TCP | 校内机器出口 IP（建议） | frpc 控制连接 |
| 18080 | TCP | **仅 127.0.0.1** | frp 隧道出口，禁止对公网开放 |
