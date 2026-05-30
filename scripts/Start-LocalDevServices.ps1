# =============================================================================
# Mnemoscape — Start-LocalDevServices.ps1
# =============================================================================
# Automatically loads the remote workstation environment variables and
# concurrently boots up all the local Spring Boot microservices inside new
# PowerShell windows, preventing the "missing env vars in new tabs" trap!
# =============================================================================
[CmdletBinding()]
param(
  [string]$EnvFile,
  [switch]$SkipPreflight
)

$ErrorActionPreference = "Stop"
$scriptRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { $PSScriptRoot }
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
  $EnvFile = Join-Path $scriptRoot '..\backend\.env.workpc'
}
$backendRoot = (Resolve-Path (Join-Path $scriptRoot '..\backend')).Path

if (-not (Test-Path $EnvFile)) {
  Write-Host "[FATAL] env file not found: $EnvFile" -ForegroundColor Red
  exit 1
}

# 1. Load env variables into process
Write-Host "== Loading Remote Workstation Env Variables ==" -ForegroundColor Cyan
Get-Content $EnvFile | ForEach-Object {
  if ($_ -match '^\s*$') { return }
  if ($_ -match '^\s*#') { return }
  if ($_ -match '^\s*([^=]+?)\s*=\s*(.*?)\s*$') {
    $k = $Matches[1].Trim()
    $v = $Matches[2].Trim()
    # Skip empty values: an empty NVIDIA_API_KEY= line in the env file must NOT
    # override the application.yml placeholder default. Spring AI requires
    # api-key to be non-empty during bean autowiring, so we only push values
    # that are actually filled in.
    if ([string]::IsNullOrWhiteSpace($v)) {
      Write-Host "  $k skipped (empty)" -ForegroundColor DarkGray
      return
    }
    [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    Write-Host "  $k loaded" -ForegroundColor DarkGray
  }
}

Write-Host "== Effective Local Dev Targets ==" -ForegroundColor Cyan
$effectiveRemoteHost = [Environment]::GetEnvironmentVariable('MNEMOSCAPE_REMOTE_HOST', 'Process')
if ([string]::IsNullOrWhiteSpace($effectiveRemoteHost)) { $effectiveRemoteHost = '100.66.166.46' }
$effectiveNacosIp = [Environment]::GetEnvironmentVariable('SPRING_CLOUD_NACOS_DISCOVERY_IP', 'Process')
if ([string]::IsNullOrWhiteSpace($effectiveNacosIp)) { $effectiveNacosIp = '<auto>' }
$effectiveProfile = [Environment]::GetEnvironmentVariable('SPRING_PROFILES_ACTIVE', 'Process')
if ([string]::IsNullOrWhiteSpace($effectiveProfile)) { $effectiveProfile = '<unset>' }
Write-Host ("  MNEMOSCAPE_REMOTE_HOST              = {0}" -f $effectiveRemoteHost)
Write-Host ("  SPRING_CLOUD_NACOS_DISCOVERY_IP     = {0}" -f $effectiveNacosIp)
Write-Host ("  SPRING_PROFILES_ACTIVE              = {0}" -f $effectiveProfile)
Write-Host ("  Backend root                        = {0}" -f $backendRoot)

if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SPRING_CLOUD_NACOS_DISCOVERY_IP', 'Process'))) {
  Write-Host "  [WARN] SPRING_CLOUD_NACOS_DISCOVERY_IP is empty; Spring may auto-pick a local interface." -ForegroundColor Yellow
}

# 2. Pre-flight: confirm workstation infra is reachable BEFORE we spawn 6 windows
if (-not $SkipPreflight) {
  $remoteHost = [Environment]::GetEnvironmentVariable('MNEMOSCAPE_REMOTE_HOST', 'Process')
  if (-not $remoteHost) { $remoteHost = '100.66.166.46' }

  Write-Host "`n== Pre-flight: probing workstation infra at $remoteHost ==" -ForegroundColor Cyan
  $probes = @(
    @{ Name = 'Nacos';    Port = 8848 }
    @{ Name = 'MySQL';    Port = 3306 }
    @{ Name = 'RabbitMQ'; Port = 5672 }
    @{ Name = 'Redis';    Port = 6379 }
  )
  $failed = @()
  foreach ($p in $probes) {
    $ok = Test-NetConnection -ComputerName $remoteHost -Port $p.Port -InformationLevel Quiet -WarningAction SilentlyContinue
    if ($ok) {
      Write-Host ("  [OK]   {0,-9} {1}:{2}" -f $p.Name, $remoteHost, $p.Port) -ForegroundColor Green
    } else {
      Write-Host ("  [FAIL] {0,-9} {1}:{2}" -f $p.Name, $remoteHost, $p.Port) -ForegroundColor Red
      $failed += $p.Name
    }
  }
  if ($failed.Count -gt 0) {
    Write-Host "`n[ABORT] Workstation infra unreachable: $($failed -join ', ')" -ForegroundColor Red
    Write-Host "  - Check Tailscale: ``tailscale status`` and ``ping $remoteHost``" -ForegroundColor Yellow
    Write-Host "  - On workstation: ``cd D:\MnemoscapeInfra; docker compose -f docker-compose.remote.yml up -d``" -ForegroundColor Yellow
    Write-Host "  - On workstation: run ``scripts\remote\Open-Firewall.ps1`` once to open ports" -ForegroundColor Yellow
    Write-Host "  - To bypass this check anyway, re-run with -SkipPreflight" -ForegroundColor DarkGray
    exit 2
  }
  Write-Host "  All probes green — proceeding to launch local services." -ForegroundColor Green
}

# 3. Boot up local services in new windows
$services = @('api-gateway', 'auth-service', 'memory-service', 'ai-service', 'resonance-service', 'asset-service')
Write-Host "`n== Launching Local Spring Boot Microservices in New Windows ==" -ForegroundColor Cyan

foreach ($service in $services) {
  Write-Host "  -> Starting $service..." -ForegroundColor Green
  # Start process with custom window title and inherited environment variables
  Start-Process powershell `
    -WorkingDirectory $backendRoot `
    -ArgumentList "-NoExit", "-Command", "`$host.ui.RawUI.WindowTitle = 'Mnemoscape-$service'; Write-Host 'Starting $service in remote workpc mode...' -ForegroundColor Cyan; .\mvnw.cmd -pl $service spring-boot:run"
  Start-Sleep -Seconds 1
}

Write-Host "`n== All Local Dev Services Have Been Triggered! ==" -ForegroundColor Green
Write-Host "Tip: To shut down all services cleanly at once, run: powershell -File scripts\Stop-LocalDevServices.ps1" -ForegroundColor Yellow
Write-Host "Now start your local frontend dev server: cd frontend; npm run dev" -ForegroundColor Cyan
