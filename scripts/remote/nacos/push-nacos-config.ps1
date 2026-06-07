# =============================================================================
# Mnemoscape — 推送本地 YAML 配置到 Nacos Server
#
# 用法（PowerShell 7+）：
#   .\push-nacos-config.ps1
#   .\push-nacos-config.ps1 -NacosHost 100.66.166.46
#   .\push-nacos-config.ps1 -DataId ai-service.yaml -Group DEFAULT_GROUP
#   .\push-nacos-config.ps1 -DryRun       # 只看将要推送什么，不真发
#
# 前提：
#   - Nacos 8848 端口可达（先用 scripts\remote\diagnose-nacos.ps1 排查）
#   - Nacos 鉴权关闭（NACOS_AUTH_ENABLE=false，对应 docker-compose.remote.yml）
# =============================================================================

[CmdletBinding()]
param(
    [string]$NacosHost = "100.66.166.46",
    [int]$NacosPort    = 8848,
    [string]$Group     = "DEFAULT_GROUP",
    [string[]]$DataIds = @(
        "ai-service.yaml", "ai-service-dev.yaml", "ai-service-prod.yaml",
        "memory-service.yaml",
        "auth-service.yaml",
        "resonance-service.yaml",
        "asset-service.yaml",
        "api-gateway.yaml"
    ),
    [string]$ConfigDir = $PSScriptRoot,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# 1) Nacos 健康检查
$healthUrl = "http://${NacosHost}:${NacosPort}/nacos/v1/console/health/readiness"
Write-Host "==> Checking Nacos health at $healthUrl ..." -ForegroundColor Cyan
try {
    $h = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
    Write-Host "    health check: $($h.StatusCode) $($h.Content)" -ForegroundColor Green
} catch {
    Write-Host "    Nacos NOT reachable: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "    Tip: run scripts\remote\diagnose-nacos.ps1 first" -ForegroundColor Yellow
    exit 1
}

# 2) 逐个推送
$pushEndpoint = "http://${NacosHost}:${NacosPort}/nacos/v1/cs/configs"
$success = 0
$failed  = 0
foreach ($dataId in $DataIds) {
    $file = Join-Path $ConfigDir $dataId
    if (-not (Test-Path -LiteralPath $file)) {
        Write-Host "[skip] $dataId — file not found: $file" -ForegroundColor Yellow
        $failed++
        continue
    }
    $content = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    $bytes   = [System.Text.Encoding]::UTF8.GetBytes($content)

    if ($DryRun) {
        Write-Host "[dry-run] would POST $pushEndpoint dataId=$dataId group=$Group (${($bytes.Length)} bytes)" -ForegroundColor DarkCyan
        $success++
        continue
    }

    $form = @{
        dataId  = $dataId
        group   = $Group
        content = $content
        type    = "yaml"
    }
    try {
        $r = Invoke-WebRequest -Uri $pushEndpoint -Method Post -Body $form -UseBasicParsing -TimeoutSec 10
        # Nacos 推送成功返回字面 "true"；错误返回 JSON / 错误码
        $body = ($r.Content | Out-String).Trim()
        if ($r.StatusCode -eq 200 -and $body -eq "true") {
            Write-Host "[ok ] $dataId pushed ($($bytes.Length) bytes)" -ForegroundColor Green
            $success++
        } else {
            Write-Host "[err] $dataId  $body" -ForegroundColor Red
            $failed++
        }
    } catch {
        Write-Host "[err] $dataId  $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

# 3) 总结
Write-Host ""
Write-Host "================ 推送结果 ================" -ForegroundColor Cyan
Write-Host "  成功：$success"  -ForegroundColor Green
Write-Host "  失败：$failed"   -ForegroundColor Red
if ($failed -gt 0) { exit 1 } else { exit 0 }
