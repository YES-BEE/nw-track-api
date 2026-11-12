# Requires Docker Desktop and the postgres:17-alpine image. Uses only generated,
# isolated containers and temporary test folders; never changes a deployed database.
$ErrorActionPreference="Stop"
$root=(Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$testId=[Guid]::NewGuid().ToString("N")
$testRoot=Join-Path $root "target\backup-smoke-$testId"
$project=Join-Path $testRoot "source project"
$scripts=Join-Path $project "deployment\windows"
$output=Join-Path $testRoot "backup output"
$sourceName="nwtrack-backup-smoke-source-$testId"
$restoreName="nwtrack-backup-smoke-restore-$testId"
$containers=@()
function Test-Docker([string[]]$DockerArguments){
    $ErrorActionPreference="Continue"
    $result=@(& docker @DockerArguments 2>&1);$code=$LASTEXITCODE
    if($code -ne 0){throw "Docker test command failed: $($result -join [Environment]::NewLine)"}
    foreach($line in $result){$line.ToString()}
}
function Wait-TestDatabase([string]$Name,[string]$User){
    for($attempt=0;$attempt -lt 60;$attempt++){
        $ErrorActionPreference="Continue"
        & docker exec $Name pg_isready --username $User 2>&1 | Out-Null
        if($LASTEXITCODE -eq 0){return}
        Start-Sleep -Milliseconds 500
    }
    throw "Test PostgreSQL did not become ready: $Name"
}
try{
    New-Item -ItemType Directory -Path $scripts -Force | Out-Null
    foreach($file in @("backup.ps1","install.cmd","install.ps1","nwtrack.ps1")){
        Copy-Item -LiteralPath (Join-Path $root "deployment\windows\$file") -Destination (Join-Path $scripts $file)
    }
    Test-Docker -DockerArguments @("image","inspect","postgres:17-alpine","--format","{{.Id}}") | Out-Null
    $containers+=$sourceName
    Test-Docker -DockerArguments @("run","--detach","--name",$sourceName,"--network","none","--tmpfs","/var/lib/postgresql/data:rw,size=256m","--label","com.docker.compose.service=database","--label","com.docker.compose.project.working_dir=$project","--env","POSTGRES_DB=backup_fixture","--env","POSTGRES_USER=postgres","--env","POSTGRES_PASSWORD=backup-smoke-only","postgres:17-alpine") | Out-Null
    Wait-TestDatabase $sourceName "postgres"
    $sql="create table backup_probe(id serial primary key,name text,payload bytea,quantity integer); insert into backup_probe(name,payload,quantity) values ('row-one',decode('00ff00','hex'),1),(chr(9731),decode('ff00ff','hex'),2),('row-three',decode('abcdef','hex'),3);"
    Test-Docker -DockerArguments @("exec",$sourceName,"psql","-X","-q","-v","ON_ERROR_STOP=1","-U","postgres","-d","backup_fixture","-c",$sql) | Out-Null
    # Exercise the same install.cmd entry point used by the factory operator.
    & (Join-Path $scripts "install.cmd") backup -BackupDirectory ($output+[IO.Path]::DirectorySeparatorChar)
    if($LASTEXITCODE -ne 0){throw "install.cmd backup failed"}
    $zips=@(Get-ChildItem -LiteralPath $output -Filter "nwtrack-database-*.zip")
    if($zips.Count -ne 1){throw "Expected exactly one completed backup ZIP"}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $extract=Join-Path $testRoot "extracted"
    [IO.Compression.ZipFile]::ExtractToDirectory($zips[0].FullName,$extract)
    $manifest=Get-Content -LiteralPath (Join-Path $extract "manifest.json") -Raw | ConvertFrom-Json
    $dump=Join-Path $extract "database.dump"
    if($manifest.database -ne "backup_fixture" -or (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash -ne $manifest.dumpSha256){throw "Backup metadata/checksum verification failed"}
    if(-not (Test-Path -LiteralPath (Join-Path $extract "RESTORE.txt"))){throw "Restore instructions missing"}
    $containers+=$restoreName
    Test-Docker -DockerArguments @("run","--detach","--name",$restoreName,"--network","none","--tmpfs","/var/lib/postgresql/data:rw,size=256m","--env","POSTGRES_DB=recovered","--env","POSTGRES_USER=recovery_admin","--env","POSTGRES_PASSWORD=backup-smoke-only","postgres:17-alpine") | Out-Null
    Wait-TestDatabase $restoreName "recovery_admin"
    Test-Docker -DockerArguments @("cp",$dump,"${restoreName}:/tmp/database.dump") | Out-Null
    Test-Docker -DockerArguments @("exec",$restoreName,"pg_restore","--exit-on-error","--single-transaction","--no-owner","--no-privileges","-U","recovery_admin","-d","recovered","/tmp/database.dump") | Out-Null
    $query="select count(*)||':'||sum(quantity) from backup_probe; select encode(payload,'hex') from backup_probe where id=1; select encode(convert_to(name,'UTF8'),'hex') from backup_probe where id=2; insert into backup_probe(name,quantity) values ('after restore',4) returning id;"
    $actual=@(Test-Docker -DockerArguments @("exec",$restoreName,"psql","-X","-qAt","-v","ON_ERROR_STOP=1","-U","recovery_admin","-d","recovered","-c",$query))
    if(($actual -join ',') -ne '3:6,00ff00,e29883,4'){throw "Restored data, binary values, Unicode or sequence state differs: $($actual -join ',')"}
    Write-Host "PASS: backup ZIP restored into an isolated PostgreSQL instance with a different owner; rows, binary data, Unicode and sequences match."
    # Only the generated source fixture is changed to exercise pg_dump failure.
    Test-Docker -DockerArguments @("exec",$sourceName,"psql","-X","-q","-v","ON_ERROR_STOP=1","-U","postgres","-d","postgres","-c","alter database backup_fixture rename to fixture_unavailable;") | Out-Null
    $ErrorActionPreference="Continue"
    $failureOutput=@(& (Join-Path $scripts "install.cmd") backup -BackupDirectory ($output+[IO.Path]::DirectorySeparatorChar) 2>&1)
    $failureCode=$LASTEXITCODE
    $ErrorActionPreference="Stop"
    if($failureCode -eq 0 -or ($failureOutput -join "`n") -notmatch 'Database backup failed'){throw "A failed dump was not reported as failure"}
    if(@(Get-ChildItem -LiteralPath $output -Filter "nwtrack-database-*.zip").Count -ne 1){throw "Failed backup created a completed ZIP or removed the previous good backup"}
    if(@(Get-ChildItem -LiteralPath $output -Force | Where-Object {$_.Name -like '*.partial' -or $_.Name -like '.staging-*'}).Count){throw "Failed backup staging files were not cleaned"}
    if(@(Test-Docker -DockerArguments @("exec",$sourceName,"find","/tmp","-maxdepth","1","-name","nwtrack-backup-*")).Count){throw "Container temporary files were not cleaned"}
    Write-Host "PASS: failed pg_dump exits nonzero, preserves the last good backup and cleans only its own temporary files."
}finally{
    foreach($name in $containers){
        if($name -notmatch ('^nwtrack-backup-smoke-(source|restore)-'+[Regex]::Escape($testId)+'$')){throw "Unexpected test container name"}
        & docker rm --force --volumes $name 2>&1 | Out-Null
    }
    if(Test-Path -LiteralPath $testRoot){
        $resolved=[IO.Path]::GetFullPath($testRoot);$parent=[IO.Path]::GetFullPath((Join-Path $root "target"))
        if([IO.Path]::GetDirectoryName($resolved) -ine $parent -or (Get-Item -LiteralPath $resolved).Name -ne "backup-smoke-$testId" -or ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Unsafe test cleanup path"}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
