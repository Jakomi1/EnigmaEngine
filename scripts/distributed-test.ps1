# EnigmaEngine distributed runtime test harness.
# Drives the local test servers (world-host + compute-host) over RCON.
# Deterministic entity guard via FRESH flat world + forceload materialisation:
#   S0  boot + connect + doMobSpawning off, then forceload a far chunk grid so
#       the regionizer creates a real (entity-free) region despite the fresh
#       world having no spawn sections
#   S1  entity guard: 3 tagged "fake players" (armor stands) block migration
#   S2  kill the fake players -> entity count 0 -> successful migration
#       (verify / release / install / local ticking / forwarded / status)
#   S3  prepare-stop returns the region, WH halts
#   S4  regression scan for fenced-write crash signatures
# Run:  powershell -ExecutionPolicy Bypass -File scripts\distributed-test.ps1

$ErrorActionPreference = 'Continue'
Set-StrictMode -Off

$Root   = Split-Path -Parent $PSScriptRoot
$Java   = 'C:\Program Files\Java\jdk-25\bin\java.exe'
$WhDir  = Join-Path $Root 'test\world-host'
$ChDir  = Join-Path $Root 'test\compute-host'
$Art    = Join-Path $env:TEMP 'opencode\DistTest'
$null = New-Item -ItemType Directory -Force -Path $Art | Out-Null

$Wh = @{ Name='WH'; Dir=$WhDir; Log=Join-Path $WhDir 'logs\latest.log'; Out=Join-Path $Art 'wh.out'; Err=Join-Path $Art 'wh.err'; RconPort=25575; Proc=$null }
$Ch = @{ Name='CH'; Dir=$ChDir; Log=Join-Path $ChDir 'logs\latest.log'; Out=Join-Path $Art 'ch.out';  Err=Join-Path $Art 'ch.err';  RconPort=25576; Proc=$null }

$script:Checks = New-Object System.Collections.Generic.List[object]

function Add-Check([string]$name, $ok, [string]$detail) {
    $script:Checks.Add([pscustomobject]@{ Name=$name; Ok=[bool]$ok; Detail=$detail })
}

function Clean-Txt([string]$s) {
    if ($null -eq $s) { return '' }
    $s = $s -replace ([string][char]0x1B) + '\[[0-9;]*m', ''
    $s = $s -replace ([string][char]0x00A7) + '[0-9a-fk-orxA-FK-ORx]', ''
    $s = $s -replace ([string][char]0x00A7) + '[0-9a-fk-orxA-FK-ORx;]{0,4}', ''
    $s = $s -replace ([string][char]0x00A7) + '.', ''
    $s = $s -replace ([string][char]0x1B) + '.', ''
    return $s
}

