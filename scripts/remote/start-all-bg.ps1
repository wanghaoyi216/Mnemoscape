# =============================================================================
# 用 Start-Process 启独立 powershell 跑后端 — 不被 bash 工具的进程退出影响
# 立即返回，进程在后台真活
# =============================================================================
$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$logDir     = "$backendDir\logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

# 加载环境
Get-Content "$backendDir\.env.workpc" | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
}

function Launch-One {
    param([string]$Name, [int]$HeapMB = 1024)
    $jar = "$backendDir\$Name\target\$Name-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { Write-Host "[err] missing $jar"; return }
    $logFile = "$logDir\$Name.log"
    $errFile = "$logDir\$Name.err"
    $argList = @('-Xmx' + $HeapMB + 'm','-Xms256m','-jar',$jar)
    $proc = Start-Process -FilePath 'java.exe' -ArgumentList $argList -WorkingDirectory "$backendDir\$Name" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru -WindowStyle Hidden
    Write-Host ("[bg] {0,-16} pid={1,5}  log={2}.log" -f $Name, $proc.Id, $logFile) -ForegroundColor Green
}

# 串行启动，每个 sleep 5s 错开端口冲突检测窗口
Launch-One 'auth-service'
Start-Sleep -Seconds 3
Launch-One 'memory-service'
Start-Sleep -Seconds 3
Launch-One 'ai-service'
Write-Host ""
Write-Host "3 services launched as detached java processes. Wait 30-60s then test ports." -ForegroundColor Green
