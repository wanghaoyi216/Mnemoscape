param(
    [string]$DeployRoot = "M:\MnemoscapeInfra"
)

$ErrorActionPreference = "Continue"

Write-Host "== host =="
hostname
whoami

Write-Host "== powershell =="
$PSVersionTable.PSVersion.ToString()

Write-Host "== drives =="
Get-PSDrive -PSProvider FileSystem |
    Select-Object Name, Root, Used, Free |
    Format-Table -AutoSize

Write-Host "== docker =="
docker --version
docker compose version
docker context ls
docker system df

Write-Host "== docker desktop storage hints =="
$paths = @(
    "$env:LOCALAPPDATA\Docker",
    "$env:LOCALAPPDATA\Docker\wsl",
    "$env:LOCALAPPDATA\Docker\wsl\data",
    "$env:APPDATA\Docker",
    "$env:PROGRAMDATA\DockerDesktop",
    "$env:PROGRAMDATA\Docker"
)

foreach ($path in $paths) {
    if (Test-Path -LiteralPath $path) {
        Write-Host "EXISTS $path"
        Get-ChildItem -LiteralPath $path -Force |
            Select-Object Mode, Length, LastWriteTime, FullName |
            Format-Table -AutoSize
    }
    else {
        Write-Host "MISSING $path"
    }
}

Write-Host "== deploy root =="
if (Test-Path -LiteralPath $DeployRoot) {
    Get-Item -LiteralPath $DeployRoot | Format-List FullName, LastWriteTime
    Get-ChildItem -LiteralPath $DeployRoot -Force |
        Select-Object Mode, Length, LastWriteTime, FullName |
        Format-Table -AutoSize
}
else {
    Write-Host "MISSING $DeployRoot"
}

Write-Host "== containers =="
docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
