param(
    [ValidateSet("init", "start", "stop", "restart", "status", "logs", "certificate", "reset")]
    [string]$Action = "start",
    [switch]$ConfirmDatabaseDeletion
)

$ErrorActionPreference = "Stop"
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentFile = Join-Path $projectDirectory ".env"
$composeFile = Join-Path $projectDirectory "compose.yaml"
$certificateDirectory = Join-Path $projectDirectory "deployment\certificates"
$script:DockerComposeExe = $null

function Add-DockerCliToPath {
    $candidateDirectories = @()
    $existingDocker = Get-Command docker -ErrorAction SilentlyContinue
    if ($existingDocker -and $existingDocker.Source) { $candidateDirectories += (Split-Path $existingDocker.Source -Parent) }
    if ($env:LOCALAPPDATA) {
        $candidateDirectories += (Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\resources\bin")
        $candidateDirectories += (Join-Path $env:LOCALAPPDATA "Docker\Docker\resources\bin")
    }
    if ($env:ProgramFiles) { $candidateDirectories += (Join-Path $env:ProgramFiles "Docker\Docker\resources\bin") }
    $usersRoot = Join-Path $env:SystemDrive "Users"
    if (Test-Path -LiteralPath $usersRoot) {
        Get-ChildItem -LiteralPath $usersRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $candidateDirectories += (Join-Path $_.FullName "AppData\Local\Programs\DockerDesktop\resources\bin")
            $candidateDirectories += (Join-Path $_.FullName "AppData\Local\Docker\Docker\resources\bin")
        }
    }
    $desktopProcess = Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($desktopProcess -and $desktopProcess.Path) {
        $installRoot = Split-Path (Split-Path $desktopProcess.Path -Parent) -Parent
        $candidateDirectories += (Join-Path $installRoot "resources\bin")
    }
    foreach ($directory in ($candidateDirectories | Where-Object { $_ } | Select-Object -Unique)) {
        if ($directory -and (Test-Path -LiteralPath (Join-Path $directory "docker.exe"))) {
            if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
                $env:Path = "$directory;$env:Path"
                Write-Host "Docker CLI located at: $directory" -ForegroundColor DarkGray
            }
            $standaloneCompose = Join-Path $directory "docker-compose.exe"
            if (Test-Path -LiteralPath $standaloneCompose) { $script:DockerComposeExe = $standaloneCompose; return }
            $pluginCompose = Join-Path (Split-Path $directory -Parent) "cli-plugins\docker-compose.exe"
            if (Test-Path -LiteralPath $pluginCompose) { $script:DockerComposeExe = $pluginCompose; return }
        }
    }
    Write-Host "Docker CLI search did not find docker.exe in Program Files or local Windows user profiles." -ForegroundColor Yellow
}

function Assert-Prerequisites {
    if (-not (Test-Path -LiteralPath $composeFile)) {
        throw "Missing deployment file: $composeFile"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory "pom.xml"))) {
        throw "Missing pom.xml. Restore the NW Track Spring Boot source into $projectDirectory before deployment."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory "src\main\java"))) {
        throw "Missing Spring Boot Java source under src\main\java."
    }
    Add-DockerCliToPath
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker is not installed or docker.exe is not in PATH. Install Docker Desktop, then rerun this script."
    }
    $dockerExe = (Get-Command docker -ErrorAction SilentlyContinue).Source
    & $env:ComSpec /d /c ('"' + $dockerExe + '" info >nul 2>nul')
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is installed but is not running. Start Docker Desktop, then rerun this script."
    }
    if (-not $script:DockerComposeExe -or -not (Test-Path -LiteralPath $script:DockerComposeExe)) { throw "Docker Compose executable was not found beside the Docker Desktop CLI." }
    & $script:DockerComposeExe version *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose is installed but could not run." }
}

