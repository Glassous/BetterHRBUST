# 使用 1Panel 部署服务器侧

1Panel 的「网站」功能基于 OpenResty（nginx）。它和 Caddy 路线是**二选一**：谁先占用 80/443，谁就负责对外服务。
本篇给出一条与 1Panel 共存的做法：**1Panel 管网站与证书，frps 仍按原方案原生安装**。

> 默认的 Caddy 路线不使用 nginx；1Panel 路线使用其自带的 OpenResty（nginx），这是该路线无法回避的实现方式。
> 如果不想引入 OpenResty，请走 [Caddy.md](Caddy.md)。

## 0. 先选路线

| 服务器现状 | 走哪条 |
| --- | --- |
| 已安装 1Panel，且 80/443 已被 OpenResty 占用（建过站） | **路线 A（本篇）**：1Panel 网站接管 80/443 |
| 没启用 1Panel 网站，80/443 空闲 | **路线 B**：[Caddy.md](Caddy.md) 的 Caddy 方案；1Panel 只用来管防火墙、终端、文件 |

检查当前端口：

```bash
sudo ss -lntp | grep -E ':(80|443)\b'
docker ps --format '{{.Names}}' | grep -i openresty
```

路线 A 不要安装 Caddy，否则会与 OpenResty 抢 80/443。

## 1. 安装并配置 frps

**不要用 1Panel 应用商店里的 frp。** 应用商店把 frp 跑在容器里，`proxyBindAddr = "127.0.0.1"`
会绑定到容器自身的回环，宿主机上的 OpenResty 反而访问不到。frps 继续用 systemd 原生安装。

在 1Panel 的「主机 → 终端」里执行：

```bash
# 准备仓库（首次部署）
sudo apt install -y git
sudo git clone https://github.com/Glassous/BetterHRBUST.git /srv/betterhrbust
# 已经克隆过则改用：cd /srv/betterhrbust && sudo git pull

cd /srv/betterhrbust/selfhost-frp/server
sudo bash install-frps.sh

cd certs
sudo bash gen-certs.sh
sudo mkdir -p /etc/frp/certs
sudo cp server.crt server.key /etc/frp/certs/
sudo chmod 600 /etc/frp/certs/server.key

# 生成 token 并写入 /etc/frp/frps.toml
openssl rand -hex 32
sudo nano /etc/frp/frps.toml
sudo frps verify -c /etc/frp/frps.toml
sudo systemctl enable --now frps
sudo systemctl status frps --no-pager
```

`frps.toml` 与证书细节见 [Caddy.md](Caddy.md) 的第 2、3 节。
校内机器的 frpc 仍然按 [client.md](docs/client.md) 部署，只需要把服务器地址和 token 填对。

## 2. 放行端口（1Panel 防火墙）

进入「主机 → 防火墙」：

- 放行 `80/tcp`、`443/tcp`、`7000/tcp`。
- **不要放行 `18080`。**
- 云厂商的安全组也要同步放行 80/443/7000。

如果 1Panel 的 OpenResty 改过 HTTP/HTTPS 端口（不是 80/443），按实际端口放行。

## 3. 构建前端并创建静态网站

### 3.1 构建

```bash
# Node.js 20（若未安装）
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

cd /srv/betterhrbust/web
sudo npm ci
sudo npm run build
```

产物在 `/srv/betterhrbust/web/dist`。

### 3.2 创建站点

1. 左侧「网站 → 网站」→「创建」→ 选择「静态网站」。
2. 分组随意；主域名填 `<DOMAIN>`；其他域名留空。
3. 提交。若尚未安装 OpenResty，1Panel 会提示安装，保持默认 80/443 即可。

1Panel 的默认站点目录是：

```
/opt/1panel/www/sites/<DOMAIN>/index
```

### 3.3 同步构建产物

```bash
sudo apt install -y rsync
sudo rsync -a --delete /srv/betterhrbust/web/dist/ /opt/1panel/www/sites/<DOMAIN>/index/
sudo chmod -R a+rX /opt/1panel/www/sites/<DOMAIN>/index
```

确认：

```bash
ls -l /opt/1panel/www/sites/<DOMAIN>/index/index.html
```

之后每次更新前端只需要重新构建 + 再跑一次 `rsync`，不需要重启 OpenResty。

## 4. 申请证书并开启 HTTPS

1. 左侧「网站 → 证书」→「申请证书」。
2. 选择 Let's Encrypt，填写 `<DOMAIN>`，验证方式选 HTTP（Cloudflare 先切 DNS only）。
3. 申请成功后，进入「网站 → <DOMAIN> → 配置 → HTTPS」：
   - 启用 HTTPS；
   - 选择刚申请的证书；
   - 开启「强制 HTTPS」。

证书续期由 1Panel 负责，不需要再装 certbot。

## 5. 配置 `/academic` 反向代理

打开「网站 → <DOMAIN> → 配置文件」，在 `server { ... }` 内、默认的 `location /` 之前加入：