function Save-Txt([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

function Start-Target([hashtable]$srv, [string[]]$argsList) {
    Remove-Item (Join-Path $srv.Dir 'logs\latest.log') -Force -ErrorAction SilentlyContinue
    Remove-Item $srv.Out -Force -ErrorAction SilentlyContinue
    Remove-Item $srv.Err -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $Java -ArgumentList $argsList `
        -WorkingDirectory $srv.Dir `
        -RedirectStandardOutput $srv.Out -RedirectStandardError $srv.Err -PassThru
    $srv.Proc = $p
    Start-Sleep -Seconds 2
    return $p
}

function Read-Log([hashtable]$srv) {
    if (-not (Test-Path $srv.Log)) { return '' }
    try { return (Get-Content $srv.Log -Raw -ErrorAction Stop) } catch { return '' }
}

function Wait-Log([hashtable]$srv, [string]$pattern, [int]$timeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $rx = New-Object System.Text.RegularExpressions.Regex($pattern, 'IgnoreCase')
    while ((Get-Date) -lt $deadline) {
        $txt = Read-Log $srv
        if ($txt) {
            $m = $rx.Match($txt)
            if ($m.Success) { return $m.Value }
        }
        Start-Sleep -Milliseconds 400
    }
    return $null
}

function Stop-Server([hashtable]$srv) {
    if ($srv.Proc -and -not $srv.Proc.HasExited) {
        Stop-Process -Id $srv.Proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

function Read-Exact($stream, [int]$n) {
    $buf = New-Object byte[] $n
    $off = 0
    while ($off -lt $n) {
        $r = $stream.Read($buf, $off, $n - $off)
        if ($r -le 0) { break }
        $off += $r
    }
    if ($off -eq $n) { return ,$buf }
    $cut = New-Object byte[] $off
    [Array]::Copy($buf, $cut, $off)
    return ,$cut
}

function New-Rcon([string]$chost, [int]$cport, [string]$pw) {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $ar = $tcp.BeginConnect($chost, $cport, $null, $null)
    if (-not $ar.AsyncWaitHandle.WaitOne(5000)) { $tcp.Close(); return $null }
    try { $tcp.EndConnect($ar) } catch { $tcp.Close(); return $null }
    $st = $tcp.GetStream()
    $st.ReadTimeout = 8000
    $client = [pscustomobject]@{ Tcp=$tcp; Stream=$st; Seq=[int]2; LoggedIn=$false }
    $reqId = $client.Seq; $client.Seq++
    $payload = [System.Text.Encoding]::ASCII.GetBytes($pw) + [byte[]](0, 0)
    $body = [byte[]]([System.BitConverter]::GetBytes([int]$reqId) + [System.BitConverter]::GetBytes([int]3) + $payload)
    $len = [System.BitConverter]::GetBytes([int]$body.Length)
    $pkt = $len + $body
    try {
        $st.Write($pkt, 0, $pkt.Length); $st.Flush()
        $rb = Read-Exact $st 4
        if ($rb.Length -lt 4) { $tcp.Close(); return $null }
        $rl = [System.BitConverter]::ToInt32($rb, 0)
        $rb2 = Read-Exact $st $rl
        if ($rb2.Length -ge 8) {
            $rid = [System.BitConverter]::ToInt32($rb2, 0)
            $typ = [System.BitConverter]::ToInt32($rb2, 4)
            if ($rid -eq $reqId -and $typ -eq 2) { $client.LoggedIn = $true }
        }
    } catch { }
    if (-not $client.LoggedIn) { $tcp.Close(); return $null }
    return $client
}

function Invoke-Rcon($client, [string]$cmd) {
    if (-not $client) { return '(no rcon)' }
    $reqId = $client.Seq; $client.Seq++
    $payload = [System.Text.Encoding]::UTF8.GetBytes($cmd) + [byte[]](0, 0)
    $body = [byte[]]([System.BitConverter]::GetBytes([int]$reqId) + [System.BitConverter]::GetBytes([int]2) + $payload)
    $len = [System.BitConverter]::GetBytes([int]$body.Length)
    $pkt = $len + $body
    try { $client.Stream.Write($pkt, 0, $pkt.Length); $client.Stream.Flush() }
    catch { return '(rcon write failed)' }
    $sb = New-Object System.Text.StringBuilder
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
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
            $d = New-Object byte[] ($rl - 8)
            [Array]::Copy($body, 8, $d, 0, $rl - 8)
            $i = $d.Length - 1
            while ($i -ge 0 -and $d[$i] -eq 0) { $i-- }
            if ($i -ge 0) {
                [void]$sb.Append($latin1.GetString($d, 0, $i + 1))
                $sb.Append([char]10) | Out-Null
            }
        }
        if ($typ -eq 0) { break }
    }
    return $sb.ToString()
}

function Get-RegionRows([string]$txt) {
    $rows = @()
    foreach ($line in ($txt -split "`n")) {
        if ($line -match '#(\d+) \(id=(\d+)\)') {
            $num = [int]$Matches[1]; $id = [long]$Matches[2]
            $chunks = 0L
            $clean = Clean-Txt $line
            if ($clean -match '([\d.,]+) chunks') { $chunks = [long](($Matches[1] -replace '[^0-9]', '')) }
            $forwarded = ($clean -match 'Compute Host') -or ($clean -match '\u2192')
            $rows += [pscustomobject]@{ Num=$num; Id=$id; Chunks=$chunks; Forwarded=$forwarded; Line=$clean }
        }
    }
    return $rows
}

function EntitiesOf([string]$infoTxt) {
    $t = Clean-Txt $infoTxt
    if ($t -match 'Entities\D*(\d+)') { return [int]$Matches[1] }
    return -1
}

function Wait-EntitiesLe([hashtable]$srv2, $rcon, [int]$regionNum, [int]$max, [int]$timeoutSec = 45) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $e = EntitiesOf (Invoke-Rcon $rcon "enigma distributed info $regionNum")
        if ($e -le $max) { return $e }
        Start-Sleep -Seconds 3
    }
    return (EntitiesOf (Invoke-Rcon $rcon "enigma distributed info $regionNum"))
}

