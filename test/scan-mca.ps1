param(
    [Parameter(Mandatory = $true)][string]$Mca,
    [Parameter(Mandatory = $true)][string]$Pattern
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression

$fs = New-Object System.IO.FileStream((Resolve-Path -LiteralPath $Mca), [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$len = $fs.Length
$sector = New-Object byte[] 4096
$offsetTableStart = 0
$single = [System.Text.Encoding]::UTF8.GetBytes($Pattern)
$found = New-Object System.Collections.Generic.List[string]

for ($i = 0; $i -lt 1024; $i++) {
    $fs.Position = $offsetTableStart + $i * 4
    $raw = New-Object byte[] 4
    $fs.Read($raw, 0, 4) | Out-Null
    $packed = ($raw[0] -shl 24) -bor ($raw[1] -shl 16) -bor ($raw[2] -shl 8) -bor $raw[3]
    $loc = [math]::Floor($packed / 256)
    $count = $packed -band 0xFF
    if ($loc -eq 0 -or $count -eq 0) { continue }

    $fs.Position = $loc * 4096
    $hdr = New-Object byte[] 5
    $fs.Read($hdr, 0, 5) | Out-Null
    $compressedLen = ([int]$hdr[0] -shl 24) -bor ([int]$hdr[1] -shl 16) -bor ([int]$hdr[2] -shl 8) -bor [int]$hdr[3]
    $compression = [int]$hdr[4]
    $payload = New-Object byte[] $compressedLen
    $fs.Read($payload, 0, $compressedLen) | Out-Null

    $data = $null
    if ($compression -eq 2) { # zlib
        $ms = New-Object System.IO.MemoryStream
        $out = New-Object System.IO.Compression.ZLibStream([System.IO.MemoryStream]::new($payload), [System.IO.Compression.CompressionMode]::Decompress)
        $out.CopyTo($ms); $out.Dispose()
        $data = $ms.ToArray(); $ms.Dispose()
    } elseif ($compression -eq 1) { # gzip
        $ms = New-Object System.IO.MemoryStream
        $gz = New-Object System.IO.Compression.GZipStream([System.IO.MemoryStream]::new($payload), [System.IO.Compression.CompressionMode]::Decompress)
        $gz.CopyTo($ms); $gz.Dispose()
        $data = $ms.ToArray(); $ms.Dispose()
    } elseif ($compression -eq 3) { # uncompressed
        $data = $payload
    } else {
        $found.Add("chunk $($i/32),$($i%32): unsupported compression type $compression")
        continue
    }

    $s = [System.Text.Encoding]::UTF8.GetString($data)
    if ($s.IndexOf($Pattern, [System.StringComparison]::Ordinal) -ge 0) {
        $cx = $i % 32
        $cz = [math]::Floor($i / 32)
        $found.Add("MATCH in chunk ($cx,$cz) len=$($data.Length)")
    }
}
$fs.Dispose()
if ($found.Count -eq 0) { "no match for '$Pattern' in $(Split-Path $Mca -Leaf)" }
else { $found | ForEach-Object { $_ } }