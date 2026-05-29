# Loads backend/.env.workpc and starts a single service via Maven Wrapper.
# Used by background-process launchers so each service inherits the workpc
# middleware addresses without having to spawn an interactive shell first.
param([Parameter(Mandatory=$true)][string]$Service)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root 'backend\.env.workpc'

Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*$' -or $_ -match '^\s*#') { return }
  if ($_ -match '^\s*([^=]+?)\s*=\s*(.*?)\s*$') {
    $k = $Matches[1].Trim()
    $v = $Matches[2].Trim()
    if (-not [string]::IsNullOrWhiteSpace($v)) {
      [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    }
  }
}

$env:MAVEN_OPTS = '-Xmx1024m'

Set-Location (Join-Path $root 'backend')
Write-Host "[$Service] starting at $(Get-Date -Format HH:mm:ss)" -ForegroundColor Cyan
& .\mvnw.cmd -pl $Service spring-boot:run
