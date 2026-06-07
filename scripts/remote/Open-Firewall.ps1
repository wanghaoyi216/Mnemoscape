# =============================================================================
# Mnemoscape — Open-Firewall.ps1   (RUN AS ADMINISTRATOR on workpc)
# Allows inbound TCP on the middleware ports so the dev laptop can reach
# them over Tailscale / LAN.
# =============================================================================
[CmdletBinding()]
param(
  [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Write-Host "[FATAL] Open-Firewall.ps1 must run elevated (Administrator)." -ForegroundColor Red
  Write-Host "         Open PowerShell as Administrator and rerun."        -ForegroundColor Yellow
  exit 1
}

$rules = @(
  @{ Name = 'Mnemoscape-MySQL';       Port = 3306;  Desc = 'Mnemoscape MySQL' }
  @{ Name = 'Mnemoscape-Redis';       Port = 6379;  Desc = 'Mnemoscape Redis' }
  @{ Name = 'Mnemoscape-Nacos-API';   Port = 8848;  Desc = 'Mnemoscape Nacos HTTP' }
  @{ Name = 'Mnemoscape-Nacos-gRPC';  Port = 9848;  Desc = 'Mnemoscape Nacos gRPC' }
  @{ Name = 'Mnemoscape-RabbitMQ';    Port = 5672;  Desc = 'Mnemoscape RabbitMQ AMQP' }
  @{ Name = 'Mnemoscape-RabbitMQ-UI'; Port = 15672; Desc = 'Mnemoscape RabbitMQ management UI' }
  @{ Name = 'Mnemoscape-MinIO-S3';    Port = 9000;  Desc = 'Mnemoscape MinIO S3 API' }
  @{ Name = 'Mnemoscape-MinIO-UI';    Port = 9001;  Desc = 'Mnemoscape MinIO console' }
  @{ Name = 'Mnemoscape-Neo4j-HTTP';  Port = 7474;  Desc = 'Mnemoscape Neo4j browser' }
  @{ Name = 'Mnemoscape-Neo4j-Bolt';  Port = 7687;  Desc = 'Mnemoscape Neo4j Bolt' }
  @{ Name = 'Mnemoscape-Milvus';      Port = 19530; Desc = 'Mnemoscape Milvus gRPC' }
  @{ Name = 'Mnemoscape-Milvus-Web';  Port = 9091;  Desc = 'Mnemoscape Milvus health' }
)

foreach ($r in $rules) {
  $existing = Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue
  if ($Remove) {
    if ($existing) {
      Remove-NetFirewallRule -DisplayName $r.Name
      Write-Host "  removed rule $($r.Name)"
    }
    continue
  }

  if ($existing) {
    Write-Host "  rule $($r.Name) already present"
    continue
  }
  New-NetFirewallRule -DisplayName $r.Name `
                      -Description $r.Desc `
                      -Direction Inbound `
                      -Protocol TCP `
                      -LocalPort $r.Port `
                      -Action Allow `
                      -Profile Any | Out-Null
  Write-Host "  allowed inbound tcp/$($r.Port)  ($($r.Name))" -ForegroundColor Green
}

Write-Host ""
if ($Remove) {
  Write-Host "==> firewall rules removed" -ForegroundColor Yellow
} else {
  Write-Host "==> firewall ready" -ForegroundColor Green
}
