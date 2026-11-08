param(
    [ValidateSet("bundle", "archive", "schedule")][string]$Action = "bundle",
    [ValidateRange(1,720)][int]$SinceHours = 24,
    [ValidateRange(1,365)][int]$RetentionDays = 30,
    [ValidateRange(1,100)][int]$ArchiveSizeGB = 2,
    [ValidateRange(1,60)][int]$IntervalMinutes = 5
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$logRoot = Join-Path $root "logs"
$archiveRoot = Join-Path $logRoot "console-archive"
$bundleRoot = Join-Path $root "support-bundles"
$stamp = [DateTime]::UtcNow.ToString("yyyyMMdd-THHmmss-fffffffZ")
$utf8 = New-Object Text.UTF8Encoding($false)

# Load only function definitions: do not execute deployment or read/export .env.
$deployTokens=$null; $deployErrors=$null
$deployAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'nwtrack.ps1'),[ref]$deployTokens,[ref]$deployErrors)
$pathFunction=$deployAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Add-DockerCliToPath'},$true)
if ($pathFunction) { . ([scriptblock]::Create($pathFunction.Extent.Text)); Add-DockerCliToPath }

if ($Action -eq "schedule") {
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" archive -RetentionDays ' + $RetentionDays + ' -ArchiveSizeGB ' + $ArchiveSizeGB
    $taskAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arguments -WorkingDirectory $root
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 4)
    Register-ScheduledTask -TaskName "NWTrack-ArchiveLogs" -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Write-Host "Scheduled console-log archiving every $IntervalMinutes minutes for $identity while logged in. Docker Desktop must be running."
    exit 0
}

New-Item -ItemType Directory -Path $archiveRoot,$bundleRoot -Force | Out-Null
$capture = Join-Path $archiveRoot $stamp
New-Item -ItemType Directory -Path $capture -Force | Out-Null
$failures = New-Object 'Collections.Generic.List[string]'
function Capture-Command([string]$Name,[scriptblock]$Command) {
    # docker logs writes application stderr on stderr; retain it as log text in Windows PowerShell 5.
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Command 2>&1 | Out-String -Width 240
        $code = $LASTEXITCODE
        [IO.File]::WriteAllText((Join-Path $capture $Name),$output,$utf8)
        if ($code -ne 0) { $failures.Add("$Name exited $code") }
    } catch {
        $failures.Add("${Name}: $($_.Exception.Message)")
        [IO.File]::WriteAllText((Join-Path $capture $Name),$_.Exception.Message,$utf8)
    }
}

$since = [DateTime]::UtcNow.AddHours(-$SinceHours)
$cursorFile = Join-Path $archiveRoot "cursor.txt"
if ($Action -eq "archive" -and (Test-Path -LiteralPath $cursorFile)) {
    $previous = [DateTime]::Parse([IO.File]::ReadAllText($cursorFile)).ToUniversalTime().AddSeconds(-10)
    if ($previous -gt $since) { $since = $previous }
}
$until = [DateTime]::UtcNow
$sinceText = $since.ToString("yyyy-MM-ddTHH:mm:ssZ")
$untilText = $until.ToString("yyyy-MM-ddTHH:mm:ssZ")

Capture-Command "docker-version.txt" { docker version }
Capture-Command "container-status.txt" { docker ps -a --filter "label=com.docker.compose.project.working_dir=$root" --format '{{.Names}} | {{.Status}} | {{.Ports}}' }
foreach ($service in @("api","nginx","database","dns")) {
    # Discover by service and compose working directory, not project-folder name.
    try {
        $ids = @(docker ps -aq --filter "label=com.docker.compose.service=$service" 2>$null)
        foreach ($id in $ids) {
            $labels = docker inspect --format '{{json .Config.Labels}}' $id 2>$null | ConvertFrom-Json
            $working = $labels.'com.docker.compose.project.working_dir'
            if (-not $working -or $working.Trim().Replace('/','\').TrimEnd('\') -ine $root.Replace('/','\').TrimEnd('\')) { continue }
            $shortId=$id.Substring(0,12)
            Capture-Command "$service-$shortId.log" { docker logs --timestamps --since $sinceText --until $untilText --tail 100000 $id }
            # Deliberately exclude Config.Env, full inspect/config, mounts and health output.
            Capture-Command "$service-$shortId-state.txt" { docker inspect --format '{{.Name}} status={{.State.Status}} restarts={{.RestartCount}} started={{.State.StartedAt}} image={{.Image}}' $id }
        }
    } catch { $failures.Add("${service}: $($_.Exception.Message)") }
}
if ($failures.Count -eq 0) { [IO.File]::WriteAllText($cursorFile,$until.ToString("o"),$utf8) }
[IO.File]::WriteAllText((Join-Path $capture "capture.txt"),"UTC window: $sinceText to $untilText`r`nFailures: $($failures -join '; ')`r`nPer-container console tail limit: 100000 lines. No .env or database files collected.",$utf8)

if ($Action -eq "bundle") {
    $staging = Join-Path $bundleRoot $stamp
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    Copy-Item -LiteralPath $archiveRoot -Destination (Join-Path $staging "console-archive") -Recurse
    $apiLogs=Join-Path $logRoot "api"
    if (Test-Path -LiteralPath $apiLogs) {
        $apiDestination=Join-Path $staging "api"
        New-Item -ItemType Directory -Path $apiDestination -Force | Out-Null
        Get-ChildItem -LiteralPath $apiLogs -File | Where-Object { $_.Name -match '^nwtrack.*\.(log|gz)$' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $apiDestination }
    }
    [IO.File]::WriteAllText((Join-Path $staging "README.txt"),"NW Track support bundle created $stamp UTC.`r`nContains retained API/console logs, version and limited status diagnostics. No .env, JWT keys, private certificates, Docker environment dumps or database backup collected.`r`nLogs may contain business data or sensitive messages; review before sharing.`r`nCollector errors: $($failures -join '; ')",$utf8)
    $zip=Join-Path $bundleRoot "nwtrack-support-$stamp.zip"
    Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -CompressionLevel Optimal
    Write-Host "Support bundle: $zip"
    Write-Host "Staging files: $staging (retained for review; not automatically deleted)."
}

# Delete only individually verified archive directories under our dedicated archive root.
$limit = [long]$ArchiveSizeGB * 1GB
$archives=@(Get-ChildItem -LiteralPath $archiveRoot -Directory | Sort-Object Name)
$total=[long](($archives | ForEach-Object { (Get-ChildItem -LiteralPath $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum } | Measure-Object -Sum).Sum)
foreach ($entry in $archives) {
    if ($entry.Name -eq $stamp) { continue }
    if ($entry.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-$RetentionDays) -or $total -gt $limit) {
        $resolved=[IO.Path]::GetFullPath($entry.FullName)
        if ([IO.Path]::GetDirectoryName($resolved) -ine [IO.Path]::GetFullPath($archiveRoot) -or $entry.Name -notmatch '^\d{8}-T\d{6}-\d{7}Z$' -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Unexpected archive path: $resolved" }
        $size=[long](Get-ChildItem -LiteralPath $resolved -Recurse -File | Measure-Object Length -Sum).Sum
        Remove-Item -LiteralPath $resolved -Recurse -Force
        $total-=$size
    }
}
Write-Host "Console capture: $capture"
if ($failures.Count) { Write-Warning "Partial capture: $($failures -join '; ')"; exit 1 }
