param([switch]$ConfirmDatabaseDeletion)
$ErrorActionPreference = "Stop"
if (-not $ConfirmDatabaseDeletion) {
    throw "This deletes all NW Track data. Run: .\reset-database.ps1 -ConfirmDatabaseDeletion"
}
Push-Location $PSScriptRoot
try {
    docker compose down --volumes
    docker compose up -d --build
} finally {
    Pop-Location
}
