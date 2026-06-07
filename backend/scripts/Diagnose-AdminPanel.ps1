# Diagnose-AdminPanel.ps1
#
# Admin 面板报错（v6/v7 接力诊断脚本）。按 v6.4.1 / v6.4.2 三层探测顺序，逐层排除：
#
#   1. 中间件 banner（MySQL / Redis / Nacos）—— Tailscale 路由通但 docker 没起的常见坑
#   2. Gateway 健康（/actuator/health）—— gateway 自身在不在
#   3. 下游服务直连（绕过 gateway）—— auth-service / memory-service / resonance-service
#   4. 走 gateway 的 admin 端点真实响应
#
# 使用：
#   $env:ADMIN_TOKEN = '<从浏览器 localStorage.token 复制>'
#   .\scripts\Diagnose-AdminPanel.ps1
#
# 不传 token 时跳过第 4 步，只做基础设施 + gateway 健康检查。

[CmdletBinding()]
param(
    [string]$WorkpcHost = '100.66.166.46',
    [string]$GatewayBase = 'http://localhost:8080',
    [string]$AuthBase = 'http://localhost:8081',
    [string]$MemoryBase = 'http://localhost:8082',
    [string]$ResonanceBase = 'http://localhost:8085',
    [string]$Token = $env:ADMIN_TOKEN
)

$ErrorActionPreference = 'Continue'

function Write-Section($title) {
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkGray
    Write-Host (' ' + $title) -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkGray
}

function Probe-TcpBanner {
    param([string]$Server, [int]$Port, [int]$TimeoutMs = 2500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($Server, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            $client.Close()
            return [PSCustomObject]@{ Status = 'TIMEOUT'; Bytes = 0; Detail = "$Server`:$Port no SYN-ACK in ${TimeoutMs}ms" }
        }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 1500
        $buf = New-Object byte[] 64
        try {
            $n = $stream.Read($buf, 0, 64)
            return [PSCustomObject]@{
                Status = 'BANNER'; Bytes = $n
                Detail = "$Server`:$Port banner=${n}B"
            }
        } catch {
            return [PSCustomObject]@{
                Status = 'CONNECTED_NO_BANNER'; Bytes = 0
                Detail = "$Server`:$Port TCP up, app silent (likely down)"
            }
        }
    } catch {
        return [PSCustomObject]@{ Status = 'REFUSED'; Bytes = 0; Detail = $_.Exception.Message }
    } finally {
        try { $client.Close() } catch { }
    }
}

function Get-Json {
    param([string]$Url, [hashtable]$Headers = @{}, [int]$TimeoutSec = 8)
    try {
        $resp = Invoke-WebRequest -Uri $Url -Headers $Headers -TimeoutSec $TimeoutSec -SkipHttpErrorCheck -UseBasicParsing
        return [PSCustomObject]@{
            Ok = ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300)
            Status = $resp.StatusCode
            Body = $resp.Content
        }
    } catch {
        return [PSCustomObject]@{
            Ok = $false; Status = 'ERR'; Body = $_.Exception.Message
        }
    }
}

# -- Step 1: middleware banners --
Write-Section '1. 中间件 banner（Tailscale 应用层是否真起来）'
$middleware = @(
    @{ Name = 'MySQL';     Port = 3306;  Expected = 'banner > 0 (MySQL handshake)' }
    @{ Name = 'Redis';     Port = 6379;  Expected = 'connected; needs PING' }
    @{ Name = 'Nacos';     Port = 8848;  Expected = 'banner > 0 (HTTP)' }
    @{ Name = 'RabbitMQ';  Port = 5672;  Expected = 'banner > 0 (AMQP greeting)' }
    @{ Name = 'Neo4j';     Port = 7687;  Expected = 'connected (bolt is silent)' }
    @{ Name = 'MinIO';     Port = 9000;  Expected = 'connected; needs HTTP' }
    @{ Name = 'Milvus';    Port = 19530; Expected = 'connected (grpc is silent)' }
)
foreach ($m in $middleware) {
    $r = Probe-TcpBanner -Server $WorkpcHost -Port $m.Port
    $color = switch ($r.Status) {
        'BANNER' { 'Green' }
        'CONNECTED_NO_BANNER' { 'Yellow' }
        default { 'Red' }
    }
    Write-Host (' [{0,-9}] {1,-22} {2}' -f $r.Status, ($m.Name + ':' + $m.Port), $r.Detail) -ForegroundColor $color
}
Write-Host ' 备注：Redis/Neo4j/MinIO/Milvus 是协议要客户端先发包；CONNECTED_NO_BANNER 是正常的。' -ForegroundColor DarkGray
Write-Host ' MySQL / Nacos / RabbitMQ 必须有 BANNER；如显 CONNECTED_NO_BANNER 说明 docker 没起。' -ForegroundColor DarkGray

