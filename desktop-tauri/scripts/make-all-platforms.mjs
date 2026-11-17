/**
 * 桌面端「全平台整合包」（零 npm 依赖，与 make-portable.mjs / collect-linux.mjs 同一套约定）：
 *
 * 把 Windows 与 Linux 两条构建线各自的产物合并成一个带版本号的大压缩包，
 * 用户在 Release 页面只需下一次、解一次：
 *
 *   release/BetterHRBUST-<版本>-desktop-all.zip
 *   └── BetterHRBUST-<版本>/
 *       ├── 安装说明.txt
 *       ├── Windows/
 *       │   ├── BetterHRBUST-<版本>-windows-setup.exe    安装版（NSIS，含 CLI + TUI）
 *       │   └── portable/                                免安装版（解压即用，三个 exe）
 *       └── Linux/
 *           ├── BetterHRBUST-<版本>-linux-amd64.deb      Debian / Ubuntu
 *           └── BetterHRBUST-<版本>-linux-x86_64.rpm     RHEL / Fedora / openSUSE
 *
 * 输入为两条构建线各自整理好的 release 目录（--windows / --linux），
 * 缺少任一端产物即失败：整合包若只有一半平台，比不给更糟。
 *
 * 用法：
 *   node scripts/make-all-platforms.mjs --windows <dir> --linux <dir>
 *   省略参数时默认读本仓库 desktop-tauri/release（本地 `npm run dist` 后的产物）。
 */
import { execFileSync } from 'node:child_process';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const version = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')).version;

// ---------- 参数 ----------
const args = process.argv.slice(2);
function argOf(name) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : undefined;
}
const releaseDir = path.join(root, 'release');
const windowsDir = path.resolve(argOf('windows') ?? releaseDir);
const linuxDir = path.resolve(argOf('linux') ?? releaseDir);

const windowsSetup = `BetterHRBUST-${version}-windows-setup.exe`;
const windowsPortable = `BetterHRBUST-${version}-windows-portable.zip`;
const linuxDeb = `BetterHRBUST-${version}-linux-amd64.deb`;
const linuxRpm = `BetterHRBUST-${version}-linux-x86_64.rpm`;
const bundleName = `BetterHRBUST-${version}-desktop-all.zip`;

// 便携版压缩包内的三个 exe（顺序仅用于说明文字）
const portableExes = ['BetterHRBUST.exe', 'BetterHRBUSTCLI.exe', 'BetterHRBUSTTUI.exe'];

// ---------- 校验输入 ----------
// 两个输入目录可能相同（同为 release/），因此按文件名精确匹配而非目录区分；
// 版本号参与文件名，历史版本残留不会被误取。
const missing = [];
const pick = (dir, name) => {
  const p = path.join(dir, name);
  return existsSync(p) && statSync(p).size > 0 ? p : undefined;
};

const setupPath = pick(windowsDir, windowsSetup);
const portablePath = pick(windowsDir, windowsPortable);
const debPath = pick(linuxDir, linuxDeb);
const rpmPath = pick(linuxDir, linuxRpm);

if (!setupPath) missing.push(path.join(windowsDir, windowsSetup));
// 免安装版优先取 portable.zip（解压后放进整合包）；没有 zip 时允许目录里直接给三个 exe
const portableDirCandidate = path.join(windowsDir, 'portable');
const hasPortableDir =
  existsSync(portableDirCandidate) &&
  portableExes.every((name) => existsSync(path.join(portableDirCandidate, name)));
if (!portablePath && !hasPortableDir) missing.push(path.join(windowsDir, windowsPortable));
if (!debPath) missing.push(path.join(linuxDir, linuxDeb));
if (!rpmPath) missing.push(path.join(linuxDir, linuxRpm));

if (missing.length) {
  console.error('整合包缺少以下产物，已中止：');
  for (const p of missing) console.error(`  - ${p}`);
  console.error('请先完成 Windows（npm run dist）与 Linux（npm run dist:linux）构建。');
  process.exit(1);
}

// ---------- 归拢单文件产物 ----------
// release/ 是唯一的发布目录：把两条构建线的产物都摆进来（已是本目录时跳过），
// 这样 Release 上传与 artifact 上传都只需面对 release/ 一个目录。
mkdirSync(releaseDir, { recursive: true });
for (const src of [setupPath, portablePath, debPath, rpmPath].filter(Boolean)) {
  const dest = path.join(releaseDir, path.basename(src));
  if (path.resolve(src) !== path.resolve(dest)) copyFileSync(src, dest);
}