function New-RandomHex([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return ([BitConverter]::ToString($bytes) -replace '-', '')
}

function New-EnvironmentFile {
    if (Test-Path -LiteralPath $environmentFile) {
        $existing = Get-Content -LiteralPath $environmentFile
        if (-not ($existing -match '^APP_DOMAIN=')) { Add-Content -LiteralPath $environmentFile -Value "APP_DOMAIN=nowash-track.com" -Encoding utf8 }
        if (-not ($existing -match '^BIND_ADDRESS=')) { Add-Content -LiteralPath $environmentFile -Value "BIND_ADDRESS=127.0.0.1" -Encoding utf8 }
        if (-not ($existing -match '^ALLOWED_SUBNET=')) { Add-Content -LiteralPath $environmentFile -Value "ALLOWED_SUBNET=127.0.0.1/32" -Encoding utf8 }
        if (-not ($existing -match '^SWAGGER_ENABLED=')) { Add-Content -LiteralPath $environmentFile -Value "SWAGGER_ENABLED=true" -Encoding utf8 }
        return
    }

    $jwtBytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($jwtBytes)
    } finally {
        $generator.Dispose()
    }
    $jwtSecret = [Convert]::ToBase64String($jwtBytes)
    $databasePassword = New-RandomHex 24
    $adminPassword = New-RandomHex 18

    @(
        "DB_NAME=nwtrack"
        "DB_USERNAME=nwtrack_app"
        "DB_PASSWORD=$databasePassword"
        "JWT_SECRET_BASE64=$jwtSecret"
        "BOOTSTRAP_ADMIN_USERNAME=admin"
        "BOOTSTRAP_ADMIN_PASSWORD=$adminPassword"
        "FACTORY_NAME=NW Track Factory"
        "APP_DOMAIN=nowash-track.com"
        "BIND_ADDRESS=127.0.0.1"
        "ALLOWED_SUBNET=127.0.0.1/32"
        "SWAGGER_ENABLED=true"
    ) | Set-Content -LiteralPath $environmentFile -Encoding utf8

    Write-Host "Created secure runtime configuration: $environmentFile" -ForegroundColor Green
    Write-Host "Initial administrator username: admin" -ForegroundColor Yellow
    Write-Host "Initial administrator password: $adminPassword" -ForegroundColor Yellow
    Write-Host "Store this password securely. It will not be printed during later starts." -ForegroundColor Yellow
}

function Get-Settings {
    $settings = @{}
    Get-Content -LiteralPath $environmentFile | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
    }
    return $settings
}

function Set-DeploymentMode {
    $settings = Get-Settings
    if ($settings.HTTPS_ENABLED -eq "true") {
        $env:NGINX_TEMPLATE = "default.conf.template"
        $env:COMPOSE_PROFILES = "https"
        $env:ALLOWED_ORIGINS = "https://$($settings.APP_DOMAIN)"
    } else {
        $env:NGINX_TEMPLATE = "http.conf.template"
        $env:COMPOSE_PROFILES = ""
        $env:ALLOWED_ORIGINS = "http://$($settings.BIND_ADDRESS)"
    }
}

