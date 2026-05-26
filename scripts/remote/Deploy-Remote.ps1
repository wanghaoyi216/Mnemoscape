# =============================================================================
# Mnemoscape — remote Deploy-Remote.ps1
# Executed ON the workpc by Deploy-To-Workpc.ps1.  All data is on D:\.
# =============================================================================
[CmdletBinding()]
param(
  [ValidateSet('core', 'vector', 'full')]
  [string]$Profile = 'vector',
  [switch]$Pull,
  [switch]$Recreate,
  [switch]$Services
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$Root                = 'D:\MnemoscapeInfra'
$ComposeFile         = Join-Path $Root 'docker-compose.remote.yml'
$ComposeServicesFile = Join-Path $Root 'docker-compose.services.remote.yml'
$DockerConfig        = Join-Path $Root '.docker'

function Step($msg) {
  Write-Host ""
  Write-Host "==> $msg" -ForegroundColor Cyan
}

function FailIf($cond, $msg) {
  if ($cond) { Write-Host "[FATAL] $msg" -ForegroundColor Red; exit 1 }
}

# --- 0. Sanity --------------------------------------------------------------
Step "Sanity checks"
$dDrive = Get-PSDrive -Name D -ErrorAction SilentlyContinue
FailIf ($null -eq $dDrive) "D: drive not present"
$freeGB = [math]::Round($dDrive.Free / 1GB, 1)
Write-Host "D: free space = ${freeGB} GB"
FailIf ($freeGB -lt 20) "D: free space below 20 GB — refusing to deploy"

$cDrive = Get-PSDrive -Name C -ErrorAction SilentlyContinue
$cFreeGB = [math]::Round($cDrive.Free / 1GB, 1)
Write-Host "C: free space = ${cFreeGB} GB (off-limits — only Docker WSL VM may use it)"

# Docker reachable?
try { & docker --config $DockerConfig version --format '{{.Server.Version}}' | Out-Null }
catch { FailIf $true "Docker daemon not reachable. Start Docker Desktop first." }

FailIf (-not (Test-Path $ComposeFile)) "Missing $ComposeFile — upload it first"
if ($Services) {
  FailIf (-not (Test-Path $ComposeServicesFile)) "Missing $ComposeServicesFile — upload it first"
}

# --- 1. Directory tree ------------------------------------------------------
Step "Ensuring D:\MnemoscapeInfra layout"
$dirs = @(
  'mysql\data', 'mysql\conf', 'mysql\init',
  'redis\data',
  'nacos\data', 'nacos\logs',
  'rabbitmq\data',
  'minio\data',
  'neo4j\data', 'neo4j\logs', 'neo4j\import',
  'etcd\data',
  'milvus\data'
)
foreach ($d in $dirs) {
  $full = Join-Path $Root $d
  if (-not (Test-Path $full)) {
    New-Item -ItemType Directory -Path $full -Force | Out-Null
    Write-Host "  created $full"
  }
}

# --- 2. RabbitMQ config (force load definitions.json) -----------------------
$rmqConf = Join-Path $Root 'rabbitmq\rabbitmq.conf'
if (-not (Test-Path $rmqConf)) {
  @"
default_user = guest
default_pass = guest
default_vhost = /
loopback_users.guest = false
management.load_definitions = /etc/rabbitmq/definitions.json
"@ | Out-File -FilePath $rmqConf -Encoding ascii
  Write-Host "  wrote $rmqConf"
}

# --- 3. (Optional) docker compose pull --------------------------------------
if ($Pull) {
  Step "docker compose pull"
  Push-Location $Root
  $pullArgs = @('compose', '-f', $ComposeFile)
  if ($Services) { $pullArgs += @('-f', $ComposeServicesFile) }
  else { $pullArgs += @('--profile', $Profile) }
  $pullArgs += 'pull'
  & docker --config $DockerConfig $pullArgs
  Pop-Location
}

# --- 4. Bring stack up ------------------------------------------------------
Step "docker compose up -d (profile=$Profile, services=$Services)"
Push-Location $Root
$args = @('compose', '-f', $ComposeFile)
if ($Services) {
  $args += @('-f', $ComposeServicesFile)
} else {
  $args += @('--profile', $Profile)
}
$args += @('up', '-d')
if ($Recreate) { $args += '--force-recreate' }
if ($Services) { $args += '--build' }
& docker --config $DockerConfig $args
$rc = $LASTEXITCODE
Pop-Location
FailIf ($rc -ne 0) "docker compose up exited with code $rc"

# --- 5. Wait on healthchecks -----------------------------------------------
Step "Waiting for healthchecks (max ~3 min)"
$deadline = (Get-Date).AddMinutes(3)
$expected = @('mnemoscape-mysql','mnemoscape-redis','mnemoscape-nacos','mnemoscape-rabbitmq','mnemoscape-minio','mnemoscape-neo4j','mnemoscape-etcd')
if ($Profile -ne 'core') { $expected += 'mnemoscape-milvus' }

while ((Get-Date) -lt $deadline) {
  $allOk = $true
  foreach ($name in $expected) {
    $state = (& docker --config $DockerConfig inspect --format='{{.State.Health.Status}}' $name 2>$null)
    if (-not $state) { $state = 'unknown' }
    if ($state -ne 'healthy') {
      $allOk = $false
    }
  }
  if ($allOk) {
    Write-Host "  all containers healthy"
    break
  }
  Start-Sleep -Seconds 5
}

# --- 6. Final status snapshot ----------------------------------------------
Step "Final container state"
& docker --config $DockerConfig ps --filter "label=com.docker.compose.project=mnemoscape-infra" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

Step "Done. Endpoints (LAN/Tailscale, replace IP with workpc host):"
Write-Host "  MySQL       : 100.66.166.46:3306 (root / root123)"
Write-Host "  Redis       : 100.66.166.46:6379"
Write-Host "  Nacos UI    : http://100.66.166.46:8848/nacos (auth disabled)"
Write-Host "  RabbitMQ UI : http://100.66.166.46:15672 (guest / guest)"
Write-Host "  MinIO UI    : http://100.66.166.46:9001 (minioadmin / minioadmin123)"
Write-Host "  MinIO S3    : http://100.66.166.46:9000"
Write-Host "  Neo4j UI    : http://100.66.166.46:7474 (neo4j / password123)"
Write-Host "  Milvus      : 100.66.166.46:19530"
