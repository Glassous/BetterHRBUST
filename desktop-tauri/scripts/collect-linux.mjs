/**
 * Linux 端发布产物整理（零 npm 依赖，与 make-portable.mjs 同一套约定）：
 *
 * tauri 在 Linux 目标下自动合并 tauri.linux.conf.json（deb + rpm），
 * 产物名由 bundler 决定：
 *   deb: BetterHRBUST_<版本>_amd64.deb
 *   rpm: BetterHRBUST-<版本>-1.x86_64.rpm
 * 这里按当前版本精确匹配后复制并统一命名为
 *   release/BetterHRBUST-<版本>-linux-amd64.deb
 *   release/BetterHRBUST-<版本>-linux-x86_64.rpm
 * 与 Windows 产物（-windows-*）命名风格一致，可直接挂到同一 GitHub Release。
 *
 * deb 与 rpm 在 Linux 管线里都是必需产物：任一缺失即失败退出，
 * 不做 make-portable 那种"NSIS 可跳过"的宽容处理。
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const version = JSON.parse(
  readFileSync(path.join(root, 'package.json'), 'utf8')
).version;

// 按版本精确匹配：bundle 目录里可能残留历史版本产物，
// 宽松匹配会把旧版本误当本次产物复制走
const artifacts = [
  {
    source: path.join(
      root, 'src-tauri', 'target', 'release', 'bundle', 'deb',
      `BetterHRBUST_${version}_amd64.deb`
    ),
    target: `BetterHRBUST-${version}-linux-amd64.deb`
  },
  {
    source: path.join(
      root, 'src-tauri', 'target', 'release', 'bundle', 'rpm',
      `BetterHRBUST-${version}-1.x86_64.rpm`
    ),
    target: `BetterHRBUST-${version}-linux-x86_64.rpm`
  }
];

const releaseDir = path.join(root, 'release');
mkdirSync(releaseDir, { recursive: true });

let missing = false;
for (const { source, target } of artifacts) {
  if (!existsSync(source) || statSync(source).size === 0) {
    console.error(`未找到 ${source}（或为空文件），请先执行 npm run dist:linux`);
    missing = true;
    continue;
  }
  const dest = path.join(releaseDir, target);
  copyFileSync(source, dest);
  console.log(`已生成: ${dest}`);
}

if (missing) {
  process.exit(1);
}