# ---------------------------------------------------------------- preflight
Write-Output '== preflight =='
# Robust vs stale test servers (also catches ones left behind by a killed
# harness that no longer holds their MC/RCON ports).
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'EnigmaEngine\.jar' } |
    ForEach-Object { Write-Output ("  stopping stale server pid {0}" -f $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($prt in 4001, 4002, 25567, 25575, 25576, 25577) {
    try {
        $c = Get-NetTCPConnection -LocalPort $prt -State Listen -ErrorAction Stop
        foreach ($conn in $c) {
            Write-Output ("  stopping leftover pid {0} on port {1}" -f $conn.OwningProcess, $prt)
            Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    } catch { }
}
foreach ($srv in @($Wh, $Ch)) {
    if (-not (Test-Path (Join-Path $srv.Dir 'EnigmaEngine.jar'))) { Write-Output 'ERROR: missing jar'; exit 1 }
    Set-Content -Path (Join-Path $srv.Dir 'eula.txt') -Value 'eula=true' -Force
    # A valid flat preset is required: empty generator-settings={} makes fresh
    # world generation fail in this dev build ("No key layers in MapLike[{}]").
    $propsPath = Join-Path $srv.Dir 'server.properties'
    $l1 = '{"block":"minecraft:bedrock","height":1}'
    $l2 = '{"block":"minecraft:dirt","height":2}'
    $l3 = '{"block":"minecraft:grass_block","height":1}'
    $flatSettings = '{"layers":[' + $l1 + ',' + $l2 + ',' + $l3 + '],"biome":"minecraft:plains","structures":{"structures":{}}}'
    $props = [System.IO.File]::ReadAllText($propsPath)
    if ($props -match '(?m)^generator-settings=.*$') {
        $props = $props -replace '(?m)^generator-settings=.*$', ('generator-settings=' + $flatSettings)
    } else {
        $props += "`ngenerator-settings=" + $flatSettings
    }
    [System.IO.File]::WriteAllText($propsPath, $props, (New-Object System.Text.UTF8Encoding($false)))
}
$jarHash = (Get-FileHash (Join-Path $Root 'serverJar\EnigmaEngine.jar')).Hash
$whHash  = (Get-FileHash (Join-Path $Wh.Dir 'EnigmaEngine.jar')).Hash
$chHash  = (Get-FileHash (Join-Path $Ch.Dir 'EnigmaEngine.jar')).Hash
Add-Check 'servers run same jar as serverJar' ($jarHash -eq $whHash -and $jarHash -eq $chHash)
    ('wh {0} / ch {1}' -f ($(if($whHash -eq $jarHash){'ok'}else{'DIFF'}), $(if($chHash -eq $jarHash){'ok'}else{'DIFF'})))

# ---------------------------------------------------------------- S0: boot + region materialisation
Write-Output '== S0: boot + region materialisation =='

$null = Start-Target $Wh @('-Xmx1G', '-XX:ActiveProcessorCount=6', '-Denigma.distributed.enabled=true',
    '-Denigma.distributed.role=WORLD_HOST', '-Denigma.distributed.autoMigrate=false',
    '-Denigma.distributed.debugLogging=true', '-jar', 'EnigmaEngine.jar', 'nogui')
Add-Check 'WH booted' (Wait-Log $Wh 'Done \(' 180)
Add-Check 'WH distributed started' (Wait-Log $Wh 'Starting EnigmaEngine as WORLD_HOST' 60)
Add-Check 'WH distributed listener' (Wait-Log $Wh 'listening on' 30)
$whRcon = New-Rcon '127.0.0.1' $Wh.RconPort 'ensematest'
Add-Check 'WH rcon auth' ($null -ne $whRcon)
$null = Invoke-Rcon $whRcon 'gamerule doMobSpawning false'

$null = Start-Target $Ch @('-Xmx1G', '-XX:ActiveProcessorCount=6', '-Denigma.distributed.enabled=true',
    '-Denigma.distributed.role=COMPUTE_HOST', '-Denigma.distributed.worldHost=127.0.0.1',
    '-Denigma.distributed.debugLogging=true', '-jar', 'EnigmaEngine.jar', 'nogui')
Add-Check 'CH booted' (Wait-Log $Ch 'Done \(' 180)
$registered = Wait-Log $Wh 'Compute Host -?\d+ registered from' 90
Add-Check 'CH connected (WH registered)' ($null -ne $registered) $registered
$ready = Wait-Log $Wh 'Compute Host -?\d+ is ready to receive migrations' 60
Add-Check 'CH ready for migrations' ($null -ne $ready) $ready
$chRcon = New-Rcon '127.0.0.1' $Ch.RconPort 'ensematest'
Add-Check 'CH rcon auth' ($null -ne $chRcon)

# A FRESH world has no sections on the regionizer at boot (spawn area is
# generated but not added via regionizer.addChunk). The FIRST added chunk
# merges all pending spawn sections into region id=0 (the spawn region, never
# migratable). Force-loading a SECOND, far-away chunk grid creates a distinct
# non-spawn region (id>0) that we can migrate.
$null = Invoke-Rcon $whRcon 'execute in minecraft:overworld run forceload add 100 100'
foreach ($cell in @('3000 3000', '3002 3000', '3000 3002')) {
    $null = Invoke-Rcon $whRcon "execute in minecraft:overworld run forceload add $cell"
}
Start-Sleep -Seconds 6
$regs = Invoke-Rcon $whRcon 'enigma distributed regions'
Save-Txt (Join-Path $Art 'regions-1.txt') $regs
$rows = @(Get-RegionRows $regs)
$local = @(@($rows | Where-Object { -not $_.Forwarded }))
Add-Check 'WH has >=2 local regions (spawn + far)' ($local.Count -ge 2) ('local=' + $local.Count)
$spawn = @($local | Sort-Object Id | Select-Object -First 1) | Select-Object -First 1
$tgt = @($local | Sort-Object Id | Select-Object -Last 1) | Select-Object -First 1
Add-Check 'migration target picked (#N id chunks)' ($null -ne $tgt -and $tgt.Chunks -gt 0) $(if($tgt){"#$($tgt.Num) (id=$($tgt.Id)) chunks=$($tgt.Chunks)"}else{''})
Add-Check 'spawn region identified (#N id)' ($null -ne $spawn) $(if($spawn){"#$($spawn.Num) (id=$($spawn.Id))"}else{''})

# ---------------------------------------------------------------- S1: entity guard
Write-Output '== S1: entity guard (fake players) =='
if ($spawn) {
    $respS = Invoke-Rcon $whRcon "enigma distributed migrate $($spawn.Num)"
    Save-Txt (Join-Path $Art 'migrate-spawn-guard.txt') $respS
    Add-Check 'spawn region refused by spawn guard' ((Clean-Txt $respS) -match 'contains the world spawn') $respS
}
if ($tgt) {
    $eBase = EntitiesOf (Invoke-Rcon $whRcon "enigma distributed info $($tgt.Num)")
    Add-Check 'baseline entity count is 0' ($eBase -eq 0) ("baseline Entities=$eBase")

    foreach ($x in 48006, 48010, 48014) {
        $null = Invoke-Rcon $whRcon ('execute in minecraft:overworld run summon armor_stand ' + $x + ' 320 48006 {Tags:["enigma_fake"]}')
        Start-Sleep -Seconds 2
    }
    Start-Sleep -Seconds 8
    $infoFake = Invoke-Rcon $whRcon "enigma distributed info $($tgt.Num)"
    Save-Txt (Join-Path $Art 'info-fakeplayers.txt') $infoFake
    $eFake = EntitiesOf $infoFake
    Add-Check 'fake players (armor stands) appear in region' ($eFake -ge 3) ("Entities=$eFake (3 stands)")

    $beforeLog = Read-Log $Wh
    $respG = Invoke-Rcon $whRcon "enigma distributed migrate $($tgt.Num)"
    Save-Txt (Join-Path $Art 'migrate-guard.txt') $respG
    Start-Sleep -Seconds 5
    $tail = $null
    $afterLog = Read-Log $Wh
    if ($afterLog -and $beforeLog -and $afterLog.Length -gt $beforeLog.Length) {
        $tail = $afterLog.Substring($beforeLog.Length)
    }
    $cG = Clean-Txt $respG
    $blocked = ($cG -match 'still carries') -and ($null -eq $tail -or $tail -notmatch 'Migration of region \d+ to Compute Host -?\d+ started')
    Add-Check 'guard blocks migration with fake players' $blocked $respG

    $regs2 = Invoke-Rcon $whRcon 'enigma distributed regions'
    $rowStill = @(Get-RegionRows $regs2 | Where-Object { $_.Id -eq $tgt.Id }) | Select-Object -First 1
    Add-Check 'region still local after blocked migration' (($rowStill) -and -not $rowStill.Forwarded) $rowStill.Line
}

# ---------------------------------------------------------------- S2: cleanup + successful migration
Write-Output '== S2: kill fake players, then migrate =='
if ($tgt) {
    foreach ($x in 48006, 48010, 48014) {
        $null = Invoke-Rcon $whRcon "execute in minecraft:overworld positioned $x 320 48006 run kill @e[type=armor_stand,distance=0..40]"
        Start-Sleep -Seconds 2
    }
    $eClean = Wait-EntitiesLe $Wh $whRcon $tgt.Num 0 45
    Add-Check 'fake players removed (entity count 0)' ($eClean -eq 0) ("Entities=$eClean")

    $respM = Invoke-Rcon $whRcon "enigma distributed migrate $($tgt.Num)"
    Save-Txt (Join-Path $Art 'migrate-ok.txt') $respM
    Add-Check 'manual migrate accepted' ((Clean-Txt $respM) -match 'started') $respM

    $started = Wait-Log $Wh 'Migration of region \d+ to Compute Host -?\d+ started' 60
    Add-Check 'WH log migration started' ($null -ne $started) $started
    $verified = Wait-Log $Wh 'MIGRATION of region \d+ VERIFIED' 120
    Add-Check 'migration VERIFIED (marker state read on CH)' ($null -ne $verified) $verified
    $released = Wait-Log $Wh 'World Host released \d+ chunk\(s\) of region \d+ to Compute Host -?\d+' 60
    Add-Check 'WH released chunks' ($null -ne $released) $released
    $installed = Wait-Log $Ch 'Installed \d+ migrated chunk\(s\) of region \d+ into' 120
    Add-Check 'CH installed snapshot' ($null -ne $installed) $installed
    $ticking = Wait-Log $Ch 'Region \d+ is now ticking locally' 30
    Add-Check 'CH ticks region locally' ($null -ne $ticking) $ticking

    Start-Sleep -Seconds 3
    $regs3 = Invoke-Rcon $whRcon 'enigma distributed regions'
    $rowFwd = @(Get-RegionRows $regs3 | Where-Object { $_.Id -eq $tgt.Id }) | Select-Object -First 1
    Add-Check 'region now forwarded' (($rowFwd) -and $rowFwd.Forwarded) $rowFwd.Line

    $stWh = Invoke-Rcon $whRcon 'enigma distributed status'
    $stCh = Invoke-Rcon $chRcon 'enigma distributed status'
    Save-Txt (Join-Path $Art 'status-wh-after.txt') $stWh
    Save-Txt (Join-Path $Art 'status-ch-after.txt') $stCh
    Add-Check 'WH: handed over regions >= 1' (($stWh -match 'Handed over regions\D*(\d+)') -and ([int]$Matches[1] -ge 1))
    Add-Check 'CH: installed from World Host >= 1' (($stCh -match 'Installed from World Host\D*(\d+)') -and ([int]$Matches[1] -ge 1))
}

# ---------------------------------------------------------------- S3: prepare-stop
Write-Output '== S3: prepare-stop =='
$ps = Invoke-Rcon $whRcon 'enigma distributed prepare-stop'
Save-Txt (Join-Path $Art 'prepare-stop.txt') $ps
Add-Check 'prepare-stop requested (returning forwarded)' ((Clean-Txt $ps) -match 'returning \d+ forwarded') $ps
$ret = Wait-Log $Wh 'Region \d+ returned to the World Host' 120
Add-Check 'region returned to WH' ($null -ne $ret) $ret
$done = Wait-Log $Wh 'Prepare stop complete: all regions are back on the World Host' 60
Add-Check 'prepare-stop complete' ($null -ne $done) $done
Add-Check 'WH exited after prepare-stop' (($Wh.Proc) -and $Wh.Proc.WaitForExit(90000)) ''

# ---------------------------------------------------------------- S4: regression scan
Write-Output '== S4: crash signature scan =='
$signatures = @('Adding block without owning region', 'failed main thread check',
    'ThreadViolationException', 'could not be ticked', 'failed to tick',
    'OutOfMemoryError', 'inside other region', 'Entry count of')
foreach ($srv in @($Wh, $Ch)) {
    $txt = Read-Log $srv
    if (-not $txt) { $txt = (Get-Content $srv.Out -Raw -ErrorAction SilentlyContinue) }
    foreach ($sig in $signatures) {
        $c = ([regex]::Matches($txt, [regex]::Escape($sig), 'IgnoreCase')).Count
        Add-Check ("no '{0}' in {1} log" -f $sig, $srv.Name) ($c -eq 0) ("hits=" + $c)
    }
}

# ---------------------------------------------------------------- teardown
Write-Output '== teardown =='
Stop-Server $Ch
Stop-Server $Wh

# ---------------------------------------------------------------- report
Write-Output ''
Write-Output '============================== RESULT =============================='
$fail = @($script:Checks | Where-Object { -not $_.Ok })
$pass = @($script:Checks | Where-Object { $_.Ok })
foreach ($c in $script:Checks) {
    $mark = $(if ($c.Ok) { 'PASS' } else { 'FAIL' })
    Write-Output ("[{0}] {1}" -f $mark, $c.Name)
    if ($c.Detail) {
        $d = (Clean-Txt $c.Detail)
        if ($d.Length -gt 300) { $d = $d.Substring(0, 300) }
        $d = $d -replace "`r?`n", ' | '
        Write-Output ("        {0}" -f $d)
    }
}
Write-Output ('---- {0} passed, {1} failed ----' -f $pass.Count, $fail.Count)
Write-Output ('artifacts: {0}' -f $Art)
$script:Checks | ForEach-Object {
    [pscustomobject]@{ ok=$_.Ok; name=$_.Name; detail=($_.Detail -replace "`r?`n", ' ') }
} | ConvertTo-Json | Set-Content (Join-Path $Art 'summary.json')
exit $(if ($fail.Count -eq 0) { 0 } else { 1 })