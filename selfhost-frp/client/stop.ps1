# 停止由 start.ps1 启动的 frpc 隧道（Windows）。
# 只会停止本目录 frpc.exe 对应的进程，不会误杀其他同名进程。

$ErrorActionPreference = 'Stop'

$exe = Join-Path $PSScriptRoot 'frpc.exe'
$pidFile = Join-Path $PSScriptRoot 'frpc.pid'

if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host '没有受管理的隧道进程。'
    exit 0
}

$raw = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
$pidValue = 0
if (-not [int]::TryParse([string]$raw, [ref]$pidValue)) {
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Write-Host 'PID 文件无效，已清理。'
    exit 0
}

$process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
if ($process -and $process.Path -eq $exe) {
    Stop-Process -Id $pidValue
    Write-Host "隧道已停止（PID $pidValue）。"
} else {
    Write-Host '进程不存在，或不是本目录的 frpc.exe。'
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
