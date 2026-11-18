# 故障排查

先按链路方向排查，不要一上来就改配置：

```
校内机器 frpc → 公网 frps:7000 → 服务器 127.0.0.1:18080 → Caddy / OpenResty `/academic` → 浏览器
```

## 0. 一次性收集现场信息

```bash
sudo systemctl status frps caddy --no-pager
sudo journalctl -u frps -n 100 --no-pager
sudo journalctl -u caddy -n 100 --no-pager
sudo ss -lntp | grep -E ':(7000|18080|80|443)\b'
curl -v http://127.0.0.1:18080/academic/
```

校内机器（Windows）：

```powershell
Get-Content .\frpc.log -Tail 50
Get-Content .\frpc.err.log -Tail 50
```

## 1. 常见现象

| 现象 | 最可能的原因 | 处理 |
| --- | --- | --- |
| `curl http://127.0.0.1:18080/academic/` 连接被拒绝 | frpc 不在线，或 frps 没重启 | 校内机器 `start.cmd`；`sudo systemctl restart frps` |
| 网站能打开，但页面报「教务请求未到达目标站点……当前站点也没有 /academic 转发」 | 反向代理把 `/academic` 当成静态路径，返回了 `index.html` | 确认 Caddy 的 `handle /academic/*` 或 OpenResty 的 `location ^~ /academic/` 已生效；`curl -i https://<域名>/academic/` 看响应是不是 HTML 首页 |
| 打开网站直接 502 | 反向代理命中了 `/academic`，但隧道出口没人监听 | 按上一条检查 frpc 与 18080 |
| 登录时提示网络不可用 / 一直转圈 | 302 的 `Location` 还是 `http://jwzx...`，被浏览器按混合内容拦截 | Caddy 检查 `header_down Location`；OpenResty 检查 `proxy_redirect` 与 `absolute_redirect off` |
| 登录成功但刷新后掉登录 | Cookie 被加上了 `Domain`，或反向代理没有透传 `Set-Cookie` | `curl -sI https://<域名>/academic/getCaptcha.do` 看 `Set-Cookie` |
| 验证码空白 | 请求没走 `/academic`，或返回的是 HTML | `curl -I https://<域名>/academic/getCaptcha.do`，应为 `image/jpeg` |
| 教务页面返回 403 / 登录页结构不对 | `Host` / `Referer` 没改写 | 确认已改写 Host / Referer（Caddy 的 `header_up`，OpenResty 的 `proxy_set_header`） |
| 公网能直接访问 18080 | `proxyBindAddr` 未生效 | `ss -lntp \| grep 18080` 必须是 `127.0.0.1:18080`；改完配置重启 frps |
| 证书申请失败 | 80 被挡、DNS 未生效、Cloudflare 代理 | 见 [tls-and-domain.md](tls-and-domain.md) |
| 设置里改自定义教务地址没反应 | 本方案固定走隧道 | 预期行为；反向代理会剥离 `x-betterhrbust-academic-base`，见 README「已知限制」 |
| 意见反馈报 503 | 本方案未部署反馈后端 | 预期行为；需要反馈请另外部署 `server/` |
| 换域名后要重新登录 | localStorage 按 origin 隔离 | 使用固定域名；换域名后重新登录同步一次 |

## 2. 登录后掉登录

官方教务返回的 Cookie 形如：

```
Set-Cookie: JSESSIONID=...; Path=/academic; HttpOnly
```

没有 `Domain`，浏览器会自动归属到当前站点，这是正常的。如果某个 URP 部署返回了带 `Domain` 的 Cookie，
浏览器会拒绝写入，表现为「登录成功但立刻回到登录页」。按路线在反向代理里追加一行：

Caddy（`Caddyfile` 的 `/academic` 块）：

```caddyfile
header_down Set-Cookie "(?i);[[:space:]]*Domain=[^;]*" ""
```

1Panel（站点「配置文件」的 `location ^~ /academic/`）：

```nginx
proxy_cookie_domain jwzx.hrbust.edu.cn $host;
```

Caddy 重载：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

1Panel 在「配置文件」里保存即可（或在容器内执行 `openresty -s reload`）。

清掉浏览器站点数据后再登录一次。

## 3. 隧道在线但教务不可达

在**校内机器**上直接验证源站：

```powershell
curl.exe -I http://jwzx.hrbust.edu.cn/academic/
```

如果这里就不通，问题不在 frp：

- 机器不在校园网，且没有连 WebVPN。
- WebVPN 需要网页登录，裸 TCP 隧道带不了登录态。
- 校内 DNS / 防火墙限制了该机器。

## 4. 静态资源 403 / 404

先确认构建产物存在：

```bash
ls -l /srv/betterhrbust/web/dist/index.html
```

Caddy 路线：Caddy 以 `caddy` 用户读取 `/srv/betterhrbust/web/dist`：

```bash
sudo chmod o+x /srv/betterhrbust /srv/betterhrbust/web
sudo chmod -R a+rX /srv/betterhrbust/web/dist
sudo -u caddy test -r /srv/betterhrbust/web/dist/index.html && echo OK
```

1Panel 路线：静态站点读的是 `/opt/1panel/www/sites/<域名>/index`：

```bash
ls -l /opt/1panel/www/sites/<域名>/index/index.html
sudo chmod -R a+rX /opt/1panel/www/sites/<域名>/index
```

没同步或同步到了错误目录时，页面会 404；重新执行 [1Panel.md](../1Panel.md) 第 3.3 节的 `rsync`。

## 5. frpc 日志关键字

| 关键字 | 含义 |
| --- | --- |
| `login to server success` | 已连上 frps |
| `start proxy success` | 18080 隧道已建立 |
| `authentication failed` | token 不一致 |
| `tls: failed to verify certificate` | `ca.crt` 或 `serverName` 不对 |
| `connect to server error ... unreachable` | 7000 被防火墙/安全组挡住，或网络不通 |
| `work connection pool is full` | 并发连接超过 frpc 连接池；多标签页同时刷新时偶发，持续出现可加大 `transport.poolCount` |

## 6. 重启顺序

改完配置后按这个顺序重启，避免反向代理先于隧道：

```bash
# 服务器
sudo systemctl restart frps

# Caddy 路线
sudo systemctl reload caddy

# 1Panel 路线：在站点「配置文件」保存，或
# docker exec <openresty容器名> openresty -s reload

# 校内机器
.\stop.cmd
.\start.cmd
```

最后再跑一遍 [Caddy.md](../Caddy.md) 或 [1Panel.md](../1Panel.md) 里的验证清单。
