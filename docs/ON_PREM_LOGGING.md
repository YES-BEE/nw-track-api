# On-prem log retention and support collection

Run commands below from the repository directory on the on-prem Windows server. No internet upload or database backup is performed by the collector.

For database data, use the same installer with `deployment\windows\install.cmd backup`.
See [database backup and recovery](DATABASE_BACKUP.md). Database backups are separate
from support-log bundles.

## One-time setup

Deploy the logging change through the normal lifecycle:

```cmd
deployment\windows\install.cmd start
```

The API now writes console logs plus rolling files in **`logs/api/` on the host**, mounted into the container. Files survive API container recreation and Compose down/up. Normal deployment also captures available old console logs before replacing containers. This does not recover history already deleted before this change.

The same `install.cmd start` command automatically enables periodic collection of **API, Nginx, PostgreSQL and optional DNS container console logs** after the service health check succeeds. No second setup command is required. Repeated installation updates the same task rather than creating duplicates.

The installer installs/replaces the `NWTrack-ArchiveLogs` Windows Scheduled Task for the current user, every five minutes. Run it under the account that runs Docker Desktop and has access to its engine. It runs only while that user is logged in; a Windows service/server deployment needs its own always-on collection account/agent. The task performs no container start/stop/rebuild or database changes. A scheduling failure is reported explicitly even if the API is already running; rerun install.cmd start after correcting the task/account error.

## Extract a ZIP when there is an issue

```cmd
deployment\windows\install.cmd support
```

Share the resulting **`support-bundles/nwtrack-support-<UTC timestamp>.zip`** with your technical team after reviewing its contents. The command works from another directory too, through the script's absolute path. It attempts a partial bundle even when Docker is unavailable; failures appear in capture.txt and the script exits 1 for an incomplete capture.

For a different console time window:

```cmd
deployment\windows\install.cmd support -SinceHours 72
```

The ZIP includes retained API rolling files, retained console snapshots, a fresh console capture, Docker client/server versions, selected container state/image/restart information and capture errors. `SinceHours` controls the fresh console query; it does not exclude older retained API files/snapshots from the bundle. Container status is discovered using Compose labels; no entire Docker configuration or inspect dump is exported.

**Not collected:** `.env`, environment-variable dumps, private certificates, JWT signing keys, database data/backup or a disk image. Logs themselves may contain usernames, IPs, request/business data or sensitive exception messages; the collector does not promise automatic redaction. Keep normal logging at INFO, avoid request-body/Authorization logging, and review bundles before sharing. No automatic upload is configured.

## Retention and limits

| Log source | Storage | Default retention |
| --- | --- | --- |
| API persistent rolling log | logs/api/nwtrack.log plus dated gzip archives | 20 MB rollover, up to 30 daily periods and 2 GB history cap |
| All services' console snapshots | logs/console-archive/<UTC timestamp>/ | Up to 30 days / 2 GB; oldest snapshots removed first |
| Docker's own console buffer | Docker-managed storage | Existing size-based rotation, about 50 MB per main container / 30 MB DNS; not guaranteed days |
| Exported bundles and staging copies | support-bundles/ | Kept until manually removed after transfer/review |

Caps are not a promise of 30 days: high volume can remove older logs sooner. Spring retention applies at rollover/startup; the current active log is additional to the history cap. Console pruning happens during collection and retains the latest capture even if it exceeds the configured cap. Bundle/staging copies require separate disk monitoring and manual cleanup. Compress-Archive has platform file-size limits, so keep individual retained files below 2 GB.

Configure API logging in the server `.env`, then redeploy:

```dotenv
LOG_MAX_FILE_SIZE=20MB
LOG_HISTORY_DAYS=30
LOG_TOTAL_SIZE_CAP=2GB
```

Configure console archiving when creating/updating the task:

```cmd
deployment\windows\support.cmd schedule -IntervalMinutes 5 -RetentionDays 30 -ArchiveSizeGB 2
```

Manual capture without a ZIP:

```cmd
deployment\windows\support.cmd archive -SinceHours 24 -RetentionDays 30 -ArchiveSizeGB 2
```

Archiving uses a UTC cursor with a ten-second overlap to reduce boundary loss; duplicate lines between snapshots are expected. Each container capture is limited to its last 100,000 available lines in the window. The task cannot recover logs Docker already rotated away or a container deleted before capture; a task gap beyond 24 hours captures only the most recent 24 hours by default. Persistent API files protect application history independently of the task, but Nginx/PostgreSQL/DNS older console history depends on timely successful captures. This is bounded local troubleshooting retention, not a lossless audit/archive system.

For a mandatory lossless retention period, use a separately designed continuously running log collector and protected storage, sized for the site's actual volume. Back up logs to the site's approved NAS/offline backup if they must survive host disk failure. Host-local files do not survive disk loss. Application database audit tables are separate from these diagnostic logs.

## Operational checks

- Confirm logs/api/nwtrack.log is created after redeployment. Docker Desktop bind mounts normally use host directory access; verify write access for the API user (UID 10001).
- In Task Scheduler, inspect NWTrack-ArchiveLogs last-run result and logs/console-archive for new timestamps. Result 0 means capture succeeded; 1 means partial/failed capture.
- Check remaining disk space regularly. The configured API+console history is approximately 4 GB plus active files, current capture, bundles and staging copies.
- Remove the task when decommissioning: `Unregister-ScheduledTask -TaskName NWTrack-ArchiveLogs -Confirm:$false` in PowerShell. Do not delete log folders before collecting an incident bundle.
- For live diagnosis: `docker compose logs --timestamps --tail 200 -f api`.

On Linux, the same Compose API bind mount works, but create/chown the host directory for UID 10001 before starting: `sudo install -d -o 10001 -g 10001 logs/api`. The Windows collector/task is Windows-specific; use an approved Linux scheduler/collector for console retention. Compose still provides `docker compose logs` for manual extraction.
