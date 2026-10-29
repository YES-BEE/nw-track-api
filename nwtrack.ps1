param(
    [ValidateSet("start", "stop", "restart", "status", "logs", "reset")]
    [string]$Action = "start",
    [switch]$ConfirmDatabaseDeletion
)

$ErrorActionPreference = "Stop"
$projectDirectory = $PSScriptRoot
$environmentFile = Join-Path $projectDirectory ".env"
$composeFile = Join-Path $projectDirectory "compose.yaml"

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
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker is not installed or docker.exe is not in PATH. Install Docker Desktop, then rerun this script."
    }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is installed but is not running. Start Docker Desktop, then rerun this script."
    }
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose v2 is not available." }
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
        "STORAGE_TOKEN_TRACKING_ENABLED=true"
        "API_PORT=8080"
    ) | Set-Content -LiteralPath $environmentFile -Encoding utf8

    Write-Host "Created secure runtime configuration: $environmentFile" -ForegroundColor Green
    Write-Host "Initial administrator username: admin" -ForegroundColor Yellow
    Write-Host "Initial administrator password: $adminPassword" -ForegroundColor Yellow
    Write-Host "Store this password securely. It will not be printed during later starts." -ForegroundColor Yellow
}

function Wait-ForApi {
    $healthUrl = "http://localhost:8080/actuator/health"
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
        $apiState = docker compose ps api --format json 2>$null
        if ($apiState -and $apiState -match '"State":"exited"') {
            docker compose logs --tail 100 api
            throw "The NW Track API container stopped during startup."
        }
    }
    docker compose logs --tail 100 api
    throw "NW Track did not become healthy within five minutes."
}

function Start-NwTrack {
    Assert-Prerequisites
    New-EnvironmentFile
    docker compose --env-file $environmentFile -f $composeFile up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not start NW Track." }
    Wait-ForApi
    docker compose ps
    Write-Host "Application:  http://localhost:8080" -ForegroundColor Cyan
    Write-Host "Swagger UI:  http://localhost:8080/swagger-ui.html" -ForegroundColor Cyan
    Write-Host "Health:      http://localhost:8080/actuator/health" -ForegroundColor Cyan
}

Push-Location $projectDirectory
try {
    switch ($Action) {
        "start" { Start-NwTrack }
        "stop" {
            Assert-Prerequisites
            docker compose --env-file $environmentFile -f $composeFile down
        }
        "restart" {
            Assert-Prerequisites
            docker compose --env-file $environmentFile -f $composeFile restart
            Wait-ForApi
        }
        "status" {
            Assert-Prerequisites
            docker compose --env-file $environmentFile -f $composeFile ps
        }
        "logs" {
            Assert-Prerequisites
            docker compose --env-file $environmentFile -f $composeFile logs --tail 200 -f
        }
        "reset" {
            Assert-Prerequisites
            if (-not $ConfirmDatabaseDeletion) {
                throw "Reset deletes all NW Track data. Use: .\nwtrack.ps1 reset -ConfirmDatabaseDeletion"
            }
            docker compose --env-file $environmentFile -f $composeFile down --volumes
            if ($LASTEXITCODE -ne 0) { throw "Could not remove the existing deployment." }
            Start-NwTrack
        }
    }
} finally {
    Pop-Location
}
