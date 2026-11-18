#!/usr/bin/env bash
# 生成 frp 隧道用的自建 CA 与服务端证书。
#
# 在服务器上运行：
#   cd selfhost-frp/server/certs
#   bash gen-certs.sh
#
# 产物：
#   ca.crt      复制给校内机器（frpc 的 trustedCaFile）
#   ca.key      CA 私钥，只留在服务器，绝不能外传
#   server.crt  frps 使用
#   server.key  frps 使用
#
# 证书 SAN 固定为 jwzx-frps，必须与 frpc.toml 的 transport.tls.serverName 一致。

set -euo pipefail

cd "$(dirname "$0")"

if ! command -v openssl >/dev/null 2>&1; then
  echo "缺少 openssl，请先安装：apt install -y openssl" >&2
  exit 1
fi

for file in ca.key ca.crt server.key server.crt; do
  if [ -e "$file" ]; then
    echo "已存在 $file，为避免覆盖已退出。确认要重新生成请先手动删除这四个文件。" >&2
    exit 1
  fi
done

umask 077

echo "==> 生成 CA（10 年）"
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout ca.key -out ca.crt \
  -subj "/CN=BetterHRBUST FRP CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

echo "==> 生成服务端证书（CN/SAN = jwzx-frps，10 年）"
openssl req -newkey rsa:2048 -nodes \
  -keyout server.key -out server.csr \
  -subj "/CN=jwzx-frps"

ext_file="$(mktemp)"
trap 'rm -f "$ext_file"' EXIT
{
  printf "subjectAltName=DNS:jwzx-frps\n"
  printf "basicConstraints=critical,CA:FALSE\n"
  printf "keyUsage=critical,digitalSignature,keyEncipherment\n"
  printf "extendedKeyUsage=serverAuth\n"
} > "$ext_file"

openssl x509 -req -in server.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 3650 -extfile "$ext_file"

chmod 600 ca.key server.key

echo
echo "生成完成："
echo "  ca.crt      -> 复制到校内机器的 client/ 目录"
echo "  server.crt  -> /etc/frp/certs/server.crt"
echo "  server.key  -> /etc/frp/certs/server.key（权限保持 600）"
echo
echo "校验 SAN："
openssl x509 -in server.crt -noout -subject -ext subjectAltName
