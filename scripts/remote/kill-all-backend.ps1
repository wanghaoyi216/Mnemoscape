# 杀掉所有 mnemoscape 后端 java 进程
Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
    if ($cmd -match '(auth|memory|ai|resonance|asset|api-gateway)-service-1') {
        Write-Host "kill pid=$($_.Id) svc=$($Matches[1])"
        Stop-Process -Id $_.Id -Force
    }
}
