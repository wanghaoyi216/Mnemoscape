8081,8082,8083 | ForEach-Object {
    $port = $_
    $c = New-Object System.Net.Sockets.TcpClient
    $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null)
    if ($iar.AsyncWaitHandle.WaitOne(2000)) {
        $c.EndConnect($iar)
        Write-Host "  port $port : UP"
    } else {
        Write-Host "  port $port : down"
    }
    $c.Close()
}
