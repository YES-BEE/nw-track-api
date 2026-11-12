param([string]$BackupDirectory)

if (-not $BackupDirectory) { $BackupDirectory = $env:NWTRACK_BACKUP_DIRECTORY }

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$backupId = [Guid]::NewGuid().ToString("N")
$stamp = [DateTime]::UtcNow.ToString("yyyyMMdd-THHmmss-fffffffZ")
$startedAt = [DateTime]::UtcNow.ToString("o")
$utf8 = New-Object Text.UTF8Encoding($false)
$containerId = $null
$staging = $null
$partialZip = $null
$succeeded = $false
$remoteRunner = "/tmp/nwtrack-backup-$backupId.sh"
$remoteDump = "/tmp/nwtrack-backup-$backupId.dump"

function Invoke-BackupDocker([string[]]$DockerArguments) {
    # Windows PowerShell 5 treats native stderr as ErrorRecord objects. Check the
    # actual exit code while retaining useful pg_dump/docker error messages.
    $ErrorActionPreference = "Continue"
    $output = @(& docker @DockerArguments 2>&1)
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "Docker/database command failed (exit $code): $($output -join [Environment]::NewLine)" }
    foreach ($line in $output) { $line.ToString() }
}

try {
    # Reuse CLI discovery without running deployment actions or reading .env.
    $tokens=$null; $parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot "nwtrack.ps1"),[ref]$tokens,[ref]$parseErrors)
    if ($parseErrors.Count) { throw "Cannot read Docker CLI discovery from nwtrack.ps1." }
    $pathFunction=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq "Add-DockerCliToPath"},$true)
    if (-not $pathFunction) { throw "Docker CLI discovery function is missing from nwtrack.ps1." }
    . ([scriptblock]::Create($pathFunction.Extent.Text))
    Add-DockerCliToPath
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker CLI was not found. Install/start Docker Desktop and retry install.cmd backup." }

    $candidates = @(Invoke-BackupDocker -DockerArguments @("ps", "--filter", "label=com.docker.compose.service=database", "--format", "{{.ID}}"))
    $matching = @()
    foreach ($candidate in $candidates) {
        if ($candidate -notmatch '^[0-9a-f]{12,64}$') { continue }
        $labels = ((Invoke-BackupDocker -DockerArguments @("inspect", "--format", "{{json .Config.Labels}}", $candidate)) -join "") | ConvertFrom-Json
        $working = [string]$labels.'com.docker.compose.project.working_dir'
        if ($working.Trim().Replace('/','\').TrimEnd('\') -ieq $projectRoot.Replace('/','\').TrimEnd('\')) { $matching += $candidate }
    }
    if ($matching.Count -ne 1) { throw "Expected one running NW Track database for $projectRoot; found $($matching.Count). Start this deployment's database and retry. Other deployments will not be backed up." }
    $containerId = $matching[0]

    if (-not $BackupDirectory) { $BackupDirectory = Join-Path $projectRoot "backups" }
    $outputRoot = [IO.Path]::GetFullPath($BackupDirectory)
    $outputComparison = $outputRoot.TrimEnd([char[]]'\/')
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    $staging = Join-Path $outputRoot ".staging-$backupId"
    $payload = Join-Path $staging "payload"
    New-Item -ItemType Directory -Path $payload -Force | Out-Null
    $runnerPath = Join-Path $staging "backup-run.sh"
    # A copied POSIX script avoids native Windows quoting problems and keeps the
    # database password entirely inside its container. Binary dump bytes are never
    # piped through PowerShell or redirected with >.
    $runner = @'
#!/bin/sh
set -eu
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
export PGPASSWORD="${POSTGRES_PASSWORD:-}"
export PGCONNECT_TIMEOUT=15
pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --file="$1"
pg_restore --list "$1" >/dev/null
pg_restore --file=/dev/null "$1"
'@
    [IO.File]::WriteAllText($runnerPath,($runner.Replace("`r`n","`n")+"`n"),$utf8)
    Write-Host "Creating an online PostgreSQL backup..."
    Invoke-BackupDocker -DockerArguments @("cp", $runnerPath, "${containerId}:$remoteRunner") | Out-Null
    $messages = @(Invoke-BackupDocker -DockerArguments @("exec", $containerId, "sh", $remoteRunner, $remoteDump))
    if ($messages.Count) {
        [IO.File]::WriteAllText((Join-Path $payload "backup-messages.txt"),($messages -join [Environment]::NewLine),$utf8)
        Write-Warning "PostgreSQL returned messages; review backup-messages.txt inside the bundle."
    }
    $dumpPath = Join-Path $payload "database.dump"
    Invoke-BackupDocker -DockerArguments @("cp", "${containerId}:$remoteDump", $dumpPath) | Out-Null
    if (-not (Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -le 5) { throw "The database dump is missing or empty." }
    $checksum = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
    $database = (Invoke-BackupDocker -DockerArguments @("exec", $containerId, "printenv", "POSTGRES_DB")) -join ""
    $version = (Invoke-BackupDocker -DockerArguments @("exec", $containerId, "pg_dump", "--version")) -join ""
    $image = (Invoke-BackupDocker -DockerArguments @("inspect", "--format", "{{.Config.Image}}", $containerId)) -join ""
    $manifest = [ordered]@{
        format="pg_dump-custom"; database=$database; postgresToolsVersion=$version; containerImage=$image
        startedAtUtc=$startedAt; finishedAtUtc=[DateTime]::UtcNow.ToString("o")
        dumpFile="database.dump"; dumpBytes=(Get-Item -LiteralPath $dumpPath).Length; dumpSha256=$checksum
        verification="pg_restore list and complete archive read; SHA256 checked after ZIP creation"
        excludes=@(".env and deployment secrets", "PostgreSQL cluster roles/passwords", "certificates", "application images/source", "diagnostic logs")
    }
    [IO.File]::WriteAllText((Join-Path $payload "manifest.json"),($manifest | ConvertTo-Json -Depth 4),$utf8)
    $restoreText = @'
NW Track database backup

database.dump contains the database schema, application data, Flyway history and
sequence values. manifest.json records its SHA256 checksum and PostgreSQL tools version.
The backup contains confidential business data and application password hashes.

RESTORE INTO A SEPARATE EMPTY DATABASE, not the running factory database:
1. Extract this ZIP and verify database.dump using:
   Get-FileHash -Algorithm SHA256 -LiteralPath .\database.dump
   Compare with dumpSha256 in manifest.json.
2. Start a separate compatible PostgreSQL instance (currently PostgreSQL 17).
   Create the destination user and an empty database first. Keep the API stopped
   until restoration finishes, so bootstrap/migrations cannot populate the database.
3. Copy the dump to that recovery container:
   docker cp database.dump <recovery-container>:/tmp/database.dump
4. Restore using the destination's administrator and empty database:
   docker exec <recovery-container> pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username=<destination-user> --dbname=<empty-database> /tmp/database.dump
   Supply authentication using the recovery instance's configuration if required.
5. Start the matching NW Track API version against that restored database and verify
   users, styles, lots and recent changes before using it as a replacement server.

This bundle does not include .env, deployment credentials, cluster roles, certificates,
application images/source or diagnostic logs. Keep these separately and securely.
Archive-read and checksum checks detect damaged/unreadable archives, but do not replace
a restore rehearsal. Data committed after pg_dump's snapshot is not in this backup.

Copy the complete ZIP to a separate disk/NAS/another machine. A backup kept only on
the factory server's disk will not survive failure of that disk.
'@
    [IO.File]::WriteAllText((Join-Path $payload "RESTORE.txt"),$restoreText,$utf8)
    $name = "nwtrack-database-$stamp-$($backupId.Substring(0,8)).zip"
    $finalZip = Join-Path $outputRoot $name
    $partialZip = "$finalZip.partial"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    # The dump is already compressed. ZipFile streams entries and supports ZIP64;
    # avoid Compress-Archive's large-file limitations for production databases.
    [IO.Compression.ZipFile]::CreateFromDirectory($payload,$partialZip,[IO.Compression.CompressionLevel]::NoCompression,$false)
    $archive = [IO.Compression.ZipFile]::OpenRead($partialZip)
    try {
        $entry = $archive.GetEntry("database.dump")
        if (-not $entry) { throw "The ZIP does not contain database.dump." }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $zipHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace("-","") }
        finally { $sha.Dispose(); $stream.Dispose() }
        if ($zipHash -ne $checksum) { throw "The dump checksum changed during ZIP creation." }
    } finally { $archive.Dispose() }
    [IO.File]::Move($partialZip,$finalZip)
    $succeeded = $true
    Write-Host "Database backup: $finalZip" -ForegroundColor Green
    Write-Host "Dump SHA256: $checksum"
    Write-Host "Copy this ZIP to independent storage. Restore instructions are inside RESTORE.txt."
} catch {
    Write-Error "Database backup failed: $($_.Exception.Message)" -ErrorAction Continue
} finally {
    if ($containerId) {
        try { Invoke-BackupDocker -DockerArguments @("exec", $containerId, "rm", "-f", "--", $remoteRunner, $remoteDump) | Out-Null }
        catch { Write-Warning "Temporary backup files could not be removed from the database container: $($_.Exception.Message)" }
    }
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        # Recursive cleanup is limited to our exact generated child directory.
        $resolved = [IO.Path]::GetFullPath($staging)
        $item = Get-Item -LiteralPath $resolved
        if (([IO.Path]::GetDirectoryName($resolved)).TrimEnd([char[]]'\/') -ieq $outputComparison -and $item.Name -eq ".staging-$backupId" -and -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        } else { Write-Warning "Unexpected staging path; retained for manual inspection: $resolved" }
    }
    if ($partialZip -and (Test-Path -LiteralPath $partialZip) -and ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($partialZip))).TrimEnd([char[]]'\/') -ieq $outputComparison) {
        Remove-Item -LiteralPath $partialZip -Force
    }
}
if (-not $succeeded) { exit 1 }
