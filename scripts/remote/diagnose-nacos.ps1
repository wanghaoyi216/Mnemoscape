#Requires -Version 5.1
<#
.SYNOPSIS
    Mnemoscape - workpc Nacos 一键诊断脚本
.DESCRIPTION
    在工位电脑 (运行 docker-compose.remote.yml 的那台) 上 PowerShell 直接跑:
        cd D:\MnemoscapeInfra        # 或仓库克隆位置
        .\diagnose-nacos.ps1

    脚本会按顺序检查 6 项, 任一失败给出修复建议; 全绿即代表笔记本端
    应该可以直连 100.66.166.46:8848 / 9848 不再报 "Server check fail".

    只读不改, 不会动你的容器和防火墙 (除非加 -ApplyFirewallRule).
.PARAMETER ComposeFile
    docker-compose 文件路径, 默认在脚本同目录找 docker-compose.remote.yml
.PARAMETER ApplyFirewallRule
    带这个开关时会主动添加 Tailscale 子网 (100.64.0.0/10) 入站放行 8848 / 9848.
    不带则只检查不修改防火墙.
#>

[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot 'docker-compose.remote.yml'),
    [switch]$ApplyFirewallRule
)

$ErrorActionPreference = 'Continue'
$script:Failures = @()

function Write-Section($title) {
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkGray
    Write-Host (" $title") -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkGray
}

function Write-Ok($msg)   { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Bad($msg)  { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Failures += $msg }
function Write-Info($msg) { Write-Host "         $msg" -ForegroundColor Gray }

# ---------------------------------------------------------------- 1) Docker 在不在
Write-Section '1/6  Docker 引擎'
$dockerExe = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerExe) {
    Write-Bad 'docker 命令不存在'
    Write-Info '请安装 Docker Desktop 或确认 PATH 配置'
    return
}
try {
    $dockerVersion = docker version --format '{{.Server.Version}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerVersion)) {
        Write-Bad 'Docker daemon 未启动'
        Write-Info '解决: 启动 Docker Desktop, 等任务栏鲸鱼图标变白后重跑本脚本'
        return
    }
    Write-Ok "Docker server $dockerVersion 在线"
} catch {
    Write-Bad "无法访问 docker daemon: $($_.Exception.Message)"
    return
}

# ---------------------------------------------------------------- 2) Nacos 容器状态
Write-Section '2/6  Nacos 容器'
$containerJson = docker ps -a --filter 'name=mnemoscape-nacos' --format '{{json .}}' 2>$null
if ([string]::IsNullOrWhiteSpace($containerJson)) {
    Write-Bad '找不到容器 mnemoscape-nacos (从未创建过)'
    if (Test-Path $ComposeFile) {
        Write-Info "解决: docker compose -f `"$ComposeFile`" up -d nacos"
    } else {
        Write-Info "解决: 在 docker-compose 目录 (含 mnemoscape-nacos 服务的) 跑 'docker compose up -d nacos'"
    }
} else {
    $container = $containerJson | ConvertFrom-Json
    $status = $container.Status
    Write-Info "Status : $status"
    Write-Info "Ports  : $($container.Ports)"
    if ($status -match '^Up' -and $status -notmatch 'unhealthy') {
        Write-Ok "容器在运行"
        if ($status -notmatch 'healthy') {
            Write-Warn '健康检查未通过 (可能仍在 start_period 内)'
        }
    } elseif ($status -match 'unhealthy') {
        Write-Bad "容器 unhealthy, 看末尾 30 行日志:"
        docker logs --tail 30 mnemoscape-nacos 2>&1 | ForEach-Object { Write-Host "         $_" -ForegroundColor DarkGray }
    } elseif ($status -match '^Exited') {
        Write-Bad "容器已停止: $status"
        Write-Info '查日志:  docker logs --tail 100 mnemoscape-nacos'
        Write-Info '重启:    docker start mnemoscape-nacos'
    } else {
        Write-Warn "未知状态: $status"
    }
}

# ---------------------------------------------------------------- 3) 容器内部端口监听
Write-Section '3/6  容器内 8848 / 9848 监听'
$running = (docker ps --filter 'name=mnemoscape-nacos' --filter 'status=running' --format '{{.Names}}' 2>$null)
if (-not $running) {
    Write-Warn '容器没在运行, 跳过容器内端口检查'
} else {
    # Nacos 镜像里没 ss / netstat, 用 /proc/net/tcp 解析
    $procTcp = docker exec mnemoscape-nacos sh -c 'cat /proc/net/tcp 2>/dev/null' 2>$null
    if (-not $procTcp) {
        Write-Warn '读不到 /proc/net/tcp, 跳过'
    } else {
        $listening = @()
        foreach ($line in ($procTcp -split "`n" | Select-Object -Skip 1)) {
            $cols = ($line.Trim() -split '\s+')
            if ($cols.Count -ge 4 -and $cols[3] -eq '0A') {
                $hexPort = ($cols[1] -split ':')[1]
                if ($hexPort) {
                    $port = [Convert]::ToInt32($hexPort, 16)
                    if ($port -in 8848, 9848) { $listening += $port }
                }
            }
        }
        $listening = $listening | Sort-Object -Unique
        foreach ($p in 8848, 9848) {
            if ($listening -contains $p) {
                Write-Ok "容器内 $p LISTEN"
            } else {
                Write-Bad "容器内 $p 没监听"
                if ($p -eq 9848) {
                    Write-Info '9848 缺失通常是 Nacos < 2.x 镜像; 确认 image: nacos/nacos-server:v2.x'
                }
            }
        }
    }
}

