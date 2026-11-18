# 校内机器侧部署（frpc）

目标：让校内机器只做一件事——把 `jwzx.hrbust.edu.cn:80` 通过 frp 隧道送到服务器，由服务器上的 Caddy 或 1Panel OpenResty 对外提供网页。

本目录（`selfhost-frp/client/`）就是全部内容，不需要安装 Web 服务器或 Node。

## 前置条件

- 一台能正常打开 `http://jwzx.hrbust.edu.cn/academic/` 的机器（Windows 10/11 或 Linux）。
- 服务器已经完成 [Caddy.md](../Caddy.md) 或 [1Panel.md](../1Panel.md) 中的 frps 与证书配置。
- frpc 与服务器 frps **版本一致**。
- 服务器生成的 `ca.crt`。
- frps 使用的 token。

## 1. 准备 frpc

从 <https://github.com/fatedier/frp/releases> 下载与服务器相同版本的压缩包：

| 平台 | 文件 | 需要取出 |
| --- | --- | --- |
| Windows x64 | `frp_<版本>_windows_amd64.zip` | `frpc.exe` |
| Linux x64 | `frp_<版本>_linux_amd64.tar.gz` | `frpc` |

把 `frpc.exe`（或 `frpc`）放到本目录，与 `start.ps1` 同级。

若服务器是用 `install-frps.sh` 自动取的最新版，可以在服务器上执行 `frps -v` 查看版本号。

## 2. 放入 ca.crt

从服务器复制：

```bash
# 在本地/校内机器执行
scp <用户名>@<服务器IP>:/path/to/selfhost-frp/server/certs/ca.crt .
```

Windows 也可以用 WinSCP 把 `ca.crt` 拖进 `selfhost-frp/client/`。

`ca.key` 不要复制，它只属于服务器。

## 3. 写 frpc.toml

```powershell
Copy-Item .\frpc.toml.example .\frpc.toml
notepad .\frpc.toml
```

至少替换两处：

```toml
serverAddr = "服务器的公网 IP 或域名"
auth.token = "与服务器 /etc/frp/frps.toml 完全一致的 token"
```

其余保持默认：

- `transport.tls.trustedCaFile = "ca.crt"`：相对本目录。
- `transport.tls.serverName = "jwzx-frps"`：必须与服务器证书 SAN 一致。
- `localIP = "jwzx.hrbust.edu.cn"`、`localPort = 80`：要穿透的教务地址。
- `remotePort = 18080`：与服务器反向代理的约定一致。

## 4. 启动与停止（Windows）

```powershell
# 启动（校验配置 → 后台运行 → 写 PID）
.\start.cmd

# 查看日志
Get-Content .\frpc.log -Wait -Tail 20
Get-Content .\frpc.err.log -Tail 20

# 停止
.\stop.cmd
```

看到下面两行说明隧道建立成功：

```
login to server success
start proxy success
```

`start.ps1` 会先执行 `frpc verify -c frpc.toml`，配置写错时不会启动。
PID 记录在 `frpc.pid`；`stop.ps1` 只会停止本目录 `frpc.exe` 对应的进程。

## 5. 开机自启（Windows）

用任务计划程序，登录时自动运行：

1. 打开「任务计划程序」→「创建任务」。
2. 常规：名称 `BetterHRBUST frpc`，选择「只在用户登录时运行」。
3. 触发器：新建 →「登录时」。
4. 操作：新建 →「启动程序」→ 程序填 `C:\path\to\selfhost-frp\client\start.cmd`，
   「起始于」填 `C:\path\to\selfhost-frp\client`。
5. 条件：取消「只有在计算机使用交流电源时才启动此任务」（笔记本用电池时也要跑）。

也可以用命令行创建：

```powershell
# 在 PowerShell 中执行；单引号包裹整个 /TR 值，保留内部的双引号
schtasks /Create /TN "BetterHRBUST frpc" /SC ONLOGON /RL LIMITED /TR '"C:\path\to\selfhost-frp\client\start.cmd"'
```

删除：

```powershell
schtasks /Delete /TN "BetterHRBUST frpc" /F
```

## 6. 开机自启（Linux）

假设 frpc 放在 `/opt/betterhrbust-frpc`：

```bash
sudo mkdir -p /opt/betterhrbust-frpc
sudo cp -r selfhost-frp/client/. /opt/betterhrbust-frpc/
sudo install -m 0755 frpc /usr/local/bin/frpc

# 写入真实配置与证书
sudo cp /path/to/frpc.toml /opt/betterhrbust-frpc/frpc.toml
sudo cp /path/to/ca.crt /opt/betterhrbust-frpc/ca.crt

sudo cp /opt/betterhrbust-frpc/systemd/frpc.service /etc/systemd/system/frpc.service
sudo systemctl daemon-reload
sudo systemctl enable --now frpc
sudo systemctl status frpc --no-pager
sudo journalctl -u frpc -f
```

`frpc.service` 的 `WorkingDirectory` 是 `/opt/betterhrbust-frpc`，
因此 `trustedCaFile = "ca.crt"` 能正确解析。

## 7. 验证

在**服务器**上执行：

```bash
curl -I http://127.0.0.1:18080/academic/
# 期望 200 或 302，Content-Type: text/html;charset=gbk
```

在浏览器打开 `https://<DOMAIN>/`，登录后确认概览、课表、成绩可以读取。

## 8. 校外 / WebVPN

`localIP` 必须是**校内机器能直接访问**的地址：

- 在校园网内：保持 `jwzx.hrbust.edu.cn:80`。
- 在校外但能通过 WebVPN 访问：把 `localIP` / `localPort` 改成 WebVPN 提供的入口，
  并先用浏览器或 `curl` 确认该入口能正常打开教务页面。
- 如果 WebVPN 需要网页登录、Cookie 或动态验证，裸 TCP 隧道无法携带这些状态，
  这种情况请改用一台真正位于校园网内的机器。

## 9. 常见错误

| 日志 / 现象 | 原因 | 处理 |
| --- | --- | --- |
| `authentication failed` | token 不一致 | 对比两端 `auth.token` |
| `tls: failed to verify certificate` | `ca.crt` 不匹配或 `serverName` 不是 `jwzx-frps` | 重新从服务器复制 `ca.crt`，检查 SAN |
| `connect to server error ... unreachable` | 服务器 7000 未放行，或本机网络不通 | 检查安全组、`ufw`、`telnet <IP> 7000` |
| `start proxy success` 但服务器 18080 没有监听 | frps 未重启 / `allowPorts` 限制 | `sudo systemctl restart frps`，查看 `journalctl -u frps` |
| 服务器能连 18080 但页面报错 | 教务站不可达或返回了别的页面 | 在校内机器直接 `curl -I http://jwzx.hrbust.edu.cn/academic/` |
