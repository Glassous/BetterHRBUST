#!/usr/bin/env bash
# 在 Debian / Ubuntu 服务器上安装 frps。
#
#   cd selfhost-frp/server
#   sudo bash install-frps.sh
#
# 可选环境变量：
#   FRP_VERSION=0.61.1   指定版本；默认取 GitHub 最新 Release
#
# 脚本只做四件事：下载 frps、写入 /usr/local/bin、创建 /etc/frp、安装 systemd 单元。
# 证书与 frps.toml 中的 token 仍需按 Caddy.md / 1Panel.md 手工配置。

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 sudo 运行：sudo bash install-frps.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for bin in curl tar; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "缺少 $bin，请先安装：apt install -y $bin" >&2
    exit 1
  fi
done

case "$(uname -m)" in
  x86_64 | amd64) ARCH="amd64" ;;
  aarch64 | arm64) ARCH="arm64" ;;
  *)
    echo "不支持的架构：$(uname -m)" >&2
    exit 1
    ;;
esac

if [ -z "${FRP_VERSION:-}" ]; then
  echo "==> 查询 frp 最新版本"
  FRP_VERSION="$(
    curl -fsSL https://api.github.com/repos/fatedier/frp/releases/latest \
      | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\([^"]*\)".*/\1/p'
  )"
fi

if [ -z "${FRP_VERSION:-}" ]; then
  echo "无法获取 frp 版本，请手动指定：sudo FRP_VERSION=0.61.1 bash install-frps.sh" >&2
  exit 1
fi

TARBALL="frp_${FRP_VERSION}_linux_${ARCH}.tar.gz"
URL="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${TARBALL}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> 下载 frp v${FRP_VERSION}（linux/${ARCH}）"
curl -fL --retry 3 --connect-timeout 15 -o "$TMP_DIR/$TARBALL" "$URL"

echo "==> 解压并安装 frps"
tar -xzf "$TMP_DIR/$TARBALL" -C "$TMP_DIR"
install -m 0755 "$TMP_DIR/frp_${FRP_VERSION}_linux_${ARCH}/frps" /usr/local/bin/frps

install -d -m 0755 /etc/frp /etc/frp/certs

if [ -f "$SCRIPT_DIR/frps.toml.example" ]; then
  if [ -f /etc/frp/frps.toml ]; then
    echo "==> /etc/frp/frps.toml 已存在，未覆盖"
  else
    install -m 0640 "$SCRIPT_DIR/frps.toml.example" /etc/frp/frps.toml
    echo "==> 已创建 /etc/frp/frps.toml，请替换其中的 <FRP_TOKEN>"
  fi
fi

if [ -f "$SCRIPT_DIR/systemd/frps.service" ]; then
  install -m 0644 "$SCRIPT_DIR/systemd/frps.service" /etc/systemd/system/frps.service
  systemctl daemon-reload
  echo "==> 已安装 systemd 单元 /etc/systemd/system/frps.service"
fi

cat <<'EOF'

安装完成。接下来手工完成：

  1. 生成证书并复制到 /etc/frp/certs/
       cd selfhost-frp/server/certs && sudo bash gen-certs.sh
       sudo cp server.crt server.key /etc/frp/certs/
       sudo chmod 600 /etc/frp/certs/server.key

  2. 编辑 /etc/frp/frps.toml，把 <FRP_TOKEN> 换成 openssl rand -hex 32 的输出

  3. 校验并启动
       sudo frps verify -c /etc/frp/frps.toml
       sudo systemctl enable --now frps
       sudo systemctl status frps

具体步骤见 Caddy.md 或 1Panel.md。
EOF
