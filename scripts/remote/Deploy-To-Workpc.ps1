# =============================================================================
# Mnemoscape — Deploy-To-Workpc.ps1   (run from this dev laptop)
# =============================================================================
# One-shot orchestration:
#   1. Verifies ssh workpc reachability
#   2. Creates D:\MnemoscapeInfra on the remote
#   3. Uploads docker-compose + supporting files + remote .ps1 scripts
#   4. Invokes Deploy-Remote.ps1 over SSH
#   5. (Optional) opens Windows firewall via Open-Firewall.ps1
#
# Usage:
#   powershell -File scripts\remote\Deploy-To-Workpc.ps1
#   powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Pull -OpenFirewall
#   powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Profile core
# =============================================================================
[CmdletBinding()]
param(
  [string]$SshHost = 'workpc',
  [ValidateSet('core', 'vector', 'full')]
  [string]$Profile = 'vector',
  [switch]$Pull,
  [switch]$Recreate,
  [switch]$OpenFirewall,
  [switch]$SkipDeploy,
  [switch]$Status,
  [switch]$Stop
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Resolve-Path (Join-Path $ScriptDir '..\..')

function Step($msg) {
  Write-Host ""
  Write-Host "==> $msg" -ForegroundColor Cyan
}

function Sh($cmd) { Write-Host "  $ $cmd" -ForegroundColor DarkGray }

function Invoke-RemoteShell([string]$RemoteCommand) {
  # Default remote shell on workpc is Git Bash — perfect for posix-style
  # paths.  We tunnel a single command line through it.
  Sh "ssh $SshHost $RemoteCommand"
  & ssh $SshHost $RemoteCommand
  if ($LASTEXITCODE -ne 0) {
    throw "remote command failed (exit $LASTEXITCODE): $RemoteCommand"
  }
}

function Invoke-RemotePowerShell([string]$ScriptPath, [string[]]$ScriptArgs = @()) {
  $remoteWinPath = $ScriptPath -replace '\\', '/'
  $argsLiteral   = ($ScriptArgs -join ' ')
  $cmd = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$remoteWinPath`" $argsLiteral"
  Sh "ssh $SshHost $cmd"
  & ssh $SshHost $cmd
  if ($LASTEXITCODE -ne 0) {
    throw "remote PowerShell failed (exit $LASTEXITCODE): $remoteWinPath"
  }
}

function Send-File([string]$LocalPath, [string]$RemotePosixPath) {
  Sh "scp $LocalPath  ${SshHost}:$RemotePosixPath"
  & scp -p -O "$LocalPath" "${SshHost}:$RemotePosixPath"
  if ($LASTEXITCODE -ne 0) {
    # Older OpenSSH builds reject -O; fall back to the SFTP transport.
    & scp -p "$LocalPath" "${SshHost}:$RemotePosixPath"
  }
  if ($LASTEXITCODE -ne 0) {
    throw "scp failed for $LocalPath -> $RemotePosixPath (exit $LASTEXITCODE)"
  }
}

# ---------------------------------------------------------------------------
# 1. Probe SSH
# ---------------------------------------------------------------------------
Step "Probing ssh $SshHost"
$probe = & ssh -o BatchMode=yes -o ConnectTimeout=10 $SshHost 'echo OK && hostname && uname -a'
Write-Host $probe

# ---------------------------------------------------------------------------
# 2. Special modes
# ---------------------------------------------------------------------------
$RemoteDir       = '/d/MnemoscapeInfra'
$RemoteDirWin    = 'D:\MnemoscapeInfra'

if ($Status) {
  Invoke-RemotePowerShell "${RemoteDirWin}\Status-Remote.ps1"
  exit 0
}

if ($Stop) {
  Invoke-RemotePowerShell "${RemoteDirWin}\Stop-Remote.ps1"
  exit 0
}

# ---------------------------------------------------------------------------
# 3. Ensure remote layout
# ---------------------------------------------------------------------------
Step "Creating $RemoteDir on remote"
Invoke-RemoteShell "mkdir -p $RemoteDir/{mysql/init,rabbitmq,minio}"

# ---------------------------------------------------------------------------
# 4. Upload artifacts
# ---------------------------------------------------------------------------
Step "Uploading deployment artifacts"

$payload = @(
  @{ Local = "$ScriptDir\docker-compose.remote.yml"; Remote = "$RemoteDir/docker-compose.remote.yml" }
  @{ Local = "$ScriptDir\Deploy-Remote.ps1";          Remote = "$RemoteDir/Deploy-Remote.ps1" }
  @{ Local = "$ScriptDir\Stop-Remote.ps1";            Remote = "$RemoteDir/Stop-Remote.ps1" }
  @{ Local = "$ScriptDir\Status-Remote.ps1";          Remote = "$RemoteDir/Status-Remote.ps1" }
  @{ Local = "$ScriptDir\Open-Firewall.ps1";          Remote = "$RemoteDir/Open-Firewall.ps1" }

  # Init scripts shipped with the project repo
  @{ Local = "$RepoRoot\docker\mysql\init\01-schema-auth.sql";      Remote = "$RemoteDir/mysql/init/01-schema-auth.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\02-schema-memory.sql";    Remote = "$RemoteDir/mysql/init/02-schema-memory.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\03-schema-resonance.sql"; Remote = "$RemoteDir/mysql/init/03-schema-resonance.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\04-schema-chat.sql";      Remote = "$RemoteDir/mysql/init/04-schema-chat.sql" }
  @{ Local = "$RepoRoot\docker\rabbitmq\definitions.json";          Remote = "$RemoteDir/rabbitmq/definitions.json" }
)

foreach ($p in $payload) {
  if (-not (Test-Path $p.Local)) {
    Write-Host "  [WARN] missing $($p.Local), skipping" -ForegroundColor Yellow
    continue
  }
  Send-File -LocalPath $p.Local -RemotePosixPath $p.Remote
}

# Make sure remote scripts use LF line endings so PowerShell on remote is happy
# (Git Bash on copy doesn't transform them, so they arrive intact.)

# ---------------------------------------------------------------------------
# 5. (Optional) open firewall
# ---------------------------------------------------------------------------
if ($OpenFirewall) {
  Step "Requesting Open-Firewall.ps1 (needs admin on remote)"
  try {
    Invoke-RemotePowerShell "${RemoteDirWin}\Open-Firewall.ps1"
  } catch {
    Write-Host "  [WARN] firewall script failed — most likely because the remote PowerShell session isn't elevated." -ForegroundColor Yellow
    Write-Host "         Open an Administrator PowerShell on workpc and run:"                                          -ForegroundColor Yellow
    Write-Host "             powershell -File $RemoteDirWin\Open-Firewall.ps1"                                         -ForegroundColor Yellow
  }
}

# ---------------------------------------------------------------------------
# 6. Deploy
# ---------------------------------------------------------------------------
if ($SkipDeploy) {
  Step "SkipDeploy specified — artifacts uploaded, nothing started"
  exit 0
}

Step "Triggering Deploy-Remote.ps1 (profile=$Profile)"
$deployArgs = @('-Profile', $Profile)
if ($Pull)     { $deployArgs += '-Pull' }
if ($Recreate) { $deployArgs += '-Recreate' }
Invoke-RemotePowerShell "${RemoteDirWin}\Deploy-Remote.ps1" $deployArgs

Write-Host ""
Write-Host "==> Deployment finished" -ForegroundColor Green
Write-Host "Endpoints (replace IP with workpc Tailscale IP if you changed it):"
Write-Host "  MySQL       100.66.166.46:3306        (root / root123)"
Write-Host "  Redis       100.66.166.46:6379"
Write-Host "  Nacos UI    http://100.66.166.46:8848/nacos"
Write-Host "  RabbitMQ UI http://100.66.166.46:15672 (guest / guest)"
Write-Host "  MinIO UI    http://100.66.166.46:9001  (minioadmin / minioadmin123)"
Write-Host "  Neo4j UI    http://100.66.166.46:7474  (neo4j / password123)"
Write-Host "  Milvus      100.66.166.46:19530"
Write-Host ""
Write-Host "Next: copy backend/.env.local.sample → .env.local and start the local stack." -ForegroundColor Cyan
