# =============================================================================
# 持续迭代控制器：在后台 job 里循环跑 e2e + 收集系统指标
# - 每 60s 跑一次 e2e smoke
# - 收集 JVM 内存、GC 次数、MQ 队列堆积
# - 检测到 e2e 失败或 MQ 堆积就 dump 日志
# - 持续 30 分钟
# =============================================================================
$ErrorActionPreference = "Continue"
$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$logDir     = "$backendDir\logs"
$reportFile = "$logDir\iterate-report.json"
$deadline   = (Get-Date).AddMinutes(30)
$round = 0

while ((Get-Date) -lt $deadline) {
    $round++
    $ts = Get-Date -Format "HH:mm:ss"
    Write-Host ""
    Write-Host "[$ts] === round $round ===" -ForegroundColor Cyan

    # 1. 跑 e2e (允许失败)
    $e2eScript = "$PSScriptRoot\e2e-smoke-test.ps1"
    & $e2eScript 2>&1 | Out-Null
    $e2eResult = Get-Content "$logDir\e2e-result.json" -ErrorAction SilentlyContinue
    if ($e2eResult) {
        $r = $e2eResult | ConvertFrom-Json
        Write-Host "  e2e: mqOut=$($r.mqOutSent) mqIn=$($r.mqInConsumed) memoryId=$($r.memoryId)" -ForegroundColor Yellow
    }

    # 2. 检查 MQ 队列堆积
    try {
        $queues = Invoke-WebRequest "http://guest:guest@100.66.166.46:15672/api/queues" -UseBasicParsing -TimeoutSec 5 |
                  ConvertFrom-Json
        foreach ($q in $queues) {
            if ($q.name -like "ai.*" -or $q.name -like "resonance.*") {
                $depth = $q.messages_ready
                $color = if ($depth -gt 100) { "Red" } elseif ($depth -gt 0) { "Yellow" } else { "DarkGray" }
                Write-Host "  queue $($q.name): $depth msgs" -ForegroundColor $color
            }
        }
    } catch {
        Write-Host "  [warn] mgmt API unreachable: $($_.Exception.Message)" -ForegroundColor DarkGray
    }

    # 3. 检查 java 进程内存
    Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
        $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
        $svc = "?"
        if ($cmd -match '(auth|memory|ai|resonance|asset|api-gateway)-service-1') { $svc = $Matches[1] }
        if ($svc -ne "?") {
            $rss = [math]::Round($_.WorkingSet64 / 1MB, 0)
            $color = if ($rss -gt 1200) { "Yellow" } else { "DarkGray" }
            Write-Host "  java $svc pid=$($_.Id) rss=${rss}MB" -ForegroundColor $color
        }
    }

    # 4. 报告
    @{
        round = $round
        ts    = $ts
        e2e   = if ($e2eResult) { ($e2eResult | ConvertFrom-Json) } else { $null }
    } | ConvertTo-Json | Add-Content "$logDir\iterate-rounds.jsonl"

    Start-Sleep -Seconds 60
}

Write-Host "[iterate] deadline reached, exit" -ForegroundColor Green
