# 启动校內机器的 frpc 隧道（Windows）。
# 用法：右键「使用 PowerShell 运行」，或双击 start.cmd。

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$exe = Join-Path $PSScriptRoot 'frpc.exe'
$config = Join-Path $PSScriptRoot 'frpc.toml'
$pidFile = Join-Path $PSScriptRoot 'frpc.pid'
$outLog = Join-Path $PSScriptRoot 'frpc.log'
$errLog = Join-Path $PSScriptRoot 'frpc.err.log'

if (-not (Test-Path -LiteralPath $exe)) {
    throw "缺少 frpc.exe：$exe。请下载与服务器相同版本的 frp，并把 frpc.exe 放到本目录。"
}
if (-not (Test-Path -LiteralPath $config)) {
    throw "缺少 frpc.toml：$config。请复制 frpc.toml.example 后填写服务器地址与 token。"
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'ca.crt'))) {
    throw "缺少 ca.crt。请从服务器的 selfhost-frp/server/certs/ 复制过来。"
}

# 已在运行则直接退出
if (Test-Path -LiteralPath $pidFile) {
    $raw = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    $oldPid = 0
    if ([int]::TryParse([string]$raw, [ref]$oldPid)) {
        $old = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
        if ($old -and $old.Path -eq $exe) {
            Write-Host "隧道已在运行（PID $oldPid）。"
            exit 0
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

Write-Host '校验 frpc.toml ...'
& $exe verify -c $config
if ($LASTEXITCODE -ne 0) {
    throw 'frpc 配置校验失败，请检查 frpc.toml。'
}

$process = Start-Process -FilePath $exe -ArgumentList @('-c', $config) `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog

Set-Content -LiteralPath $pidFile -Value $process.Id
Start-Sleep -Seconds 2

if ($process.HasExited) {
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw "frpc 已退出，请查看日志：$errLog"
}

Write-Host "隧道已启动（PID $($process.Id)）。"
Write-Host "日志：$outLog"
Write-Host "错误日志：$errLog"
