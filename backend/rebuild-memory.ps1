$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$envFile = Join-Path $backendDir ".env.workpc"
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
}

# Kill memory-service only (port 8082)
$conn = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "killed memory PID $($conn.OwningProcess)"
}
Start-Sleep 2

# Build
Write-Host "=== build memory ==="
Set-Location $backendDir
$env:MAVEN_OPTS = "-Xmx4g -Xms512m"
& .\mvnw.cmd install -DskipTests -pl memory-service -am 2>&1 | Select-Object -Last 20

# Start memory
$jarPath = Join-Path $backendDir "memory-service\target\memory-service-1.0.0-SNAPSHOT.jar"
$logPath = Join-Path $backendDir "logs\memory-service.log"
$proc = Start-Process -FilePath "java" -ArgumentList @("-jar", $jarPath, "--server.port=8082") `
                      -WorkingDirectory "$backendDir\memory-service" `
                      -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err" `
                      -WindowStyle Hidden -PassThru
Write-Host "started memory PID=$($proc.Id)"

# Wait for port
for ($i = 0; $i -lt 30; $i++) {
    $c = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue
    if ($c) { Write-Host "port 8082 UP"; break }
    Start-Sleep 1
}
Write-Host "done"
