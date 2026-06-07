# =============================================================================
# Mnemoscape — workpc 端查看中间件状态
# 用法：.\status-remote-infra.ps1
# =============================================================================
[CmdletBinding()] param()

function Test-Port {
    param([string]$Host, [int]$Port, [int]$TimeoutSec = 2)
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $tcp.BeginConnect($Host, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutSec * 1000)
        if ($ok) { $tcp.EndConnect($iar); return $true }
        return $false
    } catch { return $false } finally { $tcp.Close() }
}

$services = @(
    @{ Name = "MySQL";     Port = 3306 },
    @{ Name = "Redis";     Port = 6379 },
    @{ Name = "Nacos";     Port = 8848 },
    @{ Name = "Nacos-gRPC";Port = 9848 },
    @{ Name = "RabbitMQ";  Port = 5672 },
    @{ Name = "RabbitMQ-UI";Port = 15672 },
    @{ Name = "MinIO";     Port = 9000 },
    @{ Name = "MinIO-UI";  Port = 9001 },
    @{ Name = "Neo4j";     Port = 7687 },
    @{ Name = "Neo4j-UI";  Port = 7474 },
    @{ Name = "Milvus";    Port = 19530 },
    @{ Name = "Milvus-UI"; Port = 9091 }
)
Write-Host "Mnemoscape infra status:" -ForegroundColor Cyan
foreach ($s in $services) {
    $ok = Test-Port -Host localhost -Port $s.Port
    $mark = if ($ok) { "✓" } else { "·" }
    $color = if ($ok) { "Green" } else { "DarkGray" }
    Write-Host ("  {0,-14} :{1,5} {2}" -f $s.Name, $s.Port, $mark) -ForegroundColor $color
}

# Docker 容器列表
Write-Host ""
Write-Host "Docker containers:" -ForegroundColor Cyan
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" --filter "name=mnemoscape-"
