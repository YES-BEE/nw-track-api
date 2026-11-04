# Configuration v8: implemented design

Updated 4 November 2026. The authoritative current change contract is [Process-only assignments and mock configuration](PROCESS_ASSIGNMENT_V5.md). It supersedes separate station assignments and extra mandatory configuration fields.

| Mock / use case | Current API behavior |
|---|---|
| User process assignment | `processAssignments: [{processId, activeOnShift}]`; multiple processes permitted |
| User station context | Derived from each assigned process; no independent station mapping |
| Login, user reads, NFC, sync | Process IDs/names, derived station IDs/numbers, and shift state |
| Process Name / Description / Station Number | `name`, `description`, `stationNumber`; station UUID is created or reused |
| Process-level NFC | `nfcMode=PROCESS`, one `nfcUid` or selected registry `tagId`, no devices |
| Device-level NFC | `nfcMode=DEVICES`, `deviceCount`, named/tagged `devices`; count must match |
| Additional process/device metadata | Code generated; type defaults to GENERAL; existing code/type/quality preserved when omitted on edit |
| Storage Number / Description / Station Number / NFC | `storageNumber`, `description`, `stationNumber`, `nfcUid` or registry `tagId`; name defaults to number |
| Tag Name / Actual NFC ID | `name`, `uid` (also accepts `tagName`, physical `tagId`) |
| Tag lifecycle | ACTIVE at creation; DELETED after soft deletion; assignment is separate `assigned` boolean |
| Tag choices | GET `/api/v1/nfc-tags?status=ACTIVE&assigned=false` |
| CRUD deletion | History retained; asset tags unassigned; an assigned tag cannot itself be deleted |
| Final QC | Counts sum to lot quantity; reject remarks required; current lot closes; rework separate |

Seven fixed roles remain NW, TOP_MANAGER, PRODUCTION_MANAGER, PROCESS_MANAGER, SUPERVISOR, ADMIN_IT and STATION_0. Role/channel permissions remain editable; scope is derived from processes. Non-admin PC sessions cannot execute Pad operations. Native authenticated STOMP subscriptions and browser REST polling retain the same process-derived station scope.

Flyway V5 preserves existing process assignments and copies matching station on-shift flags onto them, then drops the old station-assignment table. Tag lifecycle is migrated from the previous enabled state into an extensible status lookup. Prior migrations and historical records remain intact. Global roles retain their previously agreed access. Biometrics and automatic Pad logout remain deferred.

The [HTML design](../NWTrack_API_Design.html), [editable architecture](../NWTrack_Arch.xml), [integration guide](API_INTEGRATION_GUIDE.md), and [script-free Postman handoff](postman/README.md) describe the same contract.
