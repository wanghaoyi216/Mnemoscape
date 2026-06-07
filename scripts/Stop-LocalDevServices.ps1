# =============================================================================
# Mnemoscape — Stop-LocalDevServices.ps1
# =============================================================================
# Cleanly terminates all locally running Spring Boot microservices launched by
# Start-LocalDevServices.ps1 by matching their window titles.
# =============================================================================
$ErrorActionPreference = "SilentlyContinue"

Write-Host "== Terminating Local Spring Boot Microservices cleanly ==" -ForegroundColor Cyan

$closed = 0
Get-Process | Where-Object { $_.MainWindowTitle -like "Mnemoscape-*" } | ForEach-Object {
  $name = $_.MainWindowTitle
  Stop-Process $_.Id -Force
  Write-Host "  Stopped $name" -ForegroundColor Gray
  $closed++
}

Write-Host "`n== Cleaned up $closed services successfully! ==" -ForegroundColor Green
