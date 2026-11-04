# Process-only assignments and mock configuration contract

Confirmed 4 November 2026. This revision supersedes the earlier requirement for separate station assignments and additional required form fields.

## User identity and access

A user has one of the seven fixed roles and zero or more process assignments. Each assignment contains `processId` and `activeOnShift`. The station is always joined from the process; there is no user-to-station assignment table or request field. Station lists, storage access, reporting, notifications and subscriptions derive their station scope from active assigned processes. A user cannot operate an off-shift process just because another process at the same station is on shift.

```json
{
  "username": "manager01",
  "displayName": "Process Manager",
  "initialPassword": "<choose-a-password>",
  "role": "PROCESS_MANAGER",
  "processAssignments": [
    {"processId": "<uuid>", "activeOnShift": true}
  ]
}
```

POST `/api/v1/users` creates the account. GET collection/item reads it; PUT `/users/{id}` updates profile, role and assignments; PUT `/users/{id}/assignments` replaces the assignment list. Sending an empty list removes assignments. DELETE `/users/{id}` disables the account while retaining history. Password/role/status endpoints remain available.

Login, refresh, `/auth/me`, user reads and sync bootstrap return `processAssignments`. Each item contains `processId`, `processName`, `stationId`, `stationNumber`, `activeOnShift`, `processActive`, and `stationActive`. These are current database values, not frozen station claims in the JWT. A process station change therefore updates derived user scope without editing the account. Factory-wide role privileges remain as documented in the access matrix.

Process/device NFC resolution returns the owning process and its station, along with matching user assignments. A storage/station scan returns the station and matching assignments without inventing a single process when several are assigned.

## Process and device forms

Only the mock fields are required. A process code is generated on creation and retained on update if omitted. A device type defaults to `GENERAL`; its station is derived from its owning process.

```json
{
  "name": "Preparation",
  "description": "Sort garments",
  "stationNumber": "01",
  "nfcMode": "PROCESS",
  "nfcUid": "NFC-PROC-001",
  "deviceCount": 0,
  "devices": []
}
```

```json
{
  "name": "Washing",
  "description": "Main washing process",
  "stationNumber": "03",
  "nfcMode": "DEVICES",
  "deviceCount": 2,
  "devices": [
    {"name": "Washer 1", "description": "Main", "nfcUid": "NFC-DEV-001"},
    {"name": "Washer 2", "nfcUid": "NFC-DEV-002"}
  ]
}
```

PROCESS requires one process tag and no devices; DEVICES requires 1–50 devices and no process tag. Each device needs a name and tag. When supplied, `deviceCount` must equal the array length. Responses always return the computed count. For updates, include an existing device `id` to retain its identity; removed devices are deactivated and their tags unassigned. Existing `stationReference`, lowercase mock `mode`, `processTag`, and device `tag` inputs remain accepted aliases.

POST/GET/PUT/DELETE `/processes` and the item/status routes use this model. Standalone machine CRUD requires an owning `processId`, name and NFC selection; no station or type input is required. Start scans one process tag or one owned device according to mode; completion needs no rescan. Final quality counts and remarks retain the previously agreed behavior.

## Storage form

```json
{
  "storageNumber": "ST-001",
  "description": "Incoming storage",
  "stationNumber": "03",
  "nfcUid": "NFC-STORAGE-001"
}
```

The area name defaults to its storage number. Station resolution creates a UUID once or reuses the case-insensitive reference. No prior station CRUD is required. POST/GET/PUT/DELETE `/storage-areas` support the same fields; deleting an unoccupied area deactivates its units and unassigns their tags. Occupied areas cannot be deleted. An area with units cannot be moved to a different station. Legacy `code`, `name`, `stationReference`, `stationId` and single-item `nfcUids` inputs remain compatible.

## Tag lifecycle versus assignment

POST `/nfc-tags` accepts `{ "name": "Washer tag", "uid": "04:A1:B2" }` (aliases `tagName` and physical `tagId` are supported). A new tag has `status=ACTIVE` and `assigned=false`. Assignment changes only `assigned` and asset metadata, not lifecycle status. UIDs normalize to uppercase. Name/UID uniqueness and assignment history remain enforced.

GET `/nfc-tags?status=ACTIVE&assigned=false` supplies dropdown choices. `tagId` on process/device/storage configuration selects the registry UUID as an alternative to physical `nfcUid`; use the returned tag record's `id` for this UUID. The physical tag ID in tag-management input is a string UID, not the registry UUID.

DELETE `/nfc-tags/{id}` sets `DELETED`; an assigned tag must first be unassigned through `/nfc-assignments/{id}`. PATCH `/nfc-tags/{id}/status` accepts `ACTIVE` or `DELETED`. GET `/nfc-tags/statuses` lists the two supported statuses. A lookup table allows later lifecycle states without conflating them with assignment. Deleted tags cannot be attached. Legacy `enabled`/assignment `active` columns remain internal compatibility details and are not the public lifecycle contract.

## Migration and handoff

Flyway V5 copies station on-shift flags onto existing process assignments before dropping `user_station_assignments`. It preserves explicitly assigned processes and does not grant every process at a station. Old station-only access disappears unless the user has an actual process assignment. Existing enabled tags become ACTIVE; disabled tags become DELETED. No production reset is needed.

The script-free Postman collection and both architecture/design files use this contract. Set tokens and IDs manually in the environment and run requests in normal Postman tabs. The HTML mock remains the frontend reference; backend changes do not turn it into a deployed frontend.