function Ensure-Certificates {
    $settings = Get-Settings
    $domain = if ($settings.APP_DOMAIN) { $settings.APP_DOMAIN } else { "nowash-track.com" }
    if ($domain -notmatch '^[A-Za-z0-9.-]+$') { throw "APP_DOMAIN contains invalid characters: $domain" }
    New-Item -ItemType Directory -Path $certificateDirectory -Force | Out-Null
    $config = @"
[req]
distinguished_name=dn
prompt=no
[dn]
CN=$domain
[v3_server]
subjectAltName=DNS:$domain
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
basicConstraints=critical,CA:FALSE
"@
    Set-Content -LiteralPath (Join-Path $certificateDirectory "openssl.cnf") -Value $config -Encoding ascii
    $required = @("ca.crt","ca.key","server.crt","server.key") | ForEach-Object { Join-Path $certificateDirectory $_ }
    $mount = "${certificateDirectory}:/certs"
    if (($required | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -eq 0) {
        & docker run --rm --entrypoint openssl -v $mount alpine/openssl:3.5.4 x509 -in /certs/server.crt -noout -checkhost $domain *> $null
        if ($LASTEXITCODE -eq 0) { return }
        Remove-Item -LiteralPath (Join-Path $certificateDirectory "server.crt") -Force
        Remove-Item -LiteralPath (Join-Path $certificateDirectory "server.key") -Force
        Write-Host "The configured hostname changed; regenerating the server certificate while preserving the factory CA." -ForegroundColor Yellow
    }
    $command = "set -eu; cd /certs; if [ ! -s ca.key ] || [ ! -s ca.crt ]; then openssl genrsa -out ca.key 4096; openssl req -x509 -new -sha256 -days 3650 -key ca.key -subj '/CN=NW Track Factory Root CA' -out ca.crt; fi; if [ ! -s server.key ] || [ ! -s server.crt ]; then openssl genrsa -out server.key 3072; openssl req -new -key server.key -config openssl.cnf -out server.csr; openssl x509 -req -sha256 -days 825 -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -extfile openssl.cnf -extensions v3_server -out server.crt; rm -f server.csr ca.srl; fi; chmod 600 ca.key server.key"
    & docker run --rm --entrypoint sh -v $mount alpine/openssl:3.5.4 -c $command
    if ($LASTEXITCODE -ne 0) { throw "Could not generate the internal CA and HTTPS certificate." }
    Write-Host "Generated internal HTTPS certificate for $domain." -ForegroundColor Green
}

function Wait-ForApi {
    $settings = Get-Settings
    $bindAddress = if ($settings.BIND_ADDRESS) { $settings.BIND_ADDRESS } else { "127.0.0.1" }
    $domain = if ($settings.APP_DOMAIN) { $settings.APP_DOMAIN } else { "nowash-track.com" }
    $healthUrl = "https://${domain}/actuator/health"
    $ca = Join-Path $certificateDirectory "ca.crt"
    if ($settings.HTTPS_ENABLED -ne "true") { $healthUrl = "http://${bindAddress}/actuator/health" }
    $deadline = (Get-Date).AddMinutes(5)
    Write-Host "Waiting for PostgreSQL, Flyway migrations, and NW Track API..."
    while ((Get-Date) -lt $deadline) {
        try {
            if ($settings.HTTPS_ENABLED -eq "true") {
                $response = & curl.exe --silent --fail --ssl-no-revoke --cacert $ca --resolve "${domain}:443:${bindAddress}" $healthUrl
            } else {
                $response = & curl.exe --silent --fail --noproxy "*" $healthUrl
            }
            if ($LASTEXITCODE -eq 0 -and $response -match '"status"\s*:\s*"UP"') {
                Write-Host "NW Track is ready." -ForegroundColor Green
                return
            }
        } catch {
            Start-Sleep -Seconds 5
        }
        $apiState = & $script:DockerComposeExe ps api --format json 2>$null
        if ($apiState -and $apiState -match '"State":"exited"') {
            & $script:DockerComposeExe logs --tail 100 api
            throw "The NW Track API container stopped during startup."
        }
    }
    & $script:DockerComposeExe logs --tail 100 api
    throw "NW Track did not become healthy within five minutes."
}

function Start-NwTrack {
    Assert-Prerequisites
    New-EnvironmentFile
    Set-DeploymentMode
    if ((Get-Settings).HTTPS_ENABLED -eq "true") { Ensure-Certificates }
    # Preserve available console history before Compose can replace old containers.
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "support.ps1") archive
    if ($LASTEXITCODE -ne 0) { Write-Warning "Log archive was incomplete; deployment will continue. Use support.cmd bundle for diagnostics." }
    & $script:DockerComposeExe --env-file $environmentFile -f $composeFile up -d --build --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Docker Compose reported a startup error. Checking the engine before retrying; existing services and database volumes will be retained."
        & docker version --format '{{.Server.Version}}'
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Desktop is not responding. Restart Docker Desktop, wait until its engine is ready, then run deployment\windows\install.cmd start again."
        }
        & $script:DockerComposeExe --env-file $environmentFile -f $composeFile ps -a
        & $script:DockerComposeExe --env-file $environmentFile -f $composeFile logs --tail 80 api
        Start-Sleep -Seconds 5
        & $script:DockerComposeExe --env-file $environmentFile -f $composeFile up -d --build --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile logs --tail 100 api
            throw "Docker Compose could not start NW Track after one retry. Review the API logs above; if Docker reports a named-pipe/API 500 error, restart Docker Desktop and retry. No database volumes were deleted."
        }
    }
    Wait-ForApi
    & $script:DockerComposeExe ps
    $settings = Get-Settings
    $domain = if ($settings.APP_DOMAIN) { $settings.APP_DOMAIN } else { "nowash-track.com" }
    $baseUrl = if ($settings.HTTPS_ENABLED -eq "true") { "https://${domain}" } else { "http://$($settings.BIND_ADDRESS)" }
    Write-Host "Application:  $baseUrl" -ForegroundColor Cyan
    Write-Host "Swagger UI:  $baseUrl/swagger-ui.html" -ForegroundColor Cyan
    Write-Host "Health:      $baseUrl/actuator/health" -ForegroundColor Cyan
}

Push-Location $projectDirectory
try {
    if (Test-Path -LiteralPath $environmentFile) { Set-DeploymentMode }
    switch ($Action) {
        "init" { New-EnvironmentFile }
        "start" { Start-NwTrack }
        "stop" {
            Assert-Prerequisites
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile down
        }
        "restart" {
            Assert-Prerequisites
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile restart
            Wait-ForApi
        }
        "status" {
            Assert-Prerequisites
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile ps
        }
        "logs" {
            Assert-Prerequisites
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile logs --tail 200 -f
        }
        "certificate" {
            Assert-Prerequisites
            New-EnvironmentFile
            Remove-Item -LiteralPath (Join-Path $certificateDirectory "server.crt") -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $certificateDirectory "server.key") -Force -ErrorAction SilentlyContinue
            Ensure-Certificates
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile up -d --force-recreate nginx
            if ($LASTEXITCODE -ne 0) { throw "Certificate renewed, but Nginx could not be recreated." }
        }
        "reset" {
            Assert-Prerequisites
            if (-not $ConfirmDatabaseDeletion) {
                throw "Reset deletes all NW Track data. Use: .\nwtrack.ps1 reset -ConfirmDatabaseDeletion"
            }
            & $script:DockerComposeExe --env-file $environmentFile -f $composeFile down --volumes
            if ($LASTEXITCODE -ne 0) { throw "Could not remove the existing deployment." }
            Start-NwTrack
        }
    }
} finally {
    Pop-Location
}