# -- Step 2: gateway health --
Write-Section '2. Gateway 健康'
$g = Get-Json -Url "$GatewayBase/actuator/health"
if ($g.Ok) {
    Write-Host (' [OK ] {0}/actuator/health → {1}' -f $GatewayBase, $g.Status) -ForegroundColor Green
} else {
    Write-Host (' [FAIL] {0}/actuator/health → {1}' -f $GatewayBase, $g.Status) -ForegroundColor Red
    Write-Host '   gateway 没起来；先 .\mvnw -pl api-gateway spring-boot:run' -ForegroundColor Yellow
}

# -- Step 3: backend services direct (bypass gateway) --
Write-Section '3. 下游服务直连（绕过 gateway，定位是不是路由问题）'
$services = @(
    @{ Name = 'auth-service';      Base = $AuthBase }
    @{ Name = 'memory-service';    Base = $MemoryBase }
    @{ Name = 'resonance-service'; Base = $ResonanceBase }
)
foreach ($s in $services) {
    $r = Get-Json -Url "$($s.Base)/actuator/health"
    if ($r.Ok) {
        Write-Host (' [OK ] {0,-18} → {1}' -f $s.Name, $r.Status) -ForegroundColor Green
    } else {
        Write-Host (' [FAIL] {0,-18} → {1}' -f $s.Name, $r.Status) -ForegroundColor Red
        Write-Host ('   服务没起，先 .\mvnw -pl {0} spring-boot:run' -f $s.Name) -ForegroundColor Yellow
    }
}

# -- Step 4: real admin endpoints via gateway --
if (-not $Token) {
    Write-Section '4. Admin 端点真实响应（已跳过：未提供 ADMIN_TOKEN）'
    Write-Host ' 设置环境变量后重跑：' -ForegroundColor DarkGray
    Write-Host '   $env:ADMIN_TOKEN = "<从浏览器 localStorage.token 复制>"' -ForegroundColor DarkGray
    Write-Host '   .\scripts\Diagnose-AdminPanel.ps1' -ForegroundColor DarkGray
} else {
    Write-Section '4. Admin 端点真实响应（走 gateway，用 ADMIN_TOKEN）'
    $headers = @{ 'Authorization' = "Bearer $Token"; 'Accept-Language' = 'zh-CN' }
    $endpoints = @(
        '/api/v1/admin/health'
        '/api/v1/admin/stats/active-users?dimension=DAILY'
        '/api/v1/admin/stats/memory-trends?dimension=DAILY'
        '/api/v1/admin/stats/emotion-distribution'
        '/api/v1/admin/stats/heatmap?gridResolution=MEDIUM'
        '/api/v1/admin/stats/top-contributors'
        '/api/v1/admin/stats/fragment-discovery'
        '/api/v1/admin/stats/resonance-overview'
        '/api/v1/admin/stats/resonance-top'
    )
    foreach ($ep in $endpoints) {
        $r = Get-Json -Url "$GatewayBase$ep" -Headers $headers
        $status = $r.Status
        $color = switch ($status) {
            { $_ -ge 200 -and $_ -lt 300 } { 'Green' }
            401 { 'Red' }
            403 { 'Red' }
            404 { 'Red' }
            502 { 'Magenta' }
            default { 'Yellow' }
        }
        $hint = switch ($status) {
            401 { ' → token 失效或角色不是 ADMIN，需要重登' }
            403 { ' → token 不带 ADMIN 角色（DB 里 role 没升级？）' }
            404 { ' → gateway 路由表缺这条端点（更新 application.yml）' }
            502 { ' → 下游服务 down 或 5s timeout（看上一节是哪个 service）' }
            'ERR' { ' → 网络层失败（gateway 没起？）' }
            default { '' }
        }
        Write-Host (' [{0,3}] {1}{2}' -f $status, $ep, $hint) -ForegroundColor $color
    }
}

Write-Section '诊断完成'
Write-Host ' 阅读顺序：第 1 节 → 第 2 节 → 第 3 节 → 第 4 节，先看哪一层先红。' -ForegroundColor DarkGray
Write-Host ' 详细策略见 Memory.md §v6.4.1 / §v6.4.2。' -ForegroundColor DarkGray
