# =============================================================================
# Mnemoscape 端到端冒烟测试：注册 → 登录 → 创建记忆 → 看 MQ 消费
# 设计：自己 wait 90s 让服务起来，然后跑所有 step，结果写 e2e-result.json
# =============================================================================
$ErrorActionPreference = "Continue"
$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$logDir     = "$backendDir\logs"
$resultFile = "$backendDir\logs\e2e-result.json"
$timestamp  = Get-Date -Format "yyyy-MM-ddTHH:mm:ss"

# 0. 等服务 ready（最长 90s）
Write-Host "[e2e] waiting 90s for services..." -ForegroundColor Cyan
$ports = @{ 'auth-service' = 8081; 'memory-service' = 8082; 'ai-service' = 8083 }
$ready = $false
for ($i = 0; $i -lt 18; $i++) {
    $all = $true
    foreach ($p in $ports.Values) {
        try {
            $r = Invoke-WebRequest "http://localhost:$p/actuator/health" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -ne 200) { $all = $false }
        } catch { $all = $false; break }
    }
    if ($all) { $ready = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    Write-Host "[e2e] services not ready, abort" -ForegroundColor Red
    @{ status = "aborted"; reason = "services not ready in 90s" } | ConvertTo-Json | Set-Content $resultFile
    exit 1
}
Write-Host "[e2e] all services healthy" -ForegroundColor Green

# 1. 注册用户
$rand = Get-Random -Minimum 100000 -Maximum 999999
$user = @{
    username = "e2e_$rand"
    email    = "e2e_$rand@mnemoscape.test"
    password = "TestPass123!"
} | ConvertTo-Json

Write-Host "[e2e] step 1: register user $user.username"
try {
    $r1 = Invoke-WebRequest "http://localhost:8081/api/v1/auth/register" -Method Post -Body $user -ContentType "application/json" -UseBasicParsing -TimeoutSec 10
    Write-Host "  -> $($r1.StatusCode) $($r1.Content)"
} catch {
    Write-Host "  -> FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# 2. 登录拿 JWT
Write-Host "[e2e] step 2: login"
$login = @{ username = $user.username; password = $user.password } | ConvertTo-Json
$token = $null
try {
    $r2 = Invoke-WebRequest "http://localhost:8081/api/v1/auth/login" -Method Post -Body $login -ContentType "application/json" -UseBasicParsing -TimeoutSec 10
    $body = $r2.Content | ConvertFrom-Json
    $token = $body.data.token
    Write-Host "  -> token len=$(if ($token) { $token.Length } else { 0 })"
} catch {
    Write-Host "  -> FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# 3. 创建记忆
Write-Host "[e2e] step 3: create memory"
$memory = @{
    title = "E2E 自动化测试记忆 $rand"
    description = "这是端到端测试创建的一条记忆。来源: Start-Job 调度, 时间: $timestamp"
    memoryLocation = "上海"
    memoryYear = 2024
    privacyLevel = "PRIVATE"
} | ConvertTo-Json
$memoryId = $null
if ($token) {
    $hdr = @{ Authorization = "Bearer $token" }
    try {
        $r3 = Invoke-WebRequest "http://localhost:8082/api/v1/memories" -Method Post -Body $memory -ContentType "application/json" -Headers $hdr -UseBasicParsing -TimeoutSec 15
        $body = $r3.Content | ConvertFrom-Json
        $memoryId = $body.data.id
        Write-Host "  -> memoryId=$memoryId" -ForegroundColor Green
    } catch {
        Write-Host "  -> FAILED: $($_.Exception.Message)" -ForegroundColor Red
    }
}

# 4. 等 8s 看 ai-service 是否消费 MQ
Write-Host "[e2e] step 4: wait 8s for ai-service to consume MQ"
Start-Sleep -Seconds 8
$aiLog = Get-Content "$logDir\ai-service.log" -Tail 50 -ErrorAction SilentlyContinue
$memLog = Get-Content "$logDir\memory-service.log" -Tail 20 -ErrorAction SilentlyContinue
$mqIn  = ($aiLog | Select-String -Pattern "mq-in.*memory.indexed").Count
$mqOut = ($memLog | Select-String -Pattern "mq-out.*memory.indexed").Count
Write-Host "  -> mq-in (ai-service consumed) = $mqIn"
Write-Host "  -> mq-out (memory-service sent) = $mqOut"

# 5. 删除记忆（验证 memory.deleted 也走 MQ）
$deleteResult = "skipped"
if ($token -and $memoryId) {
    Write-Host "[e2e] step 5: delete memory $memoryId"
    $hdr = @{ Authorization = "Bearer $token" }
    try {
        $r5 = Invoke-WebRequest "http://localhost:8082/api/v1/memories/$memoryId" -Method Delete -Headers $hdr -UseBasicParsing -TimeoutSec 10
        Write-Host "  -> $($r5.StatusCode)"
        $deleteResult = "ok"
    } catch {
        Write-Host "  -> FAILED: $($_.Exception.Message)" -ForegroundColor Red
        $deleteResult = "failed"
    }
    Start-Sleep -Seconds 5
    $aiLog2  = Get-Content "$logDir\ai-service.log"     -Tail 80 -ErrorAction SilentlyContinue
    $resLog2 = Get-Content "$logDir\resonance-service.log" -Tail 80 -ErrorAction SilentlyContinue
    $delAi  = ($aiLog2  | Select-String -Pattern "mq-in.*memory.deleted.*$memoryId").Count
    Write-Host "  -> mq-in (ai-service) memory.deleted = $delAi"
}

# 写结果
$result = @{
    timestamp   = $timestamp
    user        = $user.username
    tokenGot    = [bool]$token
    memoryId    = $memoryId
    mqOutSent   = $mqOut
    mqInConsumed = $mqIn
    deleteResult = $deleteResult
} | ConvertTo-Json
$result | Set-Content $resultFile
Write-Host ""
Write-Host "[e2e] DONE. result -> $resultFile" -ForegroundColor Green
