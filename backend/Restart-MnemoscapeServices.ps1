# Restart-MnemoscapeServices.ps1
# 重启 6 个 service (api-gateway, auth, memory, ai, resonance, asset)
# 读 .env.workpc 设环境变量后用 Start-Process 启 jar

$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$envFile = Join-Path $backendDir ".env.workpc"
$logsDir = Join-Path $backendDir "logs"
$jarDir = $backendDir

if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }

# 加载 .env.workpc 到 process env
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $name = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

# 服务定义
$services = @(
    @{ name = "api-gateway";   jar = "api-gateway-1.0.0-SNAPSHOT.jar";   port = 8080; subdir = "api-gateway" },
    @{ name = "auth-service";  jar = "auth-service-1.0.0-SNAPSHOT.jar";  port = 8081; subdir = "auth-service" },
    @{ name = "memory-service"; jar = "memory-service-1.0.0-SNAPSHOT.jar"; port = 8082; subdir = "memory-service" },
    @{ name = "ai-service";    jar = "ai-service-1.0.0-SNAPSHOT.jar";    port = 8083; subdir = "ai-service" },
    @{ name = "resonance-service"; jar = "resonance-service-1.0.0-SNAPSHOT.jar"; port = 8084; subdir = "resonance-service" },
    @{ name = "asset-service"; jar = "asset-service-1.0.0-SNAPSHOT.jar"; port = 8085; subdir = "asset-service" }
)

# 杀占端口的进程
Write-Host "=== 杀掉 8080-8085 占端口的 java 进程 ==="
foreach ($svc in $services) {
    $conn = Get-NetTCPConnection -LocalPort $svc.port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn -and $conn.OwningProcess) {
        try {
            Stop-Process -Id $conn.OwningProcess -Force -ErrorAction Stop
            Write-Host "  killed PID $($conn.OwningProcess) (port $($svc.port))"
        } catch {
            Write-Host "  failed PID $($conn.OwningProcess): $_"
        }
    }
}
Start-Sleep 2

# 启 6 个 service
Write-Host "=== 启动 6 个 service ==="
foreach ($svc in $services) {
    $jarPath = Join-Path (Join-Path $jarDir $svc.subdir) "target\$($svc.jar)"
    if (-not (Test-Path $jarPath)) {
        Write-Host "  MISSING: $jarPath"
        continue
    }
    $logPath = Join-Path $logsDir "$($svc.name).log"
    $args = @("-jar", $jarPath, "--server.port=$($svc.port)")
    $proc = Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory (Join-Path $jarDir $svc.subdir) `
                          -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err" `
                          -WindowStyle Hidden -PassThru
    Write-Host "  started $($svc.name) PID=$($proc.Id) port=$($svc.port) log=$logPath"
    Start-Sleep 1
}

Write-Host "=== 端口等待 ==="
foreach ($svc in $services) {
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        $conn = Get-NetTCPConnection -LocalPort $svc.port -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            Write-Host "  port $($svc.port) ($($svc.name)) UP"
            $ready = $true
            break
        }
        Start-Sleep 1
    }
    if (-not $ready) {
        Write-Host "  port $($svc.port) ($($svc.name)) NOT listening after 30s"
    }
}
