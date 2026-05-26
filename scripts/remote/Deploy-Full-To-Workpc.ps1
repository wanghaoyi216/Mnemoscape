# =============================================================================
# Mnemoscape — Deploy-Full-To-Workpc.ps1   (run from this dev laptop)
# =============================================================================
# Ultimate zero-effort, full-stack remote orchestrator:
#   1. Compiles all Spring Boot backends locally (fast reactor build).
#   2. Compiles Vue 3 frontend assets locally.
#   3. Packages and archives assets via tar.gz for high-speed upload.
#   4. Re-creates remote service directories on 'workpc' over Tailscale.
#   5. Uploads jars, Dockerfiles, frontend assets, schemas and configurations.
#   6. Extracts frontend archive on the remote machine.
#   7. Triggers docker compose up with BOTH middleware AND services (-Services).
#   8. Writes local frontend .env.development.local to direct hot-reloads.
# =============================================================================
[CmdletBinding()]
param(
  [string]$SshHost = 'workpc',
  [switch]$Pull,
  [switch]$Recreate,
  [switch]$SkipBuild,
  [switch]$OpenFirewall
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
  Sh "scp $LocalPath ${SshHost}:$RemotePosixPath"
  & scp -p -O "$LocalPath" "${SshHost}:$RemotePosixPath"
  if ($LASTEXITCODE -ne 0) {
    & scp -p "$LocalPath" "${SshHost}:$RemotePosixPath"
  }
  if ($LASTEXITCODE -ne 0) {
    throw "scp failed for $LocalPath -> $RemotePosixPath (exit $LASTEXITCODE)"
  }
}

# ---------------------------------------------------------------------------
# 1. Probe SSH
# ---------------------------------------------------------------------------
Step "Probing SSH connectivity to $SshHost (Tailscale)"
$probe = & ssh -o BatchMode=yes -o ConnectTimeout=10 $SshHost 'echo OK && hostname && uname -a'
Write-Host $probe

$RemoteDir       = '/d/MnemoscapeInfra'
$RemoteDirWin    = 'D:\MnemoscapeInfra'

# ---------------------------------------------------------------------------
# 2. Local Compilations
# ---------------------------------------------------------------------------
if (-not $SkipBuild) {
  Step "Compiling Java Backend Microservices (Skip Tests)..."
  Push-Location (Join-Path $RepoRoot "backend")
  & .\mvnw.cmd clean package -DskipTests
  if ($LASTEXITCODE -ne 0) {
    Pop-Location
    throw "Maven reactor compilation failed!"
  }
  Pop-Location
  Write-Host "Java microservices compiled successfully." -ForegroundColor Green

  Step "Compiling Vue 3 Frontend Production Assets..."
  Push-Location (Join-Path $RepoRoot "frontend")
  & npm run build
  if ($LASTEXITCODE -ne 0) {
    Pop-Location
    throw "Frontend production build failed!"
  }
  
  # Archive built frontend dist folder using tar for rapid transmission
  Step "Archiving frontend assets..."
  if (Test-Path "dist.tar.gz") { Remove-Item "dist.tar.gz" -Force }
  & tar -czf dist.tar.gz dist
  Pop-Location
  Write-Host "Frontend compiled and archived successfully." -ForegroundColor Green
} else {
  Step "SkipBuild specified — utilizing existing build artifacts"
}

# ---------------------------------------------------------------------------
# 3. Create Remote Layout
# ---------------------------------------------------------------------------
Step "Re-creating remote structure on $SshHost"
# Create directories for services and their sub-targets
$serviceNames = 'api-gateway', 'auth-service', 'memory-service', 'ai-service', 'resonance-service', 'asset-service', 'frontend'
$mkdirsCmd = "mkdir -p $RemoteDir/{mysql/init,rabbitmq,minio,services,.docker}"
foreach ($svc in $serviceNames) {
  $mkdirsCmd += " $RemoteDir/services/$svc/target"
}
Invoke-RemoteShell $mkdirsCmd

# ---------------------------------------------------------------------------
# 4. Upload Files
# ---------------------------------------------------------------------------
Step "Uploading database schemas and core compose configurations..."
$payload = @(
  @{ Local = "$ScriptDir\docker-compose.remote.yml";          Remote = "$RemoteDir/docker-compose.remote.yml" }
  @{ Local = "$ScriptDir\docker-compose.services.remote.yml"; Remote = "$RemoteDir/docker-compose.services.remote.yml" }
  @{ Local = "$ScriptDir\Deploy-Remote.ps1";                  Remote = "$RemoteDir/Deploy-Remote.ps1" }
  @{ Local = "$ScriptDir\Stop-Remote.ps1";                    Remote = "$RemoteDir/Stop-Remote.ps1" }
  @{ Local = "$ScriptDir\Status-Remote.ps1";                  Remote = "$RemoteDir/Status-Remote.ps1" }
  @{ Local = "$ScriptDir\Open-Firewall.ps1";                  Remote = "$RemoteDir/Open-Firewall.ps1" }
  @{ Local = "$ScriptDir\config.json";                        Remote = "$RemoteDir/.docker/config.json" }

  # Init schemas
  @{ Local = "$RepoRoot\docker\mysql\init\01-schema-auth.sql";      Remote = "$RemoteDir/mysql/init/01-schema-auth.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\02-schema-memory.sql";    Remote = "$RemoteDir/mysql/init/02-schema-memory.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\03-schema-resonance.sql"; Remote = "$RemoteDir/mysql/init/03-schema-resonance.sql" }
  @{ Local = "$RepoRoot\docker\mysql\init\04-schema-chat.sql";      Remote = "$RemoteDir/mysql/init/04-schema-chat.sql" }
  @{ Local = "$RepoRoot\docker\rabbitmq\definitions.json";          Remote = "$RemoteDir/rabbitmq/definitions.json" }
)

