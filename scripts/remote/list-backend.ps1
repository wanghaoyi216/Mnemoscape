Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
    $svc = "?"
    if ($cmd -match '(auth|memory|ai|resonance|asset|api-gateway)-service-1') { $svc = $Matches[1] }
    Write-Host "pid=$($_.Id.ToString().PadLeft(5))  start=$($_.StartTime.ToString('HH:mm:ss'))  svc=$svc"
}
