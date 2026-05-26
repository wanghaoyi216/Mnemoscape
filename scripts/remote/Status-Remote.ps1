# =============================================================================
# Mnemoscape — Status-Remote.ps1
# Lightweight health / port summary for the workpc middleware stack.
# =============================================================================
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

Write-Host "==> docker compose ps" -ForegroundColor Cyan
$compose = 'D:\MnemoscapeInfra\docker-compose.remote.yml'
if (Test-Path $compose) {
  docker compose -f $compose ps
} else {
  Write-Host "  $compose not present" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==> Container health" -ForegroundColor Cyan
docker ps --filter "label=com.docker.compose.project=mnemoscape-infra" `
          --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

Write-Host ""
Write-Host "==> Listening ports (subset)" -ForegroundColor Cyan
$ports = 3306, 6379, 8848, 9848, 5672, 15672, 9000, 9001, 7474, 7687, 19530, 9091
foreach ($p in $ports) {
  $hit = (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1)
  if ($hit) {
    Write-Host ("  {0,-6}  LISTENING ({1})" -f $p, $hit.OwningProcess) -ForegroundColor Green
  } else {
    Write-Host ("  {0,-6}  not bound" -f $p) -ForegroundColor DarkGray
  }
}

Write-Host ""
Write-Host "==> D: drive usage" -ForegroundColor Cyan
$d = Get-PSDrive -Name D
'  used: {0:N1} GB   free: {1:N1} GB' -f ($d.Used / 1GB), ($d.Free / 1GB) | Write-Host
