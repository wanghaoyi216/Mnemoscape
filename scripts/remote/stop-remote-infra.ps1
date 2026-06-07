# =============================================================================
# Mnemoscape — workpc 端停掉所有中间件（保留数据卷）
# 用法：.\stop-remote-infra.ps1
#       .\stop-remote-infra.ps1 -RemoveVolumes  # 同时删除数据卷（慎用！）
# =============================================================================
[CmdletBinding()]
param(
    [switch]$RemoveVolumes
)
$ErrorActionPreference = "Stop"
Push-Location "D:\MnemoscapeInfra"
try {
    if ($RemoveVolumes) {
        docker compose -f docker-compose.remote.yml down -v
    } else {
        docker compose -f docker-compose.remote.yml down
    }
} finally {
    Pop-Location
}
Write-Host "Mnemoscape infra stopped." -ForegroundColor Green
