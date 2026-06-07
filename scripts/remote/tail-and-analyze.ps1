# =============================================================================
# 后端日志实时分析器 — 持续过滤 + 错误聚类 + 业务事件高亮
# 用法：
#   .\tail-and-analyze.ps1                          # 默认监控 3 个 service
#   .\tail-and-analyze.ps1 -Services auth,memory,ai
#
# 输出分类：
#   [ERR]  ERROR 级别的所有行
#   [WARN] WARN + 特定关键字（PRECONDITION_FAILED / Connection refused / NullPointer）
#   [MQ  ] mq-out / mq-in 标签的发布 / 消费事件
#   [NACO] nacos registry / config 推送 / 实例上下线
#   [BOOT] Spring 启动进度
#   [REST] HTTP 请求（controller 命中）
# =============================================================================
[CmdletBinding()]
param(
    [string[]]$Services = @('auth-service','memory-service','ai-service'),
    [int]$TailLines = 200
)

$backendDir = "M:\Study\ProjectTest\Mnemoscape\backend"
$logDir = "$backendDir\logs"

# 颜色函数
function C($msg, $color) { Write-Host $msg -ForegroundColor $color }

C "=== tail-and-analyze ===" Cyan
C "  services: $($Services -join ', ')" DarkGray
C "  logDir  : $logDir" DarkGray
C "  Ctrl+C to stop`n" DarkGray

# 启动时先 dump 现有 N 行历史
foreach ($svc in $Services) {
    $log = "$logDir\$svc.log"
    if (Test-Path $log) {
        C "--- [history] $svc (last $TailLines lines) ---" Magenta
        Get-Content $log -Tail $TailLines | ForEach-Object { Classify $_ }
    } else {
        C "[skip] $log not found (service not started yet?)" Yellow
    }
}

# 然后实时跟踪（-Wait 像 tail -f）
C "`n--- [live] following new lines ---`n" Magenta
Get-ChildItem $logDir -Filter "*.log" | Where-Object { $_.Name -replace '\.log$' -in $Services } | ForEach-Object {
    $path = $_.FullName
    & {
        Get-Content $path -Wait -Tail 0
    } | ForEach-Object { Classify $_ }
}

function Classify($line) {
    if ($line -match "Started \w+Application in [\d.]+ seconds") {
        C "[BOOT] $line" Green
    } elseif ($line -match "ERROR|FATAL|Exception:") {
        C "[ERR ] $line" Red
    } elseif ($line -match "PRECONDITION_FAILED|Connection refused|NullPointer|UnsupportedOperationException|BeanCreationException") {
        C "[WARN] $line" Yellow
    } elseif ($line -match "\[mq-out\]") {
        C "[MQ  ] $line" Cyan
    } elseif ($line -match "\[mq-in \]") {
        C "[MQ  ] $line" Cyan
    } elseif ($line -match "nacos registry|register finished|NacosDiscovery") {
        C "[NACO] $line" DarkGreen
    } elseif ($line -match "Tomcat started on port|Initializing Servlet") {
        # 静默
    } else {
        Write-Host "       $line"
    }
}
