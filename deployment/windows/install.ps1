param(
    [ValidateSet("start", "configure", "status", "test", "remove")]
    [string]$Action = "start",
    [string]$BindAddress,
    [string]$AllowedSubnet,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentFile = Join-Path $projectDirectory ".env"
$firewallRuleName = "NW Track - Home WiFi - TCP $Port"

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-Elevated {
    $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", ('"' + $PSCommandPath + '"'), "-Action", $Action, "-Port", $Port)
    if ($BindAddress) { $arguments += @("-BindAddress", $BindAddress) }
    if ($AllowedSubnet) { $arguments += @("-AllowedSubnet", $AllowedSubnet) }
    $process = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList ($arguments -join ' ')
    exit $process.ExitCode
}

function Get-WifiNetwork {
    $adapters = @(Get-NetAdapter -ErrorAction Stop | Where-Object {
        $_.Status -eq "Up" -and ($_.Name -match "Wi-?Fi|Wireless|WLAN" -or $_.InterfaceDescription -match "Wi-?Fi|Wireless|802\.11")
    })
    if (-not $adapters) { throw "No active Wi-Fi adapter was found. Connect this laptop to Wi-Fi and retry." }
    foreach ($adapter in $adapters) {
        $address = Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "169.254.*" -and $_.IPAddress -ne "127.0.0.1" } |
            Sort-Object PrefixLength -Descending | Select-Object -First 1
        if ($address) { return [pscustomobject]@{ Adapter=$adapter; Address=$address } }
    }
    throw "The active Wi-Fi adapter has no usable IPv4 address."
}

function Get-NetworkCidr([string]$IpAddress, [int]$PrefixLength) {
    $bytes = ([Net.IPAddress]::Parse($IpAddress)).GetAddressBytes()
    $network = New-Object byte[] 4
    for ($i=0; $i -lt 4; $i++) {
        $bits = [Math]::Max(0, [Math]::Min(8, $PrefixLength - ($i * 8)))
        $mask = if ($bits -eq 0) { 0 } else { (256 - [Math]::Pow(2, 8 - $bits)) }
        $network[$i] = $bytes[$i] -band [int]$mask
    }
    return ((New-Object Net.IPAddress -ArgumentList (,$network)).ToString() + "/" + $PrefixLength)
}

function Set-EnvironmentValue([string]$Name,[string]$Value) {
    $lines = if (Test-Path -LiteralPath $environmentFile) { @(Get-Content -LiteralPath $environmentFile) } else { @() }
    $found = $false
    $updated = foreach ($line in $lines) {
        if ($line -match ('^' + [Regex]::Escape($Name) + '=')) { $found=$true; "$Name=$Value" } else { $line }
    }
    if (-not $found) { $updated += "$Name=$Value" }
    Set-Content -LiteralPath $environmentFile -Value $updated -Encoding utf8
}

function Get-EnvironmentValue([string]$Name) {
    if (-not (Test-Path -LiteralPath $environmentFile)) { return $null }
    $line = Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match ('^' + [Regex]::Escape($Name) + '=') } | Select-Object -Last 1
    if ($line) { return ($line -split '=',2)[1].Trim() }
    return $null
}

function Configure-Network {
    try {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "nwtrack.ps1") init
        if ($LASTEXITCODE -ne 0) { throw "The initialization script returned exit code $LASTEXITCODE." }
    } catch {
        throw "Could not initialize NW Track configuration: $($_.Exception.Message)"
    }
    $wifi = Get-WifiNetwork
    $ip = if ($BindAddress) { $BindAddress } else { $wifi.Address.IPAddress }
    $subnet = if ($AllowedSubnet) { $AllowedSubnet } else { Get-NetworkCidr $ip $wifi.Address.PrefixLength }
    if ($ip -ne $wifi.Address.IPAddress) { Write-Warning "Configured address $ip differs from detected Wi-Fi address $($wifi.Address.IPAddress)." }

    Set-NetConnectionProfile -InterfaceIndex $wifi.Adapter.ifIndex -NetworkCategory Private
    Set-EnvironmentValue "BIND_ADDRESS" $ip
    Set-EnvironmentValue "ALLOWED_SUBNET" $subnet
    Set-EnvironmentValue "API_PORT" $Port

    Get-NetFirewallRule -DisplayName $firewallRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $firewallRuleName -Description "Allow NW Track only from the configured home Wi-Fi subnet" `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -LocalAddress $ip `
        -RemoteAddress $subnet -InterfaceAlias $wifi.Adapter.Name -Profile Private | Out-Null

    Write-Host "NW Track Wi-Fi access configured." -ForegroundColor Green
    Write-Host "Wi-Fi adapter: $($wifi.Adapter.Name)" -ForegroundColor Cyan
    Write-Host "Laptop address: $ip" -ForegroundColor Cyan
    Write-Host "Allowed subnet: $subnet" -ForegroundColor Cyan
    Write-Host "Health URL: http://${ip}:${Port}/actuator/health" -ForegroundColor Cyan
    Write-Host "No router port forwarding is created by this script." -ForegroundColor Yellow
}

function Show-Status {
    $ip=Get-EnvironmentValue "BIND_ADDRESS";$subnet=Get-EnvironmentValue "ALLOWED_SUBNET";$configuredPort=Get-EnvironmentValue "API_PORT"
    if (-not $ip -or -not $subnet) {
        Write-Host "Wi-Fi access is not configured." -ForegroundColor Yellow
        Write-Host "Run: .\nwtrack-home.cmd start" -ForegroundColor Cyan
    }
    Write-Host "Bind address: $ip";Write-Host "Allowed subnet: $subnet";Write-Host "Port: $configuredPort"
    Get-NetFirewallRule -DisplayName "NW Track - Home WiFi - TCP *" -ErrorAction SilentlyContinue |
        Select-Object DisplayName,Enabled,Profile,Direction,Action | Format-Table
}

function Test-Deployment {
    $ip=Get-EnvironmentValue "BIND_ADDRESS";$configuredPort=Get-EnvironmentValue "API_PORT"
    if (-not $ip) { throw "NW Track Wi-Fi access is not configured. Run nwtrack-home.cmd configure." }
    $url="http://${ip}:${configuredPort}/actuator/health"
    $health=Invoke-RestMethod -Uri $url -TimeoutSec 10
    if ($health.status -ne "UP") { throw "NW Track responded but is not healthy." }
    Write-Host "PASS: $url returned UP" -ForegroundColor Green
    Write-Host "Test this same URL from another device connected to the home Wi-Fi." -ForegroundColor Cyan
}

if ($Action -in @("start","configure","remove") -and -not (Test-Administrator)) { Invoke-Elevated }

switch ($Action) {
    "configure" { Configure-Network }
    "start" { Configure-Network; & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "nwtrack.ps1") start; if ($LASTEXITCODE -ne 0) { throw "NW Track startup failed with exit code $LASTEXITCODE." }; Test-Deployment }
    "status" { Show-Status }
    "test" { Test-Deployment }
    "remove" {
        Get-NetFirewallRule -DisplayName "NW Track - Home WiFi - TCP *" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        Set-EnvironmentValue "BIND_ADDRESS" "127.0.0.1"
        Set-EnvironmentValue "ALLOWED_SUBNET" "127.0.0.1/32"
        Write-Host "Wi-Fi exposure removed. NW Track is configured for laptop-only access." -ForegroundColor Green
    }
}
