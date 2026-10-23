<#
.SYNOPSIS
  RELAY AEGIS chaos script: injects failures into the Smart Business AI Assistant (SBA) on demand and measures them.

.DESCRIPTION
  The protected system is SBA (sba-frontend -> sba-backend -> sba-postgres, from C:\dev\smart-business-ai) plus the
  orders-service demo for the credential case. Every injection is reversible with "reset"; real data is never changed
  ("corrupt-record" adds its own marked row and "reset" deletes it).

  Measurement is independent of RELAY: "measure" polls the application's own health endpoint once a second, records
  when it went down and when it came back, then also reads RELAY's own incident record for the same failure, and
  appends one row to chaos\results\chaos-results.csv.

.EXAMPLE
  .\chaos.ps1 status
  .\chaos.ps1 stop-backend
  .\chaos.ps1 measure stop-backend -Healing on
  .\chaos.ps1 reset
  .\chaos.ps1 ready      (waits until it is safe to inject the next failure)
#>
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1)][string]$Failure,
    # Label for the run: "on" when RELAY's recovery policies are set to Automatic, "off" when they are not.
    [ValidateSet('on', 'off')][string]$Healing = 'on',
    # Give up waiting for recovery after this many seconds (a run without healing usually ends here).
    [int]$TimeoutSeconds = 180
)

# Native tools (docker, curl) report through exit codes; PowerShell 5.1 would turn their stderr into errors.
$ErrorActionPreference = 'Continue'
$BackendHealth = 'http://localhost:8081/actuator/health'
$FrontendUrl = 'http://localhost:3001/'
$OrdersHealth = 'http://localhost:8082/actuator/health'
$FloodUrl = 'http://localhost:8081/api/products'
$SbaNetwork = 'smart-business-ai_default'
$OrdersScripts = 'C:\relay\relay-aegis-orders-service\scripts'
$ResultsCsv = Join-Path $PSScriptRoot 'results\chaos-results.csv'
$ChaosOrder = 'CHAOS-0001'

$Failures = [ordered]@{
    'stop-backend'    = 'Stop the backend container (process gone)'
    'crash-backend'   = 'Make the backend process exit (Docker restart policy brings it back)'
    'freeze-backend'  = 'Freeze the backend process (SIGSTOP); the container keeps running'
    'stop-db'         = 'Stop the PostgreSQL container the backend depends on'
    'block-db'        = 'Block the database: disconnect sba-postgres from its network (both containers stay up)'
    'corrupt-record'  = 'Insert an order whose status the application cannot read (Orders page fails, health stays green)'
    'flood'           = 'Flood the backend API with 20000 requests, 300 at a time'
    'rotate-password' = 'Rotate the orders database password while orders-service keeps a hard-coded one'
}

function Write-Step($text) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) }

function Invoke-Docker {
    # Docker writes progress to stderr; only the exit code decides success.
    $out = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') failed: $out" }
    $out
}

function Invoke-SbaSql($sql) {
    Invoke-Docker exec sba-postgres psql -U postgres -d smart_business_ai -tAc $sql
}

function Test-Health($url) {
    # Returns @{ ok; ms; code }. SBA's health takes 3-4 s (Spring checks its SMTP server on every call), so the
    # timeout is 8 s; a frozen process never answers and is reported as down when it expires.
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $code = & curl.exe -s -o NUL -w '%{http_code}' --max-time 8 $url 2>$null
    $sw.Stop()
    @{ ok = ($code -eq '200'); ms = $sw.ElapsedMilliseconds; code = $code }
}

function Invoke-Inject($name) {
    switch ($name) {
        'stop-backend'   { Invoke-Docker stop sba-backend | Out-Null }
        # Signals sent from inside a container to its PID 1 are ignored unless the process handles them: the JVM
        # handles SIGTERM (it exits), and SIGSTOP has to come from outside, through Docker.
        'crash-backend'  { Invoke-Docker exec sba-backend kill -TERM 1 | Out-Null }
        'freeze-backend' { Invoke-Docker kill --signal SIGSTOP sba-backend | Out-Null }
        'stop-db'        { Invoke-Docker stop sba-postgres | Out-Null }
        'block-db'       { Invoke-Docker network disconnect $SbaNetwork sba-postgres | Out-Null }
        'corrupt-record' {
            $owner = Invoke-SbaSql "select id from users order by created_at limit 1"
            Invoke-SbaSql ("insert into orders (owner_id, order_number, status, total_amount, notes) values " +
                "('$owner', '$ChaosOrder', 'C0RRUPT', 0, 'Inserted by chaos.ps1; removed by reset') " +
                "on conflict (owner_id, order_number) do nothing") | Out-Null
        }
        'flood' {
            $cfg = Join-Path $env:TEMP 'relay-chaos-flood.txt'
            # The API rejects anonymous calls, so each request is cheap for us but still runs through the backend's
            # web server and security filters. (Flooding /actuator/health would hammer the SMTP server instead.)
            $line = "url = `"$FloodUrl`"`noutput = `"NUL`""
            Set-Content -Path $cfg -Encoding ascii -Value (@($line) * 20000)
            Start-Process -FilePath curl.exe -WindowStyle Hidden -ArgumentList @('-s', '--parallel', '--parallel-max', '300', '--max-time', '10', '-K', $cfg)
        }
        'rotate-password' { & (Join-Path $OrdersScripts 'rotate-db-password.ps1') }
        default { throw "Unknown failure '$name'. Run .\chaos.ps1 list" }
    }
    Write-Step "Injected: $name - $($Failures[$name])"
}

function Invoke-Reset {
    Write-Step 'Resetting every injection'
    & docker kill --signal SIGCONT sba-backend 2>$null | Out-Null
    $nets = & docker inspect sba-postgres --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>$null
    if ($nets -notmatch $SbaNetwork) { & docker network connect --alias postgres $SbaNetwork sba-postgres 2>$null | Out-Null }
    & docker start sba-postgres 2>$null | Out-Null
    & docker start sba-backend 2>$null | Out-Null
    & docker exec sba-postgres psql -U postgres -d smart_business_ai -tAc "delete from orders where order_number = '$ChaosOrder'" 2>$null | Out-Null
    Get-Process curl -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*curl.exe' } | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Step 'Reset done. Orders-service password: run its own reset-demo.ps1 if you used rotate-password.'
}

function Show-Status {
    foreach ($c in 'sba-frontend', 'sba-backend', 'sba-postgres', 'orders-service', 'orders-postgres') {
        $s = & docker inspect $c --format '{{.State.Status}}{{if .State.Health}} / {{.State.Health.Status}}{{end}}' 2>$null
        '{0,-16} {1}' -f $c, $s
    }
    foreach ($u in $FrontendUrl, $BackendHealth, $OrdersHealth) {
        $h = Test-Health $u
        '{0,-46} {1} ({2} ms)' -f $u, $(if ($h.ok) { 'UP' } else { "DOWN [$($h.code)]" }), $h.ms
    }
}

function Get-RelayIncident($appName, [datetime]$since) {
    # RELAY's own record of the same failure: when it detected it and how long recovery took.
    $user = (& docker exec relay-aegis-db-1 printenv POSTGRES_USER).Trim()
    $db = (& docker exec relay-aegis-db-1 printenv POSTGRES_DB).Trim()
    $iso = $since.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $sql = "select id, status, coalesce(detection_duration_ms,-1), coalesce(recovery_duration_ms,-1), " +
           "to_char(detected_at at time zone 'UTC','YYYY-MM-DD""T""HH24:MI:SS""Z""'), " +
           "coalesce(to_char(recovered_at at time zone 'UTC','YYYY-MM-DD""T""HH24:MI:SS""Z""'),'') " +
           "from incidents where application_name like '$appName' and detected_at >= '$iso'::timestamptz - interval '5 seconds' " +
           "order by detected_at limit 1"
    $row = & docker exec relay-aegis-db-1 psql -U $user -d $db -tAF '|' -c $sql 2>$null
    if (-not $row) { return $null }
    $p = "$row".Trim().Split('|')
    @{ id = $p[0]; status = $p[1]; detectMs = [long]$p[2]; recoverMs = [long]$p[3]; detectedAt = $p[4]; recoveredAt = $p[5] }
}

function Invoke-RelaySql($sql) {
    $user = (& docker exec relay-aegis-db-1 printenv POSTGRES_USER).Trim()
    $db = (& docker exec relay-aegis-db-1 printenv POSTGRES_DB).Trim()
    & docker exec relay-aegis-db-1 psql -U $user -d $db -tAc $sql 2>$null
}

function Wait-Ready {
    # Safe to inject the next failure when SBA is healthy, RELAY has closed its last SBA incident, and the per-container
    # recovery cooldown (60 s after RELAY's last action) has passed.
    Write-Step 'Waiting until it is safe to inject the next failure...'
    while (-not (Test-Health $BackendHealth).ok) { Start-Sleep -Seconds 2 }
    Write-Step 'Backend healthy.'
    $openSql = "select count(*) from incidents where application_name like 'Smart Business AI Assistant%Backend' " +
               "and status not in ('resolved','recovery_verified','closed','recovered')"
    while ([int](Invoke-RelaySql $openSql) -gt 0) { Start-Sleep -Seconds 2 }
    Write-Step 'No open RELAY incident.'
    $since = Invoke-RelaySql ("select coalesce(extract(epoch from now() - max(occurred_at))::int, 9999) from recovery_events " +
                              "where application_name like 'Smart Business AI Assistant%Backend'")
    $left = 65 - [int]$since
    while ($left -gt 0) { Write-Host -NoNewline ("`r  recovery cooldown: {0,2} s " -f $left); Start-Sleep -Seconds 1; $left-- }
    Write-Host ''
    Write-Host 'READY - inject the next failure' -ForegroundColor Green
}

function Invoke-Measure($name) {
    if (-not $Failures.Contains($name)) { throw "Unknown failure '$name'. Run .\chaos.ps1 list" }
    $url = if ($name -eq 'rotate-password') { $OrdersHealth } else { $BackendHealth }
    $app = if ($name -eq 'rotate-password') { 'Orders service' } else { 'Smart Business AI Assistant%Backend' }
    if (-not (Test-Health $url).ok) { throw "$url is not healthy before the test; run .\chaos.ps1 reset first." }

    $start = Get-Date
    Invoke-Inject $name
    $down = $null; $up = $null; $latencies = New-Object System.Collections.Generic.List[long]; $errors = 0
    $window = if ($name -in 'flood', 'corrupt-record') { 30 } else { $TimeoutSeconds }
    while (((Get-Date) - $start).TotalSeconds -lt $window) {
        $h = Test-Health $url
        $t = ((Get-Date) - $start).TotalSeconds
        $latencies.Add($h.ms)
        if (-not $h.ok) { $errors++ }
        if (-not $h.ok -and -not $down) { $down = $t; Write-Step ("DOWN after {0:N1} s [{1}]" -f $t, $h.code) }
        if ($h.ok -and $down -and -not $up) { $up = $t; Write-Step ("UP again after {0:N1} s" -f $t); break }
        Start-Sleep -Milliseconds ([Math]::Max(0, 1000 - $h.ms))
    }

    $outcome = if (-not $down) { 'never went down (health stayed green)' }
               elseif ($up) { 'recovered' } else { "still down after $window s" }
    Write-Step "Result: $outcome"

    Start-Sleep -Seconds 3   # let RELAY write its final incident state
    $inc = Get-RelayIncident $app $start
    if ($inc) {
        $d = if ($inc.detectMs -ge 0) { '{0:N1} s' -f ($inc.detectMs / 1000) } else { '-' }
        $r = if ($inc.recoverMs -ge 0) { '{0:N1} s' -f ($inc.recoverMs / 1000) } else { '-' }
        Write-Step "RELAY incident $($inc.id): status $($inc.status), detected in $d, recovery $r"
    } else { Write-Step 'RELAY recorded no incident for this run.' }

    $sorted = $latencies | Sort-Object
    $row = [pscustomobject]@{
        run_at              = $start.ToString('s')
        failure             = $name
        healing             = $Healing
        outcome             = $outcome
        down_after_s        = if ($down) { [Math]::Round($down, 1) } else { '' }
        back_up_after_s     = if ($up) { [Math]::Round($up, 1) } else { '' }
        outage_s            = if ($down -and $up) { [Math]::Round($up - $down, 1) } elseif ($down) { "> $([Math]::Round($window - $down))" } else { 0 }
        health_p50_ms       = $sorted[[int]($sorted.Count / 2)]
        health_max_ms       = $sorted[-1]
        failed_checks       = $errors
        relay_incident      = if ($inc) { $inc.id } else { '' }
        relay_status        = if ($inc) { $inc.status } else { '' }
        relay_detect_s      = if ($inc -and $inc.detectMs -ge 0) { [Math]::Round($inc.detectMs / 1000, 1) } else { '' }
        relay_recover_s     = if ($inc -and $inc.recoverMs -ge 0) { [Math]::Round($inc.recoverMs / 1000, 1) } else { '' }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $ResultsCsv) | Out-Null
    $row | Export-Csv -Path $ResultsCsv -Append -NoTypeInformation -Encoding UTF8
    $row | Format-List
}

switch ($Command) {
    'list'    { $Failures.GetEnumerator() | ForEach-Object { '{0,-16} {1}' -f $_.Key, $_.Value } }
    'status'  { Show-Status }
    'reset'   { Invoke-Reset }
    'ready'   { Wait-Ready }
    'measure' { Invoke-Measure $Failure }
    'help'    { Get-Help $PSCommandPath -Detailed }
    default   { if ($Failures.Contains($Command)) { Invoke-Inject $Command } else { throw "Unknown command '$Command'. Run .\chaos.ps1 list" } }
}
