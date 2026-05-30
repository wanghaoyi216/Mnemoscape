# Mnemoscape — Start-Background.ps1
# Starts all backend services non-interactively using NoNewWindow
# Keeps the script alive to prevent Windows Job Object cleanup of children

$services = @('api-gateway', 'auth-service', 'memory-service', 'ai-service', 'resonance-service', 'asset-service')
foreach ($service in $services) {
    Write-Host "Launching $service in background..." -ForegroundColor Green
    Start-Process powershell `
        -NoNewWindow `
        -ArgumentList "-ExecutionPolicy", "Bypass", "-File", "scripts\Start-OneService.ps1", "-Service", "$service"
    Start-Sleep -Seconds 1
}
Write-Host "All services triggered in background! Keeping script alive..." -ForegroundColor Green

# Infinite loop to keep the parent process and its child processes alive in the sandbox
while ($true) {
    Start-Sleep -Seconds 30
}