// ---------- 组装目录 ----------
const stageRoot = path.join(root, '.tmp-all-platforms');
rmSync(stageRoot, { recursive: true, force: true });
const stage = path.join(stageRoot, `BetterHRBUST-${version}`);
const stageWindows = path.join(stage, 'Windows');
const stagePortable = path.join(stageWindows, 'portable');
const stageLinux = path.join(stage, 'Linux');
for (const dir of [stageWindows, stagePortable, stageLinux]) mkdirSync(dir, { recursive: true });

copyFileSync(setupPath, path.join(stageWindows, windowsSetup));

// 免安装版：直接从 portable.zip 解出三个 exe，用户无需再解压第二层
if (hasPortableDir) {
  for (const name of portableExes) {
    copyFileSync(path.join(portableDirCandidate, name), path.join(stagePortable, name));
  }
} else if (process.platform === 'win32') {
  execFileSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-Command',
      `Expand-Archive -Path "${portablePath}" -DestinationPath "${stagePortable}" -Force`
    ],
    { stdio: 'inherit' }
  );
} else {
  execFileSync('unzip', ['-q', '-o', portablePath, '-d', stagePortable], { stdio: 'inherit' });
}
const extracted = readdirSync(stagePortable);
const lost = portableExes.filter((name) => !extracted.includes(name));
if (lost.length) {
  console.error(`免安装版解压后缺少 ${lost.join(' / ')}，请检查 ${windowsPortable}`);
  process.exit(1);
}

copyFileSync(debPath, path.join(stageLinux, linuxDeb));
copyFileSync(rpmPath, path.join(stageLinux, linuxRpm));

// ---------- 安装说明 ----------
writeFileSync(
  path.join(stage, '安装说明.txt'),
  [
    `BetterHRBUST ${version} 桌面端全平台整合包`,
    '',
    'Windows',
    `  安装版：Windows/${windowsSetup}`,
    '          双击安装（免管理员），安装时自动把安装目录写入用户 PATH，',
    '          安装后可在终端使用 BetterHRBUSTCLI 与 BetterHRBUSTTUI。',
    '  免安装：Windows/portable/ 整个目录复制到任意位置即可运行，',
    '          BetterHRBUST.exe 为图形界面，另两个是命令行与终端界面；',
    '          三个文件必须保持同目录，才能共用同一份本地数据。',
    '',
    'Linux',
    `  Debian / Ubuntu：sudo apt install ./Linux/${linuxDeb}`,
    `  RHEL / Fedora / openSUSE：sudo dnf install ./Linux/${linuxRpm}`,
    '  也可用 dpkg -i / rpm -i 安装；装完从应用菜单启动 BetterHRBUST。',
    '',
    '数据说明：图形界面、命令行与终端界面共用同一份本地数据，',
    '登录任一端后其余两端直接可用；卸载应用不会删除该数据。',
    ''
  ].join('\r\n'),
  'utf8'
);

// ---------- 压缩 ----------
// Windows 用系统自带 bsdtar（`-a` 按后缀选 zip），避免 PowerShell 5.1 的
// Compress-Archive 在 zip 内写入反斜杠路径导致 Linux 解压异常；
// Linux runner 用 zip（未安装时脚本会明确失败）。
const bundlePath = path.join(releaseDir, bundleName);
rmSync(bundlePath, { force: true });
if (process.platform === 'win32') {
  execFileSync('tar.exe', ['-a', '-c', '-f', bundlePath, '-C', stageRoot, `BetterHRBUST-${version}`], {
    stdio: 'inherit'
  });
} else {
  execFileSync('zip', ['-r', '-q', '-X', bundlePath, `BetterHRBUST-${version}`], {
    cwd: stageRoot,
    stdio: 'inherit'
  });
}

rmSync(stageRoot, { recursive: true, force: true });

if (!existsSync(bundlePath) || statSync(bundlePath).size === 0) {
  console.error(`整合包生成失败：${bundlePath} 不存在或为空`);
  process.exit(1);
}
const mb = (statSync(bundlePath).size / 1024 / 1024).toFixed(1);
console.log(`全平台整合包已生成: ${bundlePath}（${mb} MB，含 Windows 安装版 / 免安装版 + Linux deb / rpm）`);
