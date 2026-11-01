param(
    [ValidateSet("init", "start", "stop", "restart", "status", "logs", "reset")]
    [string]$Action = "start",
    [switch]$ConfirmDatabaseDeletion
)

$ErrorActionPreference = "Stop"
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentFile = Join-Path $projectDirectory ".env"
$composeFile = Join-Path $projectDirectory "compose.yaml"
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
    if (Test-Path -LiteralPath $environmentFile) { return }

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
        "BIND_ADDRESS=127.0.0.1"
        "ALLOWED_SUBNET=127.0.0.1/32"
        "API_PORT=8080"
        "SWAGGER_ENABLED=false"
    ) | Set-Content -LiteralPath $environmentFile -Encoding utf8

    Write-Host "Created secure runtime configuration: $environmentFile" -ForegroundColor Green
    Write-Host "Initial administrator username: admin" -ForegroundColor Yellow
    Write-Host "Initial administrator password: $adminPassword" -ForegroundColor Yellow
    Write-Host "Store this password securely. It will not be printed during later starts." -ForegroundColor Yellow
}

function Wait-ForApi {
    $settings = @{}
    Get-Content -LiteralPath $environmentFile | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
    }
    $bindAddress = if ($settings.BIND_ADDRESS) { $settings.BIND_ADDRESS } else { "127.0.0.1" }
    $apiPort = if ($settings.API_PORT) { $settings.API_PORT } else { "8080" }
    $healthUrl = "http://${bindAddress}:${apiPort}/actuator/health"
    $deadline = (Get-Date).AddMinutes(5)
    Write-Host "Waiting for PostgreSQL, Flyway migrations, and NW Track API..."
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
            if ($health.status -eq "UP") {
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
    & $script:DockerComposeExe --env-file $environmentFile -f $composeFile up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not start NW Track." }
    Wait-ForApi
    & $script:DockerComposeExe ps
    $settings = @{}
    Get-Content -LiteralPath $environmentFile | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() } }
    $displayAddress = if ($settings.BIND_ADDRESS) { $settings.BIND_ADDRESS } else { "127.0.0.1" }
    $displayPort = if ($settings.API_PORT) { $settings.API_PORT } else { "8080" }
    $baseUrl = "http://${displayAddress}:${displayPort}"
    Write-Host "Application:  $baseUrl" -ForegroundColor Cyan
    Write-Host "Swagger UI:  $baseUrl/swagger-ui.html" -ForegroundColor Cyan
    Write-Host "Health:      $baseUrl/actuator/health" -ForegroundColor Cyan
}

Push-Location $projectDirectory
try {
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
