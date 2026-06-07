# 启动单个 Mnemoscape 微服务（detach 模式，加载 .env.workpc 后台 java -jar）
# 用法：.\scripts\Start-Service.ps1 -ServiceName "auth-service" -Port 8081

param(
    [Parameter(Mandatory=$true)][string]$ServiceName,
    [int]$Port = 0,
    [int]$XmxMb = 768
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$envFile = Join-Path $root "backend\.env.workpc"
$jar = Join-Path $root "backend\$ServiceName\target\$ServiceName-1.0.0-SNAPSHOT.jar"
$logDir = Join-Path $root "backend\logs"
$logFile = Join-Path $logDir "$ServiceName.log"

# 1) 加载 .env.workpc
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
}

# 2) 校验 jar
if (-not (Test-Path $jar)) {
    Write-Error "[$ServiceName] jar 不存在：$jar — 先跑 mvn install"
    exit 1
}

# 3) 删除旧 log（避免 file lock）
if (Test-Path $logFile) { Remove-Item $logFile -Force }

# 4) 拼出 env 块
$envBlock = Get-Content $envFile | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
    $name = $Matches[1].Trim()
    $val = $Matches[2].Trim()
    "set `"$name=$val`""
} | Out-String

$portInfo = if ($Port -gt 0) { " (port $Port)" } else { "" }
Write-Output "[$ServiceName] starting$portInfo → $logFile"

# 5) cmd.exe 启动（detach 进程组），env 通过 set 注入
#    注意：工位电脑 9848 被 WSL2 占用，Nacos gRPC 整体迁到 19848。
#    通过 -Dnacos.server.grpc.port 强制客户端使用 19848（默认仍为 port+1000）
$cmd = @"
@echo off
cd /d "$root\backend"
$envBlock
java -Xmx${XmxMb}m -XX:+UseG1GC -Dnacos.server.grpc.port=19848 -jar "$jar" > "$logFile" 2>&1
"@
$cmdFile = Join-Path $logDir "$ServiceName.cmd"
Set-Content -Path $cmdFile -Value $cmd -Encoding ASCII

Start-Process -FilePath "$cmdFile" -WindowStyle Hidden
Write-Output "[$ServiceName] detached, log → $logFile"