foreach ($p in $payload) {
  if (Test-Path $p.Local) {
    Send-File -LocalPath $p.Local -RemotePosixPath $p.Remote
  }
}

Step "Uploading Backend Service fat-jars and Dockerfiles..."
foreach ($svc in $serviceNames) {
  if ($svc -eq 'frontend') { continue }
  
  $svcDir = Join-Path (Join-Path $RepoRoot "backend") $svc
  $dockerfile = Join-Path $svcDir "Dockerfile"
  
  # Find compiled fat jar in the target directory
  $jarPath = Get-ChildItem (Join-Path $svcDir "target") -Filter "*.jar" | 
             Where-Object { $_.Name -notlike "*sources*" -and $_.Name -notlike "*javadoc*" } | 
             Select-Object -First 1
             
  if ($null -eq $jarPath) {
    throw "Could not find compiled jar for service: $svc"
  }
  
  Write-Host "  Uploading $svc artifacts..." -ForegroundColor Gray
  Send-File -LocalPath $dockerfile -RemotePosixPath "$RemoteDir/services/$svc/Dockerfile"
  Send-File -LocalPath $jarPath.FullName -RemotePosixPath "$RemoteDir/services/$svc/target/app.jar"
}

Step "Uploading Frontend assets archive..."
$frontendDir = Join-Path $RepoRoot "frontend"
$archiveLocal = Join-Path $frontendDir "dist.tar.gz"
$nginxConfLocal = Join-Path $frontendDir "nginx.conf"
$dockerfileRemoteLocal = Join-Path $frontendDir "Dockerfile.remote"

Send-File -LocalPath $archiveLocal -RemotePosixPath "$RemoteDir/services/frontend/dist.tar.gz"
Send-File -LocalPath $nginxConfLocal -RemotePosixPath "$RemoteDir/services/frontend/nginx.conf"
Send-File -LocalPath $dockerfileRemoteLocal -RemotePosixPath "$RemoteDir/services/frontend/Dockerfile"

# Extract archive remotely and clean up
Step "Extracting frontend assets on remote host..."
Invoke-RemoteShell "cd $RemoteDir/services/frontend && tar -xzf dist.tar.gz && rm -f dist.tar.gz"

# ---------------------------------------------------------------------------
# 5. Remote Deploy Execution
# ---------------------------------------------------------------------------
Step "Triggering Remote Deployment containing Middleware and Services..."
$deployArgs = @('-Services')
if ($Pull)     { $deployArgs += '-Pull' }
if ($Recreate) { $deployArgs += '-Recreate' }

Invoke-RemotePowerShell "${RemoteDirWin}\Deploy-Remote.ps1" $deployArgs

# ---------------------------------------------------------------------------
# 6. Local Frontend Hot-reload Config (Self-Healing Dev Support)
# ---------------------------------------------------------------------------
Step "Creating local environment config for seamless laptop hot-reloads..."
$envLocalPath = Join-Path $frontendDir ".env.development.local"
"VITE_BACKEND_HOST=100.66.166.46" | Out-File -FilePath $envLocalPath -Encoding utf8 -Force
Write-Host "  Wrote $envLocalPath pointing VITE_BACKEND_HOST to 100.66.166.46" -ForegroundColor Gray

# ---------------------------------------------------------------------------
# 7. Complete Walkthrough Output
# ---------------------------------------------------------------------------
Step "Full remote stack is online and self-healing!" -ForegroundColor Green
Write-Host "Endpoints (Use LAN/Tailscale IP: 100.66.166.46):"
Write-Host "  ★ Frontend App : http://100.66.166.46/         (Direct Web Access - No Laptop CPU!)" -ForegroundColor Green
Write-Host "  ★ Frontend Alt : http://100.66.166.46:5173/    (Exposed Port Alternate)"
Write-Host "  Nacos UI       : http://100.66.166.46:8848/nacos"
Write-Host "  RabbitMQ UI    : http://100.66.166.46:15672"
Write-Host "  MinIO Console  : http://100.66.166.46:9001"
Write-Host "  Neo4j Console  : http://100.66.166.46:7474"
Write-Host ""
Write-Host "If you prefer local UI hot-reloading for code editing:" -ForegroundColor Cyan
Write-Host "  1. Run 'npm run dev' inside the frontend folder on this laptop."
Write-Host "  2. Go to http://localhost:5173/ - it is dynamically proxied directly to the remote workpc!"
Write-Host "  3. No local Java, Maven or Databases required!"
Write-Host ""
