# 加载 backend\.env.workpc 到当前进程环境变量
$ErrorActionPreference = "Stop"
$envFile = Join-Path $PSScriptRoot "..\..\backend\.env.workpc"
if (-not (Test-Path $envFile)) { throw "Missing $envFile" }
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $name = $Matches[1].Trim()
        $val  = $Matches[2].Trim()
        [Environment]::SetEnvironmentVariable($name, $val, 'Process')
        Write-Host "  $name = $val"
    }
}
Write-Host "`n[env] loaded $($envFile)"
