/**
 * Windows 端发布产物整理（零 npm 依赖，仅用系统自带能力）：
 *
 *  1. 便携版：将构建好的 BetterHRBUST.exe（图形界面，前端资产已嵌入二进制）
 *     与 BetterHRBUSTCLI.exe（命令行）一起压缩为
 *     release/BetterHRBUST-<版本>-windows-portable.zip，解压即用的绿色免安装版；
 *     两个 exe 必须同目录，命令行才能找到主程序与同一份本地缓存。
 *  2. 安装版：将 NSIS 安装包（tauri 默认名 BetterHRBUST_<版本>_x64-setup.exe）
 *     复制并统一命名为 release/BetterHRBUST-<版本>-windows-setup.exe。
 *
 * 统一命名后，desktop-tauri/release/ 下的文件即为可直接上传 GitHub Release 的资源，
 * 与 README 快捷下载链接、Android 包名（BetterHRBUST-<版本>-android.apk）保持一致。
 */
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const version = JSON.parse(
  readFileSync(path.join(root, 'package.json'), 'utf8')
).version;

const exe = path.join(root, 'src-tauri', 'target', 'release', 'BetterHRBUST.exe');
if (!existsSync(exe)) {
  console.error(`未找到 ${exe}，请先执行 npm run pack 或 npm run dist`);
  process.exit(1);
}

// 命令行程序缺失时直接失败：便携版如果只有图形界面，用户按文档执行命令会全部落空
const cliExe = path.join(root, 'src-tauri', 'target', 'release', 'BetterHRBUSTCLI.exe');
if (!existsSync(cliExe)) {
  console.error(`未找到 ${cliExe}，请先执行 npm run build:cli（npm run dist 已包含该步骤）`);
  process.exit(1);
}

const releaseDir = path.join(root, 'release');
mkdirSync(releaseDir, { recursive: true });

// 1) 便携版：图形界面 + 命令行同目录压缩为 zip（PowerShell Compress-Archive）
const zip = path.join(releaseDir, `BetterHRBUST-${version}-windows-portable.zip`);
execFileSync(
  'powershell.exe',
  [
    '-NoProfile',
    '-Command',
    `Compress-Archive -Path "${exe}","${cliExe}" -DestinationPath "${zip}" -Force`
  ],
  { stdio: 'inherit' }
);
console.log(`便携版已生成: ${zip}`);

// 2) 安装版：NSIS 产物统一改名（产物名由 tauri 决定，这里只做改名，便于直接上传 Release）
const nsisDir = path.join(root, 'src-tauri', 'target', 'release', 'bundle', 'nsis');
// 必须按当前版本精确匹配：bundle 目录里可能残留历史版本的 setup.exe，
// endsWith('-setup.exe') 这类宽松匹配会把旧版本安装包误当本次产物复制走
const installer = existsSync(nsisDir)
  ? readdirSync(nsisDir).find((name) => name === `BetterHRBUST_${version}_x64-setup.exe`)
  : undefined;

if (installer) {
  const target = path.join(releaseDir, `BetterHRBUST-${version}-windows-setup.exe`);
  copyFileSync(path.join(nsisDir, installer), target);
  console.log(`安装版已生成: ${target}`);
} else {
  // --no-bundle 构建后单独执行本脚本时没有 NSIS 产物，跳过即可，不视为失败
  console.warn(`未找到 NSIS 安装包（${nsisDir}），已跳过安装版命名`);
}