# ---------------------------------------------------------------- 4) 宿主机端口映射
Write-Section '4/6  宿主机 8848 / 9848 LISTEN'
$netstat = netstat -ano | Select-String -Pattern '^\s*TCP\s+0\.0\.0\.0:(8848|9848)\s' -AllMatches
foreach ($p in 8848, 9848) {
    $hit = $netstat | Where-Object { $_.Matches[0].Groups[1].Value -eq $p.ToString() }
    if ($hit) {
        Write-Ok "宿主机 0.0.0.0:$p LISTEN"
    } else {
        Write-Bad "宿主机 $p 没绑到 0.0.0.0"
        Write-Info "如果容器内 OK 但宿主机没监听, 检查 docker-compose 的 ports 段: '$p:$p'"
    }
}

# ---------------------------------------------------------------- 5) 本机 HTTP 自测
Write-Section '5/6  本机 HTTP readiness'
try {
    $resp = Invoke-WebRequest -Uri 'http://127.0.0.1:8848/nacos/v1/console/health/readiness' `
                              -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
    if ($resp.StatusCode -eq 200) {
        Write-Ok "127.0.0.1:8848 readiness 200 -> '$($resp.Content.Trim())'"
    } else {
        Write-Warn "readiness HTTP $($resp.StatusCode)"
    }
} catch {
    Write-Bad "本机访问 8848 失败: $($_.Exception.Message)"
    Write-Info '若 4) 显示 LISTEN 但这里失败, 可能 Nacos 还在启动 (等 30s 重试)'
}

# ---------------------------------------------------------------- 6) 防火墙 / Tailscale
Write-Section '6/6  Windows 防火墙 (Tailscale 入站)'
$tailscaleIp = $null
try {
    $tailscaleIp = (& tailscale ip -4 2>$null | Select-Object -First 1)
} catch { }
if ($tailscaleIp) {
    Write-Info "本机 Tailscale IP: $tailscaleIp"
} else {
    Write-Warn '未检测到 tailscale 命令, 无法确认本机 Tailscale IP'
}

$existingRules = Get-NetFirewallRule -DisplayName 'Mnemoscape-Nacos-*' -ErrorAction SilentlyContinue
if ($existingRules) {
    Write-Ok "已有 $($existingRules.Count) 条 Mnemoscape-Nacos-* 防火墙规则"
} else {
    Write-Warn '没找到 Mnemoscape-Nacos-* 入站规则'
    if ($ApplyFirewallRule) {
        try {
            New-NetFirewallRule -DisplayName 'Mnemoscape-Nacos-8848' -Direction Inbound `
                -Protocol TCP -LocalPort 8848 -RemoteAddress 100.64.0.0/10 -Action Allow | Out-Null
            New-NetFirewallRule -DisplayName 'Mnemoscape-Nacos-9848' -Direction Inbound `
                -Protocol TCP -LocalPort 9848 -RemoteAddress 100.64.0.0/10 -Action Allow | Out-Null
            Write-Ok '已添加 8848 / 9848 Tailscale 子网入站放行'
        } catch {
            Write-Bad "添加防火墙规则失败 (需要管理员 PowerShell): $($_.Exception.Message)"
        }
    } else {
        Write-Info '解决: 用管理员 PowerShell 重跑本脚本并加 -ApplyFirewallRule'
        Write-Info '      .\diagnose-nacos.ps1 -ApplyFirewallRule'
    }
}

# ---------------------------------------------------------------- 总结
Write-Section '诊断完成'
if ($script:Failures.Count -eq 0) {
    Write-Host '  全部检查通过. 笔记本端再启动 ai-service, Nacos 报错应该消失.' -ForegroundColor Green
} else {
    Write-Host "  $($script:Failures.Count) 项失败:" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    Write-Host ''
    Write-Host '  按上面 [FAIL] 行的提示逐项修复后重跑.' -ForegroundColor Yellow
}
Write-Host ''
