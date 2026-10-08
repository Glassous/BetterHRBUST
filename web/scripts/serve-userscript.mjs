/**
 * 一键安装服务器:把构建产物以 http 形式暴露,浏览器打开打印出的地址即可
 * 触发 Tampermonkey 的 .user.js 安装页(免开「允许访问文件网址」权限)。
 *
 * 用法:npm run serve:userscript
 * 然后访问 http://127.0.0.1:8139/better-hrbust.user.js
 */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const webRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const filePath = path.join(webRoot, 'dist-userscript', 'better-hrbust.user.js');

const server = createServer((req, res) => {
  if (req.url.split('?')[0].endsWith('.user.js')) {
    res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8' });
    res.end(readFileSync(filePath));
    return;
  }
  res.writeHead(302, { location: '/better-hrbust.user.js' });
  res.end();
});

server.listen(8139, '127.0.0.1', () => {
  console.log('在浏览器打开下面的地址,油猴会弹出安装页:');
  console.log('  http://127.0.0.1:8139/better-hrbust.user.js');
  console.log('(安装完成后 Ctrl+C 关闭本服务器即可,不影响已安装脚本)');
});
