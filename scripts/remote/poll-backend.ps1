# =============================================================================
# 轮询后端服务 /actuator/health 直到全部就绪
# 用法：
#   .\poll-backend.ps1
#   .\poll-backend.ps1 -Services auth-service,memory-service,ai-service -TimeoutSec 120
# =============================================================================
[CmdletBinding()]
param(
    [string[]]$Services = @('auth-service','memory-service','ai-service'),
    [int]$TimeoutSec = 120
)

$ports = @{
    'auth-service'      = 8081
    'memory-service'    = 8082
    'ai-service'        = 8083
    'resonance-service' = 8084
    'asset-service'     = 8085
    'api-gateway'       = 8080
}
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    $all = $true
    $rows = @()
    foreach ($s in $Services) {
        $p = $ports[$s]
        $url = "http://localhost:$p/actuator/health"
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) {
                $rows += "{0,-16} :{1,5}  ✓ {2}" -f $s, $p, $r.Content.Substring(0, [Math]::Min(60, $r.Content.Length))
            } else {
                $rows += "{0,-16} :{1,5}  · http=$($r.StatusCode)"; $all = $false
            }
        } catch {
            $rows += "{0,-16} :{1,5}  · {2}" -f $s, $p, $_.Exception.Message.Split([Environment]::NewLine)[0]
            $all = $false
        }
    }
    Clear-Host
    Write-Host "Mnemoscape backend readiness:" -ForegroundColor Cyan
    $rows | ForEach-Object { Write-Host "  $_" }
    if ($all) { Write-Host "`nAll services ready." -ForegroundColor Green; return $true }
    Start-Sleep -Seconds 3
}
Write-Host "`nTimeout after ${TimeoutSec}s. Check logs\*.err" -ForegroundColor Red
return $false
