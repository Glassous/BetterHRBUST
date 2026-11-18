# 使用 Caddy 部署服务器侧

> 如果服务器使用 1Panel，请先看 [1Panel.md](1Panel.md)：1Panel 的网站功能基于 OpenResty，
> 会占用 80/443，不能与本篇的 Caddy 并存。

目标：在公网 Linux 服务器上装好 `frps` 与 `Caddy`，让 Caddy 托管前端、终结 HTTPS，并把 `/academic/*` 转发到 frp 隧道出口 `127.0.0.1:18080`。

以下命令以 Debian / Ubuntu、root 或 sudo 权限为前提，示例域名写作 `<DOMAIN>`。
仓库使用官方地址 `https://github.com/Glassous/BetterHRBUST`，fork 请自行替换。

## 0. 准备仓库与前置检查

### 0.1 准备仓库

```bash
# 安装 git（若未安装）
sudo apt install -y git

# 首次部署：克隆完整仓库（shared/ 必须一起，Web 构建会引用它）
sudo git clone https://github.com/Glassous/BetterHRBUST.git /srv/betterhrbust

# 已经克隆过：更新到最新
cd /srv/betterhrbust
sudo git pull
```

### 0.2 系统检查

```bash
# 系统架构（脚本支持 amd64 / arm64）
uname -m

# 80 / 443 / 7000 未被占用
sudo ss -lntp | grep -E ':(80|443|7000)\b' || echo '端口空闲'
```

域名 A 记录先指向服务器公网 IPv4。Caddy 首次启动时会自动申请证书，所以 80 端口必须能从公网访问。

## 1. 安装 frps

```bash
cd /srv/betterhrbust/selfhost-frp/server
sudo bash install-frps.sh
```

脚本会：

- 从 GitHub Releases 下载最新稳定版 frp 的 `frps`，安装到 `/usr/local/bin/frps`。
- 创建 `/etc/frp` 与 `/etc/frp/certs`。
- 若 `/etc/frp/frps.toml` 不存在，从 `frps.toml.example` 复制一份（占位符仍需手工替换）。
- 安装 `/etc/systemd/system/frps.service` 并执行 `systemctl daemon-reload`。

指定版本（服务器与校内机器必须一致；下面的 0.61.1 只是示例）：

```bash
sudo FRP_VERSION=0.61.1 bash install-frps.sh
```

若服务器访问 GitHub 很慢，可以先在本地下载 `frp_<版本>_linux_amd64.tar.gz`，上传到服务器后手动解压：

```bash
tar -xzf frp_<版本>_linux_amd64.tar.gz
sudo install -m 0755 frp_<版本>_linux_amd64/frps /usr/local/bin/frps
```

## 2. 生成 frp 隧道证书

```bash
cd /srv/betterhrbust/selfhost-frp/server/certs
sudo bash gen-certs.sh

sudo cp server.crt server.key /etc/frp/certs/
sudo chmod 600 /etc/frp/certs/server.key
```

产物说明：

| 文件 | 用途 |
| --- | --- |
| `ca.crt` | 复制给校内机器，作为 frpc 的 `trustedCaFile` |
| `ca.key` | 只留在服务器，绝不能外传 |
| `server.crt` / `server.key` | frps 的 TLS 证书，SAN 固定为 `jwzx-frps` |

校验证书 SAN：

```bash
openssl x509 -in server.crt -noout -subject -ext subjectAltName
# 期望包含 DNS:jwzx-frps
```

## 3. 配置并启动 frps

```bash
# 生成 token，两端的 frps.toml / frpc.toml 必须一致
openssl rand -hex 32

sudo nano /etc/frp/frps.toml
sudo frps verify -c /etc/frp/frps.toml
sudo systemctl enable --now frps
sudo systemctl status frps --no-pager
```

关键配置解释：

- `bindPort = 7000`：frpc 连接的控制端口。
- `proxyBindAddr = "127.0.0.1"`：让 18080 只绑定回环，公网无法直连。
- `transport.tls.force = true`：只接受 TLS 连接，证书来自第 2 步。
- `allowPorts = [{ single = 18080 }]`：只允许客户端申请这一个端口。

校内机器的 frpc 按 [client.md](docs/client.md) 部署。

## 4. 防火墙

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp      # HTTP/3，可选
sudo ufw allow 7000/tcp
sudo ufw enable
sudo ufw status
```

**不要放行 18080。** frps 自己会把它绑到 `127.0.0.1`，防火墙再兜一层。

云厂商的安全组同样只放行 22/80/443/7000。

## 5. 安装 Caddy

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Caddy 以 `caddy` 用户运行，配置在 `/etc/caddy/Caddyfile`，服务名 `caddy`。

## 6. 构建前端

服务器只需要 Node 做构建，运行期由 Caddy 托管静态文件。

```bash
# Node.js 20（若已安装可跳过）
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

cd /srv/betterhrbust/web
sudo npm ci
sudo npm run build
```

构建产物在 `/srv/betterhrbust/web/dist`。

更新版本：

```bash
cd /srv/betterhrbust
sudo git pull
cd web
sudo npm ci
sudo npm run build
```

## 7. 配置 Caddy

```bash
sudo cp /srv/betterhrbust/selfhost-frp/server/Caddyfile.example /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile
# 替换 <DOMAIN> 与 <ACME_EMAIL>
```

让 `caddy` 用户能读到构建产物：

```bash
sudo chmod o+x /srv/betterhrbust /srv/betterhrbust/web
sudo chmod -R a+rX /srv/betterhrbust/web/dist
```

校验并重载：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
sudo systemctl status caddy --no-pager
```

首次访问 `https://<DOMAIN>/` 时 Caddy 会自动申请证书；日志：

```bash
sudo journalctl -u caddy -f
```

## 8. 验证服务器侧

此时如果校内机器的 frpc 还没启动，`/academic` 会返回 502，这是正常现象。先验证静态站：

```bash
curl -I https://<DOMAIN>/
curl -I http://<DOMAIN>/        # 期望 308 跳转到 https
curl -i https://<DOMAIN>/api/healthz   # 期望 JSON 503
```

frpc 启动后再验证隧道：

```bash
sudo ss -lntp | grep 18080
# 期望 127.0.0.1:18080，而不是 0.0.0.0:18080

curl -I http://127.0.0.1:18080/academic/
# 期望 200 或 302，Content-Type: text/html;charset=gbk

curl -sI https://<DOMAIN>/academic/student/currcourse/currcourse.jsdo | grep -i '^location'
# 期望 Location 以 /academic/ 开头
```

## 附：DNS 就绪前先做本机 HTTP 验证

如果域名还没解析过来，可以临时把 Caddyfile 的站点地址从 `<DOMAIN>` 改成 `:8080`：

```caddyfile
:8080 {
    # handle 内容保持不变
}
```

然后：

```bash
sudo systemctl reload caddy
curl -I http://127.0.0.1:8080/
curl -I http://127.0.0.1:8080/academic/
```

验证完记得改回 `<DOMAIN>` 并再次 reload，否则不会签发 HTTPS 证书。

## 附：升级 frps

```bash
cd selfhost-frp/server
sudo FRP_VERSION=<新版本> bash install-frps.sh
sudo systemctl restart frps
```

`/etc/frp/frps.toml` 与证书不会被覆盖。校内机器的 frpc 要同步升级到相同版本。
