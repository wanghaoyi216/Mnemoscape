# =============================================================================
# 后台启动后端服务（启动后立即返回，不等 /actuator/health）
# 用法：
#   .\start-backend.ps1 -Service auth-service
#   .\start-backend.ps1 -Service all   # 启 3 个核心服务
#
# 配合 .\poll-backend.ps1 检查就绪状态
# =============================================================================
[CmdletBinding()]
param(
    [Parameter(Position=0)]
    [ValidateSet('auth-service','memory-service','ai-service','resonance-service','asset-service','api-gateway','all')]
    [string]$Service = 'auth-service',
    [int]$HeapMB = 1024
)

$ErrorActionPreference = "Stop"
$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$logDir     = "$backendDir\logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

# 加载 .env.workpc
$envFile = "$backendDir\.env.workpc"
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
}

function Launch-One {
    param([string]$Name)
    $jar = "$backendDir\$Name\target\$Name-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { Write-Host "[err] missing jar: $jar"; return $null }
    $logFile = "$logDir\$Name.log"
    $errFile = "$logDir\$Name.err"
    $args = @("-Xmx${HeapMB}m","-Xms256m","-jar",$jar)
    $proc = Start-Process -FilePath "java.exe" -ArgumentList $args `
        -WorkingDirectory "$backendDir\$Name" `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
        -PassThru -NoNewWindow
    Write-Host ("[started] {0,-16}  pid={1,5}  log={2}\{3}.log" -f $Name, $proc.Id, $logDir, $Name) -ForegroundColor Green
    return $proc
}

$ports = @{
    'auth-service'      = 8081
    'memory-service'    = 8082
    'ai-service'        = 8083
    'resonance-service' = 8084
    'asset-service'     = 8085
    'api-gateway'       = 8080
}

if ($Service -eq 'all') {
    foreach ($s in @('auth-service','memory-service','ai-service')) { Launch-One $s }
} else {
    Launch-One $Service
}
Write-Host ""
Write-Host "Tip: run .\poll-backend.ps1 to wait for readiness, or tail logs\<service>.log" -ForegroundColor DarkGray
