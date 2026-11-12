# Database backup with the existing installer

Run from the repository directory under the Windows account that can use this
deployment's Docker Desktop engine:

```bat
deployment\windows\install.cmd backup
```

This uses the same entry point as `install.cmd support`. There is no separate batch
file to remember, no manual database credentials to enter and no Administrator prompt
for the backup action. Docker Desktop and this deployment's PostgreSQL container must
be running. API/Nginx can be down. It does not start, stop, rebuild, reset or restore
any service and does not change factory records.

The command prints the completed file's full path:

```text
backups\nwtrack-database-<UTC timestamp>-<unique suffix>.zip
```

The default folder is relative to the repository, even when invoking the installer
by its full path from another directory. To save directly to independent storage:

```bat
deployment\windows\install.cmd backup -BackupDirectory "E:\NWTrack-Backups"
deployment\windows\install.cmd backup -BackupDirectory "\\NAS\FactoryBackups\NWTrack"
```

The destination must be writable by the current Windows user. Relative custom paths
are relative to the command's working directory. Backups are manual; this action does
not install a backup schedule or upload files. Existing scheduled log collection is
unchanged. Old backup ZIPs are never automatically overwritten or deleted.

## Contents and verification

| File | Contents |
| --- | --- |
| `database.dump` | PostgreSQL custom-format backup: complete application database schema/data, Flyway history, counters/sequences, users, styles, lots, workflow and audit records |
| `manifest.json` | Source database/image/tools version, UTC start/finish times, dump size, SHA256 and verification method |
| `RESTORE.txt` | Steps for restoring into a separate empty database on your local/replacement machine |
| `backup-messages.txt` (when present) | Messages returned by PostgreSQL tools; review before relying on the backup |

The script uses `pg_dump` from the running database container, keeping tool versions
compatible and credentials inside that container. It identifies the database by
Compose service and repository working-directory labels, avoiding other deployments
on the same Docker host. No running match, multiple matches or a Docker/database
failure produces a nonzero exit code rather than an empty "successful" backup.

The dump is made while PostgreSQL is online using its consistent snapshot support.
Data committed after the dump snapshot is not included. The script checks the archive
with `pg_restore --list` and a full archive read, copies binary bytes using `docker cp`,
and verifies the dump's SHA256 again from inside the ZIP before publishing the final
filename. An archive-read check is not a full database restore rehearsal.

Temporary files are uniquely named and removed from the database container and host
staging folder when the command finishes. Failed runs retain previous good ZIPs and
never publish their own partial ZIP as a successful backup. A killed process or power
failure can leave `.partial`/`.staging-*` files; only completed `.zip` files are backups.
Space is needed for the temporary dump inside Docker and for staging/ZIP creation at
the destination. Large dumps use streaming ZIP creation rather than Compress-Archive.

The ZIP contains confidential production records and application password hashes.
It does not contain `.env`, database cluster roles/passwords, private certificates,
application images/source or diagnostic logs. Keep deployment configuration and
credentials separately and securely. Database backups are excluded from Git and the
Docker build context; they are not added to support-log ZIPs.

## Recover locally

1. Copy the backup ZIP to the recovery machine and extract it.
2. Compare `Get-FileHash -Algorithm SHA256 -LiteralPath .\database.dump` with
   `dumpSha256` in `manifest.json`.
3. Start a separate compatible PostgreSQL instance (currently PostgreSQL 17), with a
   destination administrator and an empty database. Keep the application stopped.
4. Follow `RESTORE.txt` to copy the dump into that recovery container and run
   `pg_restore --exit-on-error --single-transaction --no-owner --no-privileges` against
   the empty database. Source PostgreSQL cluster users are not restored; destination
   credentials must exist first. The NW Track application users are in the dump.
5. Start the matching application version against the restored database and check
   user access, styles, lot counts and recent changes before replacing the factory server.

The backup command does not perform an automatic restore or overwrite a database.
Rehearse recovery before relying on it. If Docker/PostgreSQL or its disk has already
failed, use a backup created earlier; a fresh logical backup cannot be produced from
an unavailable database.

Copy the complete ZIP to an external disk, NAS or another machine. A backup kept only
on the same server disk will not survive that disk failing. This manual snapshot is
not continuous WAL backup and does not guarantee a five-minute recovery window.

## Developer verification

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File deployment/windows/tests/backup-smoke.ps1
```

Requires Docker and the `postgres:17-alpine` image. The test runs the installer backup
action from a fixture project with spaces in its path, verifies checksum/bundle
contents, restores into an isolated PostgreSQL instance with a different owner, and
checks row values, binary data, Unicode and sequence continuity. It also forces a
fixture dump failure and verifies nonzero exit, temporary-file cleanup and preservation
of the previous good ZIP. Only generated test containers/folders are changed.

PostgreSQL references: [pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html)
and [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html).
