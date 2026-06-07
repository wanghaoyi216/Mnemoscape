$procs = @()
Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
    $svc = $null
    if ($cmd -match '(auth|memory|ai|resonance|asset|api-gateway)-service-1') { $svc = $Matches[1] }
    if ($svc) { $procs += [PSCustomObject]@{ Pid = $_.Id; Svc = $svc; Start = $_.StartTime } }
}
$grouped = $procs | Group-Object -Property Svc
foreach ($g in $grouped) {
    $sorted = $g.Group | Sort-Object Start
    $keep = $sorted[0]
    Write-Host "[keep] $($keep.Svc) pid=$($keep.Pid) start=$($keep.Start.ToString('HH:mm:ss'))"
    for ($i = 1; $i -lt $sorted.Count; $i++) {
        $p = $sorted[$i]
        Write-Host "[kill] $($p.Svc) pid=$($p.Pid) start=$($p.Start.ToString('HH:mm:ss'))"
        Stop-Process -Id $p.Pid -Force
    }
}
