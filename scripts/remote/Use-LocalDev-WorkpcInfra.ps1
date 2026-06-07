# =============================================================================
# Mnemoscape — Use-LocalDev-WorkpcInfra.ps1
# =============================================================================
# Bootstraps the current PowerShell session so locally-run Spring Boot services
# resolve MySQL / Redis / Nacos / RabbitMQ / MinIO / Neo4j / Milvus on the
# workpc (100.66.166.46) via Tailscale.
#
# Usage:
#   # source the env values into the current shell, then start services
#   . .\scripts\remote\Use-LocalDev-WorkpcInfra.ps1
#   .\backend\mvnw.cmd -pl common,api-gateway       spring-boot:run -f .\backend\pom.xml
#   .\backend\mvnw.cmd -pl common,auth-service      spring-boot:run -f .\backend\pom.xml
#   .\backend\mvnw.cmd -pl common,memory-service    spring-boot:run -f .\backend\pom.xml
#   .\backend\mvnw.cmd -pl common,ai-service        spring-boot:run -f .\backend\pom.xml
#   .\backend\mvnw.cmd -pl common,resonance-service spring-boot:run -f .\backend\pom.xml
#   .\backend\mvnw.cmd -pl common,asset-service     spring-boot:run -f .\backend\pom.xml
#   cd .\frontend; npm run dev
# =============================================================================
[CmdletBinding()]
param(
  [string]$EnvFile = (Join-Path $PSScriptRoot '..\..\backend\.env.workpc'),
  [switch]$Quiet
)

if (-not (Test-Path $EnvFile)) {
  Write-Host "[FATAL] env file not found: $EnvFile" -ForegroundColor Red
  exit 1
}

$loaded = 0
Get-Content $EnvFile | ForEach-Object {
  if ($_ -match '^\s*$') { return }
  if ($_ -match '^\s*#') { return }
  if ($_ -match '^\s*([^=]+?)\s*=\s*(.*?)\s*$') {
    $k = $Matches[1]
    $v = $Matches[2]
    [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    $loaded++
    if (-not $Quiet) {
      $shown = if ($k -match 'PASSWORD|SECRET|KEY') { '***' } else { $v }
      Write-Host ("  {0} = {1}" -f $k, $shown)
    }
  }
}

Write-Host ""
Write-Host "==> Loaded $loaded variables from $EnvFile" -ForegroundColor Green
Write-Host "    Tip: run a quick reachability check:" -ForegroundColor DarkGray
Write-Host "         Test-NetConnection 100.66.166.46 -Port 3306" -ForegroundColor DarkGray
Write-Host "         Test-NetConnection 100.66.166.46 -Port 8848" -ForegroundColor DarkGray
