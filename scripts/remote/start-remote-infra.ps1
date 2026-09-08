# =============================================================================
# Mnemoscape — workpc 端一键启动所有中间件
#
# 用法（在 workpc 本机 PowerShell 7+ 里执行，无需 SSH）：
#   PS D:\MnemoscapeInfra\..\Mnemoscape\scripts\remote> .\start-remote-infra.ps1
#   PS> .\start-remote-infra.ps1 -SkipNacosPush        # 启动容器但不推配置
#   PS> .\start-remote-infra.ps1 -OnlyWait             # 只等容器就绪，不重启
#
# 前置：
#   - Docker Desktop 已运行（workpc）
#   - 当前 PowerShell 进程能免密跑 docker（用户本机约定）
#   - 项目已 clone 到 D:\ 任意位置
#
# 该脚本不假设项目路径，会自动在 D:/ / C:/ 找 Mnemoscape 项目根目录，
# 把 scripts/remote/{rabbitmq,nacos}/* 拷贝到 D:\MnemoscapeInfra 挂载点。
# =============================================================================
[CmdletBinding()]
param(
    [switch]$OnlyWait,           # 只轮询就绪状态
    [switch]$SkipDockerUp,       # 跳过 docker compose up，只跑同步+推送
    [switch]$SkipNacosPush,      # 跳过 Nacos 配置推送
    [string]$ProjectRoot,        # 显式指定项目根（留空自动探测）
    [int]$WaitTimeoutSec = 180   # 等待所有容器 ready 的最长时间
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# ---------- 1. 定位项目根 & 中间件目录 ----------
function Find-ProjectRoot {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path $Explicit)) { return (Resolve-Path $Explicit).Path }

    $candidates = @(
        "D:\Mnemoscape",
        "C:\Mnemoscape",
        "D:\Projects\Mnemoscape",
        "D:\Study\ProjectTest\Mnemoscape",
        "$PSScriptRoot\..\.."
    )
    foreach ($p in $candidates) {
        $rp = Resolve-Path -LiteralPath $p -ErrorAction SilentlyContinue
        if ($rp -and (Test-Path (Join-Path $rp "scripts\remote\rabbitmq\definitions.json"))) {
            return $rp.Path
        }
    }
    throw "Cannot find project root with scripts/remote/rabbitmq/definitions.json. Use -ProjectRoot to specify."
}

$projRoot = Find-ProjectRoot -Explicit $ProjectRoot
$infraDir = "D:\MnemoscapeInfra"
$rabbitDir = "$infraDir\rabbitmq"
$scriptDir = "$projRoot\scripts\remote"

Write-Host "==> project root : $projRoot" -ForegroundColor Cyan
Write-Host "==> infra dir    : $infraDir" -ForegroundColor Cyan

# ---------- 2. 准备挂载点 ----------
if (-not (Test-Path $infraDir)) {
    New-Item -ItemType Directory -Path $infraDir -Force | Out-Null
    Write-Host "[mkdir] $infraDir"
}
foreach ($sub in @("rabbitmq", "nacos", "mysql", "redis", "neo4j", "milvus", "minio", "etcd") ) {
    $d = "$infraDir\$sub"
    if (-not (Test-Path $d)) {
        New-Item -ItemType Directory -Path $d -Force | Out-Null
    }
}

# 同步 RabbitMQ definitions.json + rabbitmq.conf
Copy-Item -LiteralPath "$scriptDir\rabbitmq\definitions.json" -Destination "$rabbitDir\definitions.json" -Force
Copy-Item -LiteralPath "$scriptDir\rabbitmq\rabbitmq.conf"  -Destination "$rabbitDir\rabbitmq.conf"     -Force
Write-Host "[copy] rabbitmq/{definitions.json,rabbitmq.conf} -> $rabbitDir"

# ---------- 3. 复制 docker-compose 文件到 infra 目录 ----------
$composeSrc = "$scriptDir\docker-compose.remote.yml"
$composeDst = "$infraDir\docker-compose.remote.yml"
if (-not (Test-Path $composeSrc)) { throw "Missing $composeSrc" }
Copy-Item -LiteralPath $composeSrc -Destination $composeDst -Force
Write-Host "[copy] docker-compose.remote.yml -> $composeDst"