```nginx
# 教务在线：frp 隧道出口（OpenResty 默认 host 网络，127.0.0.1 可直连宿主机）
location ^~ /academic/ {
    # 让 proxy_redirect 输出 /academic/... 相对路径，
    # 否则 nginx 会补成 http://<域名>/academic/...，在 HTTPS 下容易出错
    absolute_redirect off;

    proxy_pass http://127.0.0.1:18080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host jwzx.hrbust.edu.cn;
    proxy_set_header Referer http://jwzx.hrbust.edu.cn/academic/;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # 本方案固定走隧道，忽略前端的自定义教务地址提示头
    proxy_set_header x-betterhrbust-academic-base "";

    # 教务 302 的绝对地址收敛回本地相对路径
    proxy_redirect ~^https?://[^/]+(/academic(?:/.*)?)$ $1;

    # 若某个 URP 部署返回带 Domain 的 Set-Cookie，取消下面一行的注释
    # proxy_cookie_domain jwzx.hrbust.edu.cn $host;

    proxy_read_timeout 60s;
    proxy_send_timeout 60s;
}

# 本方案不部署意见反馈后端
location ^~ /api/ {
    default_type application/json;
    return 503 '{"code":503,"message":"feedback service is not deployed"}';
}
```

保存后 1Panel 会校验并重载 OpenResty；如果保存失败，按提示检查语法。

注意事项：

- `proxy_pass` **不要**写成 `http://127.0.0.1:18080/`，末尾斜杠会改写路径。
- `absolute_redirect off;` 只放在 `/academic/` 这个 location 里，避免影响站点其他重定向。
- 用 `^~` 前缀匹配，避免被站点自带的 regex location 抢走。
- 不要把整站 `/` 反代到隧道，否则前端静态资源也会被转发到教务站。
- 如果用「反向代理」界面创建，再回「配置文件」补 Host/Referer/`proxy_redirect`；以配置文件为准。

## 6. 确认 OpenResty 的网络模式

1Panel 的 OpenResty 默认使用 **host 网络**，所以 `127.0.0.1:18080` 能直连宿主机上的 frps。
如果曾经手动改成 bridge，上面的配置会返回 502。检查：

```bash
docker ps --format '{{.Names}}' | grep -i openresty
docker inspect <openresty容器名> --format '{{.HostConfig.NetworkMode}}'
```

- 输出 `host`：保持第 5 节的 `127.0.0.1:18080`。
- 输出 `bridge`：二选一
  1. 把 frps 的 `proxyBindAddr` 改成 `"0.0.0.0"`，并在 1Panel 防火墙与云安全组中**明确拒绝 18080**；
     然后把 `proxy_pass` 改成宿主机的 Docker 网关地址（如 `http://172.17.0.1:18080`）。
  2. 把 OpenResty 改回 host 网络（不熟悉 Docker 网络时不建议）。

## 7. 验证

```bash
# frps 正常、18080 只在本机
sudo systemctl status frps --no-pager
sudo ss -lntp | grep 18080

# 隧道是否把教务站带到本机
curl -I http://127.0.0.1:18080/academic/

# 站点与反代
curl -I https://<DOMAIN>/
curl -I https://<DOMAIN>/academic/
curl -sI https://<DOMAIN>/academic/student/currcourse/currcourse.jsdo | grep -i '^location'
curl -i https://<DOMAIN>/api/healthz
```

期望：

- `18080` 显示 `127.0.0.1:18080`；
- `/academic/` 返回 200 或 302，`Content-Type: text/html;charset=gbk`；
- `Location` 以 `/academic/` 开头；
- `/api/healthz` 返回 JSON 503，而不是 `index.html`。

OpenResty 配置与日志：

- 1Panel「网站 → <DOMAIN> → 日志」查看站点日志；
- 或在终端：

```bash
docker exec <openresty容器名> openresty -t
docker exec <openresty容器名> openresty -s reload
docker logs --tail 100 <openresty容器名>
```

若提示找不到 `openresty` 命令，先用 `docker exec <容器名> which openresty` 查到完整路径再执行。

## 8. 日常运维

| 操作 | 做法 |
| --- | --- |
| 更新前端 | `git pull` → `npm ci && npm run build` → `rsync` 到站点 `index` 目录 |
| 更新 frps | 重新执行 `install-frps.sh`，再 `systemctl restart frps`；frpc 同步升级 |
| 证书续期 | 1Panel 自动续期，在「网站 → 证书」查看到期时间 |
| 备份 | `/etc/frp`（含证书与 token）、`/opt/1panel/www/sites/<DOMAIN>`、git 仓库；1Panel 的计划任务可做定时打包 |
| 面板安全 | 修改默认端口、强密码、开启 2FA、限制面板来源 IP、面板自身启用 HTTPS |

## 9. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `/academic` 502 | frpc 不在线；或 OpenResty 是 bridge 网络 | 先 `curl http://127.0.0.1:18080/academic/`；再按第 6 节确认网络模式 |
| `/academic` 返回 `index.html` | `location ^~ /academic/` 没保存成功，或站点配置被覆盖 | 重新在「配置文件」保存，`docker exec ... openresty -t` 查看错误 |
| 登录时跳到 `http://jwzx...` | 缺少 `proxy_redirect` | 补第 5 节的 `proxy_redirect` |
| 登录后立刻掉登录 | 上游 Cookie 带 `Domain` | 取消 `proxy_cookie_domain jwzx.hrbust.edu.cn $host;` 的注释 |
| `/api` 返回 HTML | 缺少 `location ^~ /api/` | 补上 503 配置 |
| 更新前端后页面没变 | `rsync` 目标目录不对，或浏览器缓存 | 检查站点目录；强制刷新 |
| 80/443 被占用 | Caddy 与 OpenResty 同时运行 | `systemctl disable --now caddy`，只保留一个 |
| 面板打不开 | 1Panel 面板端口未放行 | 云安全组与「主机 → 防火墙」放行面板端口（默认 8090） |

其余与 1Panel 无关的故障（TLS、302、Cookie、frpc 日志）见 [troubleshooting.md](docs/troubleshooting.md)。
