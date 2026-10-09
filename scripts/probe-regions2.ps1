$Root = Split-Path -Parent $PSScriptRoot
$Java = 'C:\Program Files\Java\jdk-25\bin\java.exe'
$Wh  = Join-Path $Root 'test\world-host'
try { Get-NetTCPConnection -LocalPort 25567, 25575 -State Listen -ErrorAction Stop | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } } catch { }
Remove-Item (Join-Path $Wh 'logs\latest.log') -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $Java -ArgumentList @('-Xmx2G', '-Denigma.distributed.enabled=true',
    '-Denigma.distributed.role=WORLD_HOST', '-Denigma.distributed.autoMigrate=false',
    '-jar', 'EnigmaEngine.jar', 'nogui') -WorkingDirectory $Wh `
    -RedirectStandardOutput "$env:TEMP\probe.out" -RedirectStandardError "$env:TEMP\probe.err" -PassThru
$dl = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $dl) {
    if ((Get-Content (Join-Path $Wh 'logs\latest.log') -Raw -ErrorAction SilentlyContinue) -match 'Done \(') { break }
    Start-Sleep -Milliseconds 400
}
'=== boot done ==='

function Read-Exact($stream, [int]$n) {
    $buf = New-Object byte[] $n; $off = 0
    while ($off -lt $n) { $r = $stream.Read($buf, $off, $n - $off); if ($r -le 0) { break }; $off += $r }
    if ($off -eq $n) { return ,$buf }
    $cut = New-Object byte[] $off; [Array]::Copy($buf, $cut, $off); return ,$cut
}
function Invoke-Rcon($client, [string]$cmd) {
    $reqId = $client.Seq; $client.Seq++
    $payload = [System.Text.Encoding]::UTF8.GetBytes($cmd) + [byte[]](0, 0)
    $body = [byte[]]([System.BitConverter]::GetBytes([int]$reqId) + [System.BitConverter]::GetBytes([int]2) + $payload)
    $len = [System.BitConverter]::GetBytes([int]$body.Length)
    $proc = $len + $body
    $client.Stream.Write($proc, 0, $proc.Length); $client.Stream.Flush()
    $sb = New-Object System.Text.StringBuilder
    $deadline = (Get-Date).AddSeconds(8)
    while ($true) {
        if ((Get-Date) -gt $deadline) { break }
        try { $rb = Read-Exact $client.Stream 4 } catch { break }
        if ($rb.Length -lt 4) { break }
        $rl = [System.BitConverter]::ToInt32($rb, 0)
        if ($rl -lt 4 -or $rl -gt 131072) { break }
        try { $body = Read-Exact $client.Stream $rl } catch { break }
        if ($body.Length -lt 8) { break }
        $typ = [System.BitConverter]::ToInt32($body, 4)
        if ($rl -gt 8) {
            $d = New-Object byte[] ($rl - 8); [Array]::Copy($body, 8, $d, 0, $rl - 8)
            $i = $d.Length - 1
            while ($i -ge 0 -and $d[$i] -eq 0) { $i-- }
            if ($i -ge 0) { [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($d, 0, $i + 1)); $sb.Append([char]10) | Out-Null }
        }
        if ($typ -eq 0) { break }
    }
    return $sb.ToString()
}
function Clean([string]$s) {
    if (-not $s) { return '' }
    $s = $s -replace ([string][char]0x1B) + '\[[0-9;]*m', ''
    $s = $s -replace ([string][char]0x00A7) + '[0-9a-fk-orxA-FK-ORx]', ''
    $s = $s -replace ([string][char]0x00A7) + '[0-9a-fk-orxA-FK-ORx;]{0,4}', ''
    $s = $s -replace ([string][char]0x00A7) + '.', ''
    $s = $s -replace ([string][char]0x1B) + '.', ''
    return $s
}

$tcp = New-Object System.Net.Sockets.TcpClient
$cl = $null
$dl = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $dl) {
    try { $tcp.Connect('127.0.0.1', 25575); $cl = [pscustomobject]@{ Tcp = $tcp; Stream = $tcp.GetStream(); Seq = 2 }; break } catch { Start-Sleep -Seconds 2 }
}
if (-not $cl) { 'NO RCON'; exit 1 }
$pw = [System.Text.Encoding]::ASCII.GetBytes('ensematest') + [byte[]](0, 0)
$body = [byte[]]([System.BitConverter]::GetBytes([int]2) + [System.BitConverter]::GetBytes([int]3) + $pw)
$len = [System.BitConverter]::GetBytes([int]$body.Length)
$pkt = $len + $body
$cl.Stream.Write($pkt, 0, $pkt.Length); $cl.Stream.Flush()
$rb = Read-Exact $cl.Stream 4
$rl = [System.BitConverter]::ToInt32($rb, 0)
$null = Read-Exact $cl.Stream $rl

'--- enigma regionizer regions (fork-level truth) ---'
Write-Output (Clean (Invoke-Rcon $cl 'enigma regionizer regions'))
Start-Sleep -Seconds 2
'--- enigma distributed regions ---'
Write-Output (Clean (Invoke-Rcon $cl 'enigma distributed regions'))
'--- enigma regionizer info (no arg lists?) keep status ---'
$st = Clean (Invoke-Rcon $cl 'enigma distributed status')
($st -split "`n") | Where-Object { $_ -match 'Active regions|Handed over' }

Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue