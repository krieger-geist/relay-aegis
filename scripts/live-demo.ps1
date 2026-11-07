<#
.SYNOPSIS
  Shares the RELAY running on this PC at a temporary public HTTPS link, for a live demo.

.DESCRIPTION
  Starts a Cloudflare quick tunnel (no account needed) to the frontend on localhost:5173, which also serves /api, and
  recreates the backend so it accepts sign-ins from the tunnel's address. The link works only while this PC, Docker
  and the tunnel are running, and changes every time. "stop" closes the tunnel and puts the backend back.

  Requires cloudflared:  winget install --id Cloudflare.cloudflared

.EXAMPLE
  .\scripts\live-demo.ps1            (start, prints and copies the link)
  .\scripts\live-demo.ps1 status
  .\scripts\live-demo.ps1 stop
#>
param(
    [Parameter(Position = 0)][ValidateSet('start', 'stop', 'status')][string]$Command = 'start',
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $env:TEMP 'relay-live-demo'
$pidFile = Join-Path $state 'cloudflared.pid'
$urlFile = Join-Path $state 'url.txt'
$localOrigins = 'http://localhost:5173,http://127.0.0.1:5173'

function Get-Tunnel {
    if (-not (Test-Path $pidFile)) { return $null }
    $id = [int](Get-Content $pidFile -Raw)
    return Get-Process -Id $id -ErrorAction SilentlyContinue
}

# Recreates the backend (and backend-2 when the ha profile is running) with the given origins and public URL;
# without them, the backend goes back to the values from .env and docker-compose.yml.
function Update-Backend([string]$origins, [string]$publicUrl) {
    if ($origins) { $env:RELAY_CORS_ALLOWED_ORIGINS = $origins }
    if ($publicUrl) { $env:RELAY_PUBLIC_URL = $publicUrl } else { Remove-Item Env:RELAY_PUBLIC_URL -ErrorAction SilentlyContinue }
    Push-Location $root
    # docker reports through its exit code; PowerShell 5.1 would turn its progress output on stderr into errors.
    $ErrorActionPreference = 'Continue'
    try {
        $running = @(docker compose --profile ha ps --services --status running)
        $services = @('backend') + @($running | Where-Object { $_ -eq 'backend-2' })
        docker compose --profile ha up -d --no-deps @services
        if ($LASTEXITCODE -ne 0) { throw 'docker compose could not recreate the backend.' }
    } finally {
        Pop-Location
        Remove-Item Env:RELAY_CORS_ALLOWED_ORIGINS -ErrorAction SilentlyContinue
        Remove-Item Env:RELAY_PUBLIC_URL -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try { if ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 'http://localhost:8080/api/health').StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Seconds 2
    }
    throw "The backend did not report healthy within $TimeoutSeconds s. Check: docker compose logs backend"
}

switch ($Command) {
    'start' {
        if (Get-Tunnel) { Write-Host "Already running: $(Get-Content $urlFile -Raw)"; return }
        if (-not (Get-Command cloudflared -ErrorAction SilentlyContinue)) {
            throw 'cloudflared is not installed. Run: winget install --id Cloudflare.cloudflared (then open a new terminal).'
        }
        try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://localhost:5173' | Out-Null }
        catch { throw 'RELAY is not running on localhost:5173. Start it first: docker compose up -d' }

        New-Item -ItemType Directory -Force $state | Out-Null
        $log = Join-Path $state 'cloudflared.log'
        Remove-Item $log, "$log.out" -ErrorAction SilentlyContinue
        $tunnel = Start-Process cloudflared -ArgumentList 'tunnel', '--no-autoupdate', '--url', 'http://localhost:5173' `
            -RedirectStandardError $log -RedirectStandardOutput "$log.out" -WindowStyle Hidden -PassThru
        Set-Content $pidFile $tunnel.Id

        $url = $null
        $deadline = (Get-Date).AddSeconds(45)
        while (-not $url -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 1
            if ($tunnel.HasExited) { break }
            $match = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($match) { $url = $match.Matches[0].Value }
        }
        if (-not $url) {
            Stop-Process -Id $tunnel.Id -ErrorAction SilentlyContinue
            Remove-Item $pidFile -ErrorAction SilentlyContinue
            throw "The tunnel did not start. See $log"
        }
        Set-Content $urlFile $url

        Write-Host "Tunnel up. Letting the backend accept sign-ins from $url ..."
        Update-Backend "$localOrigins,$url" $url
        try { Set-Clipboard $url } catch { }
        Write-Host ''
        Write-Host "Live demo: $url  (copied)"
        Write-Host 'It stays up while this PC, Docker and the tunnel run. Stop it after the call: .\scripts\live-demo.ps1 stop'
    }
    'status' {
        if (Get-Tunnel) { Write-Host "Running: $(Get-Content $urlFile -Raw)" } else { Write-Host 'Not running.' }
    }
    'stop' {
        $tunnel = Get-Tunnel
        if ($tunnel) { Stop-Process -Id $tunnel.Id; Write-Host 'Tunnel closed.' } else { Write-Host 'No tunnel was running.' }
        Remove-Item $pidFile, $urlFile -ErrorAction SilentlyContinue
        Write-Host 'Putting the backend back to local-only sign-ins ...'
        Update-Backend $null $null
        Write-Host 'Done.'
    }
}
