param(
    [ValidateSet("start", "configure", "status", "test", "remove", "support", "backup")]
    [string]$Action = "start",
    [string]$BindAddress,
    [string]$AllowedSubnet,
    [switch]$EnableHttps,
    [ValidateRange(1,720)][int]$SinceHours = 24,
    [string]$BackupDirectory
)

$ErrorActionPreference = "Stop"
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentFile = Join-Path $projectDirectory ".env"
$runtimeDirectory = Join-Path $projectDirectory "deployment\runtime"
$icsBackupFile = Join-Path $runtimeDirectory "ics-backup.json"
$dnsBackupFile = Join-Path $runtimeDirectory "dns-backup.json"
$domain = "nowash-track.com"

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-Elevated {
    $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", ('"' + $PSCommandPath + '"'), "-Action", $Action)
    if ($BindAddress) { $arguments += @("-BindAddress", $BindAddress) }
    if ($AllowedSubnet) { $arguments += @("-AllowedSubnet", $AllowedSubnet) }
    if ($EnableHttps) { $arguments += "-EnableHttps" }
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

function Reserve-DnsPort([string]$ExpectedAddress) {
    try {
        $existingAnswer = Resolve-DnsName -Name $domain -Server $ExpectedAddress -Type A -DnsOnly -QuickTimeout -ErrorAction Stop |
            Where-Object IPAddress | Select-Object -First 1
        if ($existingAnswer.IPAddress -eq $ExpectedAddress) {
            Write-Host "NW Track DNS is already running: $domain -> $ExpectedAddress" -ForegroundColor Green
            return
        }
    } catch {
        # No valid NW Track DNS answer exists yet; continue with port ownership checks.
    }
    $owners = @()
    $owners += @(Get-NetUDPEndpoint -LocalPort 53 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess)
    $owners += @(Get-NetTCPConnection -LocalPort 53 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess)
    $owners = @($owners | Where-Object { $_ -gt 0 } | Select-Object -Unique)
    foreach ($ownerPid in $owners) {
        $services = @(Get-CimInstance Win32_Service | Where-Object ProcessId -eq $ownerPid)
        if ($services.Count -eq 1 -and $services[0].Name -eq "SharedAccess") {
            Set-Service -Name SharedAccess -StartupType Disabled
            try {
                $manager = New-Object -ComObject HNetCfg.HNetShare
                $sharedConnections = @()
                foreach ($connection in $manager.EnumEveryConnection()) {
                    $configuration = $manager.INetSharingConfigurationForINetConnection($connection)
                    if ($configuration.SharingEnabled) {
                        $properties = $manager.NetConnectionProps($connection)
                        $sharedConnections += [pscustomobject]@{ Guid=$properties.Guid.ToString(); Type=[int]$configuration.SharingConnectionType }
                        $configuration.DisableSharing()
                    }
                }
                if ($sharedConnections.Count -gt 0) {
                    New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
                    $sharedConnections | ConvertTo-Json | Set-Content -LiteralPath $icsBackupFile -Encoding utf8
                    Write-Host "Backed up and disabled active Windows connection sharing." -ForegroundColor Yellow
                }
            } catch {
                throw "Windows denied access while disabling active Internet Connection Sharing. Confirm this window title says Administrator, turn off Mobile hotspot, and retry. Details: $($_.Exception.Message)"
            }
            try { Stop-Service -Name SharedAccess -Force -ErrorAction Stop } catch { & sc.exe stop SharedAccess | Out-Host }
            $deadline=(Get-Date).AddSeconds(15)
            while ((Get-Service SharedAccess).Status -ne 'Stopped' -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
            $stillBound = @(Get-NetUDPEndpoint -LocalPort 53 -ErrorAction SilentlyContinue) + @(Get-NetTCPConnection -LocalPort 53 -State Listen -ErrorAction SilentlyContinue)
            if ($stillBound) { throw "Internet Connection Sharing still owns DNS port 53 after connection sharing was disabled. Restart Windows once, then rerun this installer." }
            Set-EnvironmentValue "ICS_DISABLED_BY_NWTRACK" "true"
            Write-Host "Disabled Internet Connection Sharing because it occupied local DNS port 53." -ForegroundColor Yellow
        } else {
            $process = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
            throw "DNS port 53 is already used by PID $ownerPid ($($process.ProcessName)). Stop or reconfigure it before installing NW Track."
        }
    }
}

function Restore-InternetConnectionSharing {
    Set-Service -Name SharedAccess -StartupType Manual
    if (Test-Path -LiteralPath $icsBackupFile) {
        $saved = @(Get-Content -LiteralPath $icsBackupFile -Raw | ConvertFrom-Json)
        $manager = New-Object -ComObject HNetCfg.HNetShare
        foreach ($connection in $manager.EnumEveryConnection()) {
            $properties = $manager.NetConnectionProps($connection)
            $match = $saved | Where-Object Guid -eq $properties.Guid.ToString() | Select-Object -First 1
            if ($match) { $manager.INetSharingConfigurationForINetConnection($connection).EnableSharing([int]$match.Type) }
        }
        Remove-Item -LiteralPath $icsBackupFile -Force
        Write-Host "Restored the previous Windows connection-sharing configuration." -ForegroundColor Green
    }
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
    if ($EnableHttps) { Reserve-DnsPort $ip }
    $subnet = if ($AllowedSubnet) { $AllowedSubnet } else { Get-NetworkCidr $ip $wifi.Address.PrefixLength }
    if ($ip -ne $wifi.Address.IPAddress) { Write-Warning "Configured address $ip differs from detected Wi-Fi address $($wifi.Address.IPAddress)." }

    Set-NetConnectionProfile -InterfaceIndex $wifi.Adapter.ifIndex -NetworkCategory Private
    Set-EnvironmentValue "BIND_ADDRESS" $ip
    Set-EnvironmentValue "ALLOWED_SUBNET" $subnet
    Set-EnvironmentValue "APP_DOMAIN" $domain
    Set-EnvironmentValue "HTTPS_ENABLED" ($EnableHttps.IsPresent.ToString().ToLowerInvariant())

    Get-NetFirewallRule -DisplayName "NW Track - *" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    if ($EnableHttps) {
    New-NetFirewallRule -DisplayName "NW Track - HTTPS" -Description "Allow NW Track HTTPS from the factory LAN" `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 443 -LocalAddress $ip `
        -RemoteAddress $subnet -InterfaceAlias $wifi.Adapter.Name -Profile Private | Out-Null
    }
    New-NetFirewallRule -DisplayName "NW Track - HTTP" -Description "Allow NW Track HTTP from the factory LAN" `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 -LocalAddress $ip `
        -RemoteAddress $subnet -InterfaceAlias $wifi.Adapter.Name -Profile Private | Out-Null
    if ($EnableHttps) {
    foreach ($protocol in @("TCP","UDP")) {
        New-NetFirewallRule -DisplayName "NW Track - DNS $protocol" -Description "Resolve $domain on the factory LAN" `
            -Direction Inbound -Action Allow -Protocol $protocol -LocalPort 53 -LocalAddress $ip `
            -RemoteAddress $subnet -InterfaceAlias $wifi.Adapter.Name -Profile Private | Out-Null
    }

    }
    Write-Host "NW Track Wi-Fi access configured." -ForegroundColor Green
    Write-Host "Wi-Fi adapter: $($wifi.Adapter.Name)" -ForegroundColor Cyan
    Write-Host "Laptop address: $ip" -ForegroundColor Cyan
    Write-Host "Allowed subnet: $subnet" -ForegroundColor Cyan
    if ($EnableHttps) {
    Write-Host "Application URL: https://$domain" -ForegroundColor Cyan
    Write-Host "Configure the Wi-Fi router DHCP DNS address as: $ip" -ForegroundColor Yellow
    } else { Write-Host "Application URL: http://$ip" -ForegroundColor Cyan }
    Write-Host "No router port forwarding is required or created." -ForegroundColor Yellow
}

function Install-FactoryCa {
    $ca = Join-Path $projectDirectory "deployment\certificates\ca.crt"
    if (-not (Test-Path -LiteralPath $ca)) { throw "Generated factory CA was not found: $ca" }
    Import-Certificate -FilePath $ca -CertStoreLocation Cert:\LocalMachine\Root | Out-Null
    Write-Host "Trusted the NW Track factory CA on this server." -ForegroundColor Green
    Write-Host "Install this CA on client devices: $ca" -ForegroundColor Yellow
}

function Enable-LocalDns {
    $wifi = Get-WifiNetwork
    $ip = Get-EnvironmentValue "BIND_ADDRESS"
    if (-not $ip) { throw "BIND_ADDRESS is missing from .env." }
    if (Test-Path -LiteralPath $dnsBackupFile) {
        $saved = Get-Content -LiteralPath $dnsBackupFile -Raw | ConvertFrom-Json
        $addresses = @($saved.ServerAddresses | Where-Object { $_ })
        if ($addresses.Count -gt 0) { Set-DnsClientServerAddress -InterfaceIndex $wifi.Adapter.ifIndex -ServerAddresses $addresses }
        else { Set-DnsClientServerAddress -InterfaceIndex $wifi.Adapter.ifIndex -ResetServerAddresses }
        Remove-Item -LiteralPath $dnsBackupFile -Force
    }
    Get-DnsClientNrptRule -ErrorAction SilentlyContinue | Where-Object DisplayName -eq "NW Track Local Domain" |
        ForEach-Object { Remove-DnsClientNrptRule -Name $_.Name -Force }
    Add-DnsClientNrptRule -Namespace $domain -NameServers $ip -DisplayName "NW Track Local Domain" `
        -Comment "Resolve the factory-local NW Track application through CoreDNS" | Out-Null
    Clear-DnsClientCache
    $answer = Resolve-DnsName -Name $domain -Type A -DnsOnly -QuickTimeout -ErrorAction Stop |
        Where-Object IPAddress | Select-Object -First 1
    if ($answer.IPAddress -ne $ip) { throw "Windows DNS returned $($answer.IPAddress), expected $ip." }
    Write-Host "Windows resolves $domain through the NW Track DNS server: $ip" -ForegroundColor Green
}

function Restore-ClientDns {
    Get-DnsClientNrptRule -ErrorAction SilentlyContinue | Where-Object DisplayName -eq "NW Track Local Domain" |
        ForEach-Object { Remove-DnsClientNrptRule -Name $_.Name -Force }
    if (Test-Path -LiteralPath $dnsBackupFile) {
        $saved = Get-Content -LiteralPath $dnsBackupFile -Raw | ConvertFrom-Json
        $adapter = Get-NetAdapter | Where-Object { $_.InterfaceGuid.ToString() -eq $saved.InterfaceGuid } | Select-Object -First 1
        if (-not $adapter) { $adapter = (Get-WifiNetwork).Adapter }
        $addresses = @($saved.ServerAddresses | Where-Object { $_ })
        if ($addresses.Count -gt 0) { Set-DnsClientServerAddress -InterfaceIndex $adapter.ifIndex -ServerAddresses $addresses }
        else { Set-DnsClientServerAddress -InterfaceIndex $adapter.ifIndex -ResetServerAddresses }
        Remove-Item -LiteralPath $dnsBackupFile -Force
    }
    Clear-DnsClientCache
    Write-Host "Removed the NW Track local-domain DNS policy and restored any saved DNS settings." -ForegroundColor Green
}

function Show-Status {
    $ip=Get-EnvironmentValue "BIND_ADDRESS";$subnet=Get-EnvironmentValue "ALLOWED_SUBNET";$configuredDomain=Get-EnvironmentValue "APP_DOMAIN"
    if (-not $ip -or -not $subnet) {
        Write-Host "Wi-Fi access is not configured." -ForegroundColor Yellow
        Write-Host "Run: deployment\windows\install.cmd start" -ForegroundColor Cyan
    }
    Write-Host "Domain: $configuredDomain";Write-Host "DNS/server address: $ip";Write-Host "Allowed subnet: $subnet"
    Get-NetFirewallRule -DisplayName "NW Track - *" -ErrorAction SilentlyContinue |
        Select-Object DisplayName,Enabled,Profile,Direction,Action | Format-Table
}

function Test-Deployment {
    $ip=Get-EnvironmentValue "BIND_ADDRESS";$configuredDomain=Get-EnvironmentValue "APP_DOMAIN"
    if (-not $ip) { throw "NW Track network access is not configured. Run deployment\windows\install.cmd configure." }
    if ((Get-EnvironmentValue "HTTPS_ENABLED") -ne "true") {
        $url="http://${ip}/actuator/health"
        $health=& curl.exe --silent --fail --noproxy "*" $url
        if ($LASTEXITCODE -ne 0 -or $health -notmatch '"status"\s*:\s*"UP"') { throw "NW Track HTTP health check failed." }
        Write-Host "PASS: $url returned UP" -ForegroundColor Green
        return
    }
    $resolved=Resolve-DnsName -Name $configuredDomain -Server $ip -Type A -ErrorAction Stop | Where-Object IPAddress | Select-Object -First 1
    if ($resolved.IPAddress -ne $ip) { throw "Local DNS returned $($resolved.IPAddress), expected $ip." }
    $url="https://${configuredDomain}/actuator/health";$ca=Join-Path $projectDirectory "deployment\certificates\ca.crt"
    $health=& curl.exe --silent --fail --ssl-no-revoke --cacert $ca --resolve "${configuredDomain}:443:${ip}" $url
    if ($LASTEXITCODE -ne 0 -or $health -notmatch '"status"\s*:\s*"UP"') { throw "NW Track responded but is not healthy." }
    Write-Host "PASS: $url returned UP" -ForegroundColor Green
    Write-Host "Test this same URL from another device connected to the home Wi-Fi." -ForegroundColor Cyan
}

function Enable-LogArchiving {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "support.ps1") schedule
    if ($LASTEXITCODE -ne 0) {
        throw "NW Track is running, but automatic log archiving could not be configured. Review the error above and rerun install.cmd start under the Windows account that runs Docker Desktop."
    }
    Write-Host "Automatic log archiving is enabled (every five minutes while the Docker user is logged in)." -ForegroundColor Green
    Write-Host "To export logs later: deployment\windows\install.cmd support" -ForegroundColor Cyan
    Write-Host "To back up the database: deployment\windows\install.cmd backup" -ForegroundColor Cyan
}

if ($Action -in @("start","configure","remove") -and -not (Test-Administrator)) { Invoke-Elevated }

switch ($Action) {
    "configure" { Configure-Network }
    "start" {
        Configure-Network
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "nwtrack.ps1") start
        if ($LASTEXITCODE -ne 0) { throw "NW Track startup failed with exit code $LASTEXITCODE." }
        if ($EnableHttps) { Enable-LocalDns; Install-FactoryCa }
        else {
            Restore-ClientDns
            if ((Get-EnvironmentValue "ICS_DISABLED_BY_NWTRACK") -eq "true") { Restore-InternetConnectionSharing; Set-EnvironmentValue "ICS_DISABLED_BY_NWTRACK" "false" }
        }
        Test-Deployment
        Enable-LogArchiving
    }
    "support" {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "support.ps1") bundle -SinceHours $SinceHours
        if ($LASTEXITCODE -ne 0) { throw "Support collection was incomplete. Check the bundle output and capture.txt for diagnostics." }
    }
    "backup" {
        $previousBackupDirectory = $env:NWTRACK_BACKUP_DIRECTORY
        try {
            if ($BackupDirectory) { $env:NWTRACK_BACKUP_DIRECTORY = $BackupDirectory }
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "backup.ps1")
            if ($LASTEXITCODE -ne 0) { throw "Database backup failed. No successful backup was reported; review the error above." }
        } finally { $env:NWTRACK_BACKUP_DIRECTORY = $previousBackupDirectory }
    }
    "status" { Show-Status }
    "test" { Test-Deployment }
    "remove" {
        Restore-ClientDns
        Get-NetFirewallRule -DisplayName "NW Track - *" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        Set-EnvironmentValue "BIND_ADDRESS" "127.0.0.1"
        Set-EnvironmentValue "ALLOWED_SUBNET" "127.0.0.1/32"
        if ((Get-EnvironmentValue "ICS_DISABLED_BY_NWTRACK") -eq "true") {
            Restore-InternetConnectionSharing
            Set-EnvironmentValue "ICS_DISABLED_BY_NWTRACK" "false"
            Write-Host "Restored Internet Connection Sharing startup type to Manual." -ForegroundColor Green
        }
        Write-Host "Wi-Fi exposure removed. NW Track is configured for laptop-only access." -ForegroundColor Green
    }
}