# ---------- 4. docker compose up -d ----------
if (-not $OnlyWait -and -not $SkipDockerUp) {
    Write-Host ""
    Write-Host "==> docker compose up -d ..." -ForegroundColor Cyan
    Push-Location $infraDir
    try {
        docker compose -f docker-compose.remote.yml up -d
        if ($LASTEXITCODE -ne 0) { throw "docker compose up failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

# ---------- 5. 等待所有中间件就绪 ----------
function Test-Port {
    param([string]$Host, [int]$Port, [int]$TimeoutSec = 3)
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $tcp.BeginConnect($Host, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutSec * 1000)
        if ($ok) { $tcp.EndConnect($iar); return $true }
        return $false
    } catch { return $false } finally { $tcp.Close() }
}

$services = @(
    @{ Name = "MySQL";     Host = "localhost"; Port = 3306 },
    @{ Name = "Redis";     Host = "localhost"; Port = 6379 },
    @{ Name = "Nacos";     Host = "localhost"; Port = 8848 },
    @{ Name = "Nacos-gRPC";Host = "localhost"; Port = 9848 },
    @{ Name = "RabbitMQ";  Host = "localhost"; Port = 5672 },
    @{ Name = "MinIO";     Host = "localhost"; Port = 9000 },
    @{ Name = "Neo4j";     Host = "localhost"; Port = 7687 },
    @{ Name = "Milvus";    Host = "localhost"; Port = 19530 }
)
Write-Host ""
Write-Host "==> Waiting for services (timeout ${WaitTimeoutSec}s) ..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($WaitTimeoutSec)
while ((Get-Date) -lt $deadline) {
    $all = $true
    $status = @()
    foreach ($s in $services) {
        $ok = Test-Port -Host $s.Host -Port $s.Port -TimeoutSec 2
        $status += "{0,-12} :{1,5} {2}" -f $s.Name, $s.Port, $(if ($ok) { "✓" } else { "·" })
        if (-not $ok) { $all = $false }
    }
    Clear-Host
    Write-Host "Mnemoscape infra readiness check:" -ForegroundColor Cyan
    $status | ForEach-Object { Write-Host "  $_" }
    if ($all) {
        Write-Host ""
        Write-Host "All services reachable." -ForegroundColor Green
        break
    }
    Start-Sleep -Seconds 3
}

# 验证 Milvus 真正 ready（端口通不等于业务 ready）
Write-Host ""
Write-Host "==> Checking Milvus /healthz ..." -ForegroundColor Cyan
$milvusReady = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:9091/healthz" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $milvusReady = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if ($milvusReady) { Write-Host "  Milvus /healthz: OK" -ForegroundColor Green }
else { Write-Host "  Milvus /healthz: not ready (容器在起，等更久)" -ForegroundColor Yellow }

# 验证 Nacos 真正 ready
Write-Host ""
Write-Host "==> Checking Nacos readiness (gRPC port 9848) ..." -ForegroundColor Cyan
$nacosReady = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8848/nacos/v1/console/health/readiness" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200 -and $r.Content -match "UP") { $nacosReady = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if ($nacosReady) { Write-Host "  Nacos: OK (HTTP+gRPC ready)" -ForegroundColor Green }
else { Write-Host "  Nacos: NOT ready" -ForegroundColor Red }

# ---------- 6. 推送 Nacos 配置 ----------
if (-not $SkipNacosPush -and $nacosReady) {
    Write-Host ""
    Write-Host "==> Pushing Nacos config ..." -ForegroundColor Cyan
    & "$scriptDir\nacos\push-nacos-config.ps1" -NacosHost 100.66.166.46
}

# ---------- 7. 总结 ----------
Write-Host ""
Write-Host "===========================================================" -ForegroundColor Cyan
Write-Host "  Mnemoscape infra 启动完成" -ForegroundColor Green
Write-Host ""
Write-Host "  Tailscale 入口（API gateway 找服务用）:"
Write-Host "    Nacos    : http://100.66.166.46:8848/nacos        (无 auth)"
Write-Host "    RabbitMQ : http://100.66.166.46:15672             (guest/guest)"
Write-Host "    MinIO    : http://100.66.166.46:9001              (minioadmin/minioadmin123)"
Write-Host "    Neo4j    : http://100.66.166.46:7474              (neo4j/password123)"
Write-Host "    Milvus   : http://100.66.166.46:9091/healthz"
Write-Host ""
Write-Host "  本机（workpc）开发时，先导入环境变量再启服务："
Write-Host "    Get-Content backend\.env.workpc | ForEach-Object { ... }" -ForegroundColor DarkGray
Write-Host "===========================================================" -ForegroundColor Cyan
