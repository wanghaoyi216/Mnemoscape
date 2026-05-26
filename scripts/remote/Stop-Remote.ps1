# =============================================================================
# Mnemoscape — Stop-Remote.ps1
# Tears the middleware stack down without touching D:\MnemoscapeInfra data.
# Use -Wipe to also delete persisted volumes on D:\.
# =============================================================================
[CmdletBinding()]
param(
  [switch]$Wipe
)

$ErrorActionPreference = 'Stop'

$Root        = 'D:\MnemoscapeInfra'
$ComposeFile = Join-Path $Root 'docker-compose.remote.yml'

if (-not (Test-Path $ComposeFile)) {
  Write-Host "[INFO] $ComposeFile not present — nothing to stop." -ForegroundColor Yellow
  exit 0
}

Write-Host "==> docker compose down" -ForegroundColor Cyan
Push-Location $Root
& docker compose -f $ComposeFile --profile vector --profile full down --remove-orphans
Pop-Location

if ($Wipe) {
  Write-Host "==> wiping persisted data at $Root" -ForegroundColor Yellow
  $confirmation = Read-Host "Type 'WIPE' to confirm deletion of all D:\MnemoscapeInfra data"
  if ($confirmation -eq 'WIPE') {
    foreach ($d in 'mysql','redis','nacos','rabbitmq','minio','neo4j','etcd','milvus') {
      $p = Join-Path $Root $d
      if (Test-Path $p) {
        Remove-Item -Recurse -Force $p
        Write-Host "  removed $p"
      }
    }
  } else {
    Write-Host "  wipe aborted (confirmation mismatch)"
  }
}

Write-Host "==> done" -ForegroundColor Green
