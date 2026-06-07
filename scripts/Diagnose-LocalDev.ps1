#!/usr/bin/env pwsh
[CmdletBinding()]
param(
  [string]$EnvFile,
  [string]$RemoteHost = '100.66.166.46'
)

$ErrorActionPreference = 'Continue'
$scriptRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { $PSScriptRoot }
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
  $EnvFile = Join-Path $scriptRoot '..\backend\.env.workpc'
}

function Write-Section([string]$Title) {
  Write-Host ""
  Write-Host "==> $Title" -ForegroundColor Cyan
}

function Load-EnvFile([string]$Path) {
  if (-not (Test-Path $Path)) {
    Write-Host "[WARN] env file not found: $Path" -ForegroundColor Yellow
    return
  }

  Get-Content $Path | ForEach-Object {
    if ($_ -match '^\s*$') { return }
    if ($_ -match '^\s*#') { return }
    if ($_ -match '^\s*([^=]+?)\s*=\s*(.*?)\s*$') {
      [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
  }
}

Load-EnvFile $EnvFile

$loadedRemoteHost = [Environment]::GetEnvironmentVariable('MNEMOSCAPE_REMOTE_HOST', 'Process')
if (-not [string]::IsNullOrWhiteSpace($loadedRemoteHost)) {
  $RemoteHost = $loadedRemoteHost
}

Write-Section "Environment"
$keys = @(
  'MNEMOSCAPE_REMOTE_HOST',
  'MYSQL_HOST',
  'REDIS_HOST',
  'NACOS_HOST',
  'RABBITMQ_HOST',
  'NEO4J_HOST',
  'MINIO_ENDPOINT',
  'MILVUS_HOST',
  'SPRING_CLOUD_NACOS_DISCOVERY_IP',
  'SPRING_PROFILES_ACTIVE'
)
foreach ($key in $keys) {
  $value = [Environment]::GetEnvironmentVariable($key, 'Process')
  if ([string]::IsNullOrWhiteSpace($value)) {
    $value = '<unset>'
  }
  Write-Host ("  {0,-34} {1}" -f $key, $value)
}

Write-Section "Remote Docker Ports"
$ports = @(3306, 6379, 8848, 5672, 15672, 9000, 9001, 7474, 7687, 19530, 9091)
foreach ($port in $ports) {
  $ok = Test-NetConnection -ComputerName $RemoteHost -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
  $mark = if ($ok) { 'OK' } else { 'FAIL' }
  Write-Host ("  {0}:{1,-5} {2}" -f $RemoteHost, $port, $mark)
}

Write-Section "Local Java Listeners"
$listeners = Get-NetTCPConnection -State Listen -LocalPort 8080,8081,8082,8083,8084,8085 -ErrorAction SilentlyContinue
if ($listeners) {
  $listeners |
    Sort-Object LocalPort |
    Select-Object LocalAddress, LocalPort, OwningProcess |
    Format-Table -AutoSize
} else {
  Write-Host "  No local backend ports are listening yet."
}

Write-Section "Tailscale"
if (Get-Command tailscale.exe -ErrorAction SilentlyContinue) {
  tailscale status --json | ConvertFrom-Json | Select-Object BackendState, Self | ConvertTo-Json -Depth 4
  tailscale ping --timeout=5s $RemoteHost
} else {
  Write-Host "  tailscale.exe not found."
}

Write-Section "Nacos Registrations"
$services = @('api-gateway', 'auth-service', 'memory-service', 'ai-service', 'resonance-service', 'asset-service')
foreach ($service in $services) {
  try {
    $uri = "http://${RemoteHost}:8848/nacos/v1/ns/instance/list?serviceName=$service&groupName=DEFAULT_GROUP"
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
      $json = & curl.exe -sS --max-time 10 $uri
      if ($LASTEXITCODE -ne 0) {
        throw "curl.exe exited with $LASTEXITCODE"
      }
      $response = $json | ConvertFrom-Json
    } else {
      $response = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    }
    $hosts = @($response.hosts)
    if ($hosts.Count -gt 0) {
      $summary = $hosts | ForEach-Object { "{0}:{1} healthy={2}" -f $_.ip, $_.port, $_.healthy }
      Write-Host ("  {0,-18} {1}" -f $service, ($summary -join ', '))
    } else {
      Write-Host ("  {0,-18} <no instances>" -f $service) -ForegroundColor Yellow
    }
  } catch {
    Write-Host ("  {0,-18} <nacos query failed: {1}>" -f $service, $_.Exception.Message) -ForegroundColor Yellow
  }
}

Write-Section "Snapshot Freshness"
$source = Get-Item (Join-Path $scriptRoot '..\backend\common\src\main\java\com\mnemoscape\common\exception\GlobalExceptionHandler.java')
$snapshotJar = Join-Path $env:USERPROFILE '.m2\repository\com\mnemoscape\common\1.0.0-SNAPSHOT\common-1.0.0-SNAPSHOT.jar'
Write-Host ("  Source file : {0}" -f $source.LastWriteTime)
if (Test-Path $snapshotJar) {
  $jarItem = Get-Item $snapshotJar
  Write-Host ("  SNAPSHOT jar: {0}" -f $jarItem.LastWriteTime)
  if ($jarItem.LastWriteTime -lt $source.LastWriteTime) {
    Write-Host "  [WARN] local ~/.m2 snapshot is older than source; run local services with -am or rebuild backend/common." -ForegroundColor Yellow
  } else {
    Write-Host "  Snapshot jar looks newer than source." -ForegroundColor Green
  }
} else {
  Write-Host "  [WARN] common SNAPSHOT jar not found in local ~/.m2." -ForegroundColor Yellow
}
