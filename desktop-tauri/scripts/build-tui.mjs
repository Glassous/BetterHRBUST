/**
 * 把 Go 写的终端界面（../tui）编译成 Windows 二进制，供桌面端一起分发。
 *
 * 产出：desktop-tauri/binaries/BetterHRBUSTTUI.exe
 *   → tauri 打包时由 tauri.windows.conf.json 的 bundle.resources 复制到安装目录
 *   → 与 BetterHRBUST.exe / BetterHRBUSTCLI.exe 同目录
 *   → 安装器（installer-hooks.nsh）把安装目录写入用户 PATH，因此
 *     `BetterHRBUSTTUI` 无需任何额外 PATH 逻辑即可在终端里直接调用。
 *
 * 版本号取自 desktop-tauri/package.json，与安装包严格同号；
 * 不用 tui/Makefile 的 git describe，避免出现"安装包 1.6.0、TUI 1.3.0-5-gxxxx"。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const version = JSON.parse(
  readFileSync(path.join(root, 'package.json'), 'utf8')
).version;

// TUI 只打进 Windows 安装包（tauri.windows.conf.json 仅对 Windows 目标生效），
// 在 Linux / macOS 上跑 dist 时跳过，避免无意义地交叉编译一个用不到的 exe。
if (process.platform !== 'win32') {
  console.log(`当前平台（${process.platform}）不需要 Windows TUI 二进制，跳过。`);
  process.exit(0);
}

const tuiDir = path.resolve(root, '..', 'tui');
if (!existsSync(path.join(tuiDir, 'go.mod'))) {
  console.error(`未找到 Go 模块 ${path.join(tuiDir, 'go.mod')}，无法构建终端界面`);
  process.exit(1);
}

const outDir = path.join(root, 'binaries');
mkdirSync(outDir, { recursive: true });
const out = path.join(outDir, 'BetterHRBUSTTUI.exe');

try {
  execFileSync(
    'go',
    [
      'build',
      '-trimpath',
      '-ldflags',
      // -s -w 去符号：与 Makefile 一致；-X 让 --version 报安装包版本
      `-s -w -X github.com/Glassous/BetterHRBUST/tui/internal/version.Version=${version}`,
      '-o',
      out,
      './cmd/betterhrbust-tui'
    ],
    {
      cwd: tuiDir,
      stdio: 'inherit',
      // 纯 Go 静态二进制，交叉编译不需要 cgo
      env: { ...process.env, CGO_ENABLED: '0', GOOS: 'windows', GOARCH: 'amd64' }
    }
  );
} catch (error) {
  if (error.code === 'ENOENT') {
    console.error('未找到 go 命令，请安装 Go（≥ 1.26）后重试，或检查 PATH。');
  }
  process.exit(1);
}

if (!existsSync(out) || statSync(out).size === 0) {
  console.error(`构建失败：${out} 不存在或为空`);
  process.exit(1);
}
console.log(
  `终端界面已生成: ${out}（版本 ${version}，${Math.round(statSync(out).size / 1024)} KB）`
);
