# 杀掉指定后端服务（dev 机本地 java 进程）
param([Parameter(Position=0, ValueFromRemainingArguments=$true)][string[]]$Targets)
$ErrorActionPreference = "SilentlyContinue"
Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
    foreach ($t in $Targets) {
        if ($cmd -like "*$t-1.0.0-SNAPSHOT*") {
            Write-Host "killing $t pid=$($_.Id)"
            Stop-Process -Id $_.Id -Force
        }
    }
}
