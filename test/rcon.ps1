param(
    [int]$Port = 25575,
    [string]$Password = "ensematest",
    [string]$Host_ = "127.0.0.1",
    [string]$Command,
    [string]$CommandFile
)
$ErrorActionPreference = "Stop"

if ($CommandFile) {
    $Command = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $CommandFile))
}

function Read-Exact([System.IO.Stream]$s, [byte[]]$buf, [int]$count) {
    $off = 0
    while ($off -lt $count) {
        $n = $s.Read($buf, $off, $count - $off)
        if ($n -le 0) { throw "RCON connection closed" }
        $off += $n
    }
}

function Send-RconPacket([System.IO.Stream]$s, [int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $buf = New-Object byte[] (4 + $len)
    [BitConverter]::GetBytes([int]$len).CopyTo($buf, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($buf, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($buf, 8)
    $bodyBytes.CopyTo($buf, 12)
    $buf[12 + $bodyBytes.Length] = 0
    $buf[13 + $bodyBytes.Length] = 0
    $s.Write($buf, 0, $buf.Length)
}

function Read-RconPacket([System.IO.Stream]$s) {
    $hdr = New-Object byte[] 4
    Read-Exact $s $hdr 4
    $len = [BitConverter]::ToInt32($hdr, 0)
    if ($len -lt 10 -or $len -gt 4194304) { throw "RCON packet length out of range: $len" }
    $body = New-Object byte[] $len
    Read-Exact $s $body $len
    return @{
        Id   = [BitConverter]::ToInt32($body, 0)
        Type = [BitConverter]::ToInt32($body, 4)
        Text = [System.Text.Encoding]::ASCII.GetString($body, 8, $len - 10)
    }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Host_, $Port)
$client.ReceiveTimeout = 1500
$stream = $client.GetStream()

Send-RconPacket $stream 1 3 $Password
$auth = Read-RconPacket $stream
if ($auth.Id -eq -1) {
    $client.Close()
    throw "RCON authentication failed"
}
if ($null -eq $Command) {
    $client.Close()
    exit 0
}

Send-RconPacket $stream 2 2 $Command

$out = New-Object System.Collections.Generic.List[string]
try {
    while ($true) {
        $resp = Read-RconPacket $stream
        if ($resp.Text.Length -eq 0) { break }
        $out.Add($resp.Text)
    }
} catch {
    # ReceiveTimeout: the server is done sending responses for this command.
}
$client.Close()
$out -join "`n"
