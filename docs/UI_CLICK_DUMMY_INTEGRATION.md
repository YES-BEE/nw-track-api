# Current configuration UI: frontend integration map

Source: [NW_Track_Configurations_MUI_v8.html](../NW_Track_Configurations_MUI_v8.html). Contract checked against the repository controllers and services on 4 November 2026. This guide covers only the three existing screens: Process Configurations, Storage Configurations and Tag Management. Future screens can add sections using the same field/action tables.

The backend supports CRUD for these screens. **The HTML itself is still a localStorage click dummy; its buttons do not currently call the backend.** Replace its demo persistence with the API calls below. This document describes integration work; it does not wire the HTML or change the backend.

## 1. What needs adjustment in the current HTML

| Item | Current dummy | Integration requirement |
| --- | --- | --- |
| Storage station | No Station Number input | Add a required Station Number input; proposed ID `storageStation`. Send `stationNumber`. |
| Process device identity | `readDevices()` and submit rebuild devices without IDs | Retain each existing device's server UUID through editing and submit it in `devices[].id`. |
| Role selector | `#role` switches demo Admin/Supervisor/Operator | Use authenticated permissions. The seven actual roles are listed below. Selecting a demo role cannot grant access. |
| Data | Samples, local IDs and localStorage | Load server records; use server UUIDs. Never upload sample records automatically. |
| Reset Demo Data | Replaces local samples | Remove/hide, or relabel Reload List and issue GET only. There is no production reset API for this button. |
| Tag availability | Inferred from local process/storage arrays | Use tag `status` and `assigned` from the server, including current selections during edits. |
| Physical tag ID edits | Blocked only while currently assigned | Backend prohibits changing a UID after any assignment history, even after unassignment. |
| Deletion | Removes local records | Call DELETE, then refresh; backend retains historical records. |

No separate station creation screen or station API call is needed for process/storage saves. The entered reference creates or reuses the station and the server generates its UUID.

## 2. Connection, login and screen access

Set `baseUrl` to the running server's HTTP address, for example `http://192.168.1.10` **only if that is still its actual Wi-Fi IP**. Every path below is relative to `baseUrl`. Check `/actuator/health` and `/swagger-ui.html` on that same address. The deployed service must include the current code and Flyway migrations; this guide does not verify a running server's version.

Login is a prerequisite; the dummy does not contain a login screen:

```http
POST /api/v1/auth/login
Content-Type: application/json
```

```json
{
  "username": "<configured username>",
  "password": "<actual password>",
  "clientType": "PC"
}
```

Save `accessToken` from the response. Login also returns `tokenType`, `expiresIn`, `username`, `roles` and `processAssignments`. Do not assume the password is `admin`.

For protected requests:

```http
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json
```

`Content-Type` is needed when sending a JSON body. Call `GET /api/v1/auth/me` for the current user, role, `permissions`, `must_change_password` and `processAssignments`. Follow forced password change using `POST /api/v1/auth/change-password` with `currentPassword` and `newPassword` before continuing normal use when required.

Show the configuration workspace when `permissions` includes `CONFIGURE`; otherwise show `#restricted`. Process/storage reads require READ plus applicable scope; their mutations and **all tag registry reads/mutations require CONFIGURE**. NW and ADMIN_IT have configuration permission by default. Actual fixed role codes: `NW`, `TOP_MANAGER`, `PRODUCTION_MANAGER`, `PROCESS_MANAGER`, `SUPERVISOR`, `ADMIN_IT`, `STATION_0`. The backend always makes the final access decision.

User assignments are to processes; `processAssignments` includes derived `stationId` and `stationNumber`. These screens do not create user assignments.

**Browser hosting:** the current security configuration does not configure REST CORS. Opening the HTML as `file://`, or hosting it on another origin/port, is not a ready-to-use API setup. Serve/proxy the UI and `/api` through one origin, or add explicit REST CORS configuration as a separate deployment/backend task. The WebSocket allowed-origins setting does not configure REST CORS. This backend does not currently bundle/serve this HTML as a frontend application.

## 3. Endpoint inventory for the existing screens

Collection GET responses are JSON arrays, not `{items: [...]}` wrappers. The CRUD POST/PUT/DELETE handlers below return JSON records with HTTP 200 on success; do not require 201/204 to recognize success.

| Screen/use | Method and path | Use |
| --- | --- | --- |
| Process | `GET /api/v1/processes` | List, including nested active devices |
| Process | `GET /api/v1/processes/{id}` | Load latest edit form |
| Process | `POST /api/v1/processes` | Create process and its devices together |
| Process | `PUT /api/v1/processes/{id}` | Save complete process form and device list |
| Process | `DELETE /api/v1/processes/{id}` | Deactivate process/devices and free their tags |
| Storage | `GET /api/v1/storage-areas` | List storage areas |
| Storage | `GET /api/v1/storage-areas/{id}` | Load latest edit form |
| Storage | `POST /api/v1/storage-areas` | Create area and assign tag |
| Storage | `PUT /api/v1/storage-areas/{id}` | Update area and replace tag |
| Storage | `DELETE /api/v1/storage-areas/{id}` | Deactivate area and free tags, if allowed |
| Tags | `GET /api/v1/nfc-tags?status=ACTIVE` | Management table; includes assigned tags |
| Dropdowns | `GET /api/v1/nfc-tags?status=ACTIVE&assigned=false` | Available tags; merge own selections on edit |
| Tags | `GET /api/v1/nfc-tags/{id}` | Load latest tag for edit |
| Tags | `POST /api/v1/nfc-tags` | Register name and physical UID |
| Tags | `PUT /api/v1/nfc-tags/{id}` | Edit name/eligible UID |
| Tags | `DELETE /api/v1/nfc-tags/{id}` | Mark unassigned tag DELETED |
| Optional metadata | `GET /api/v1/nfc-tags/statuses` | Lifecycle labels and assignability |

Use **storage-areas**, not storage-locations/storage-units, for the current Storage screen. Devices in the process form are saved inside the process request; standalone `/api/v1/machines` CRUD is available but unnecessary for this form.

## 4. Sidebar, loading and shared actions

| HTML element/action | Integration behavior |
| --- | --- |
| `#muiSidebar`, Process Configurations | `nwShowConfig('process')`: show `#workspace`; load processes and tag choices |
| Storage Configurations | `nwShowConfig('storage')`: show `#storageWorkspace`; load storage areas and tag choices |
| Tag Management | `nwShowConfig('tags')`: show `#tagWorkspace`; load ACTIVE tags and assignment labels |
| `#role` | Replace demo role switching with actual user display/access state; no role-changing API call |
| Close/Cancel | Discard draft and close form; no POST/PUT/DELETE |
| Toast | Show success only after the server confirms the mutation |

For an authorized configuration user, independent initial loads can run in parallel:

```js
const [processes, storages, tags] = await Promise.all([
  api('/api/v1/processes'),
  api('/api/v1/storage-areas'),
  api('/api/v1/nfc-tags?status=ACTIVE')
]);
```

Here `api` means your authenticated HTTP helper, not a function already provided by the dummy. Keep server data in application state. Filter processes and storage areas by `active === true` for the dummy's current lists. Tag lifecycle uses `status`, not an `active` UI flag. Use all loaded ACTIVE tags to derive availability locally, or use the dedicated available-tag query.

Existing `nw-config-view-change`, `nw-role-change`, `nw-tags-updated`, `nwRefreshTags` and `nwPopulateTagSelects` are local UI mechanisms. They do not persist data. Refactor their consumers to use refreshed server state after successful requests.

## 5. Process Configurations: field mapping

| UI field/selector | Request field | Response source / rules |
| --- | --- | --- |
| Hidden editing identity | URL `{id}` | `id`, server UUID; never `p-<timestamp>` |
| `#pname`, Process Name | `name` | `name`; required; mock max 100, API max 150 |
| `#station`, Station Number | `stationNumber` | `stationNumber` (also `station_code`); required in this UI; mock max 30, API max 50 |
| `#desc`, Description | `description` | `description`; optional; API max 2000 |
| Radio `name="nfcMode"` | `nfcMode` | `nfcMode` (also `nfc_mode`); convert mock `process`/`devices` to `PROCESS`/`DEVICES` |
| `#processTag` select | `nfcUid` | `nfcUid`; physical UID, required only in PROCESS mode |
| `#deviceCount` | `deviceCount` | `deviceCount`; submit actual `devices.length`, not an unapplied count input |
| Device `[data-field="name"]` | `devices[i].name` | `devices[i].name`; required |
| Device `[data-field="description"]` | `devices[i].description` | `devices[i].description`; optional |
| Device `[data-field="tag"]` | `devices[i].nfcUid` | `devices[i].nfcUid`; required physical UID |
| Hidden device identity | `devices[i].id` on edit | `devices[i].id`; preserve for existing devices; omit for new devices |
| No current input | Omit `code` | Generated at create, preserved on update when omitted |
| No current input | Omit device `type` | Defaults GENERAL at create; existing type preserved when omitted |
| No current input | Omit `quality` | Defaults false at create; existing value preserved on update |
| No current input | `targetDurationSeconds`, if already present | Preserve a loaded value on PUT: omission clears it; no new editor is required here |

`station_id` is the internal UUID; it is not the displayed Station Number. Reuse is case-insensitive by reference. Treat it as text: do not coerce `01` into `1`. An inactive existing station cannot be reused as an active station by this save.

### Process button/click map

| Existing control | What frontend does | API |
| --- | --- | --- |
| `#newBtn`, + Create Process | Open blank `#formCard`, refresh available tags | GET available tags; no create yet |
| `#resetBtn`, Reset Demo Data | Remove, or replace with Reload List | GET processes only |
| `[data-edit="<id>"]` | Read latest record; fill form and retain device IDs | GET process by UUID; refresh tags |
| NFC method radios | Toggle `#processTagSection` / `#deviceSection` | Local only |
| `#applyCount`, Apply Count | Resize draft device rows, 0..50 | Local only |
| `#addDevice`, + Add Device | Append blank draft row, max 50 | Local only |
| Device `[data-remove]` | Remove that draft device; preserve other IDs | Local only until Save |
| `#processForm` submit, Save Process | Validate and save one complete payload | POST when new; PUT when editing |
| `#closeBtn`, `#cancelBtn` | Close and discard draft | None |
| `[data-delete="<id>"]` | Confirm, then delete; refresh after success | DELETE process by UUID |

PROCESS mode requires exactly one process tag and no devices. DEVICES mode requires 1..50 named/tagged devices and no top-level process tag. A UID cannot be selected twice. Mode switching affects draft state until Save; omit fields for the hidden/inapplicable mode.

### Create examples

Register the physical UIDs in Tag Management first. Values below are examples, not seeded tags.

```http
POST /api/v1/processes
```

```json
{
  "name": "Washing",
  "description": "Main washing process",
  "stationNumber": "01",
  "nfcMode": "PROCESS",
  "nfcUid": "NFC-PROCESS-001",
  "deviceCount": 0,
  "devices": []
}
```

```http
POST /api/v1/processes
```

```json
{
  "name": "Drying",
  "description": "Drying machines",
  "stationNumber": "02",
  "nfcMode": "DEVICES",
  "deviceCount": 2,
  "devices": [
    {"name": "Dryer 1", "description": "Left", "nfcUid": "NFC-DEVICE-001"},
    {"name": "Dryer 2", "description": "Right", "nfcUid": "NFC-DEVICE-002"}
  ]
}
```

**PUT replaces the active device list.** Include every device to retain, using its original ID. An omitted device is deactivated and its tag freed. New devices omit `id`. Do not separately POST every device after saving its process: the nested save is transactional.

```http
PUT /api/v1/processes/<process UUID from response>
```

```json
{
  "name": "Drying",
  "description": "Updated description",
  "stationNumber": "02",
  "nfcMode": "DEVICES",
  "deviceCount": 2,
  "devices": [
    {"id": "11111111-1111-4111-8111-111111111111", "name": "Dryer 1", "description": "Left", "nfcUid": "NFC-DEVICE-001"},
    {"id": "22222222-2222-4222-8222-222222222222", "name": "Dryer 2", "description": "Right", "nfcUid": "NFC-DEVICE-002"}
  ]
}
```

Replace illustrative UUIDs with actual response IDs. Unfinished production can block edits or deletion; keep the draft and show the returned error.

### Response-to-table and edit adapter

List/detail/save responses expose `id`, `name`, `description`, `active`, `stationNumber`, `nfcMode`, `nfcUid`, `deviceCount` and `devices`, alongside database-style fields such as `station_id` and `nfc_mode`.

```js
function processToDummy(p) {
  return {
    id: p.id,
    name: p.name,
    description: p.description ?? '',
    station: p.stationNumber,
    mode: p.nfcMode.toLowerCase(),
    processTag: p.nfcUid ?? '',
    targetDurationSeconds: p.target_duration_seconds,
    devices: p.devices.map(d => ({
      id: d.id, // MUST survive readDevices(), row resize and submit
      name: d.name,
      description: d.description ?? '',
      tag: d.nfcUid ?? ''
    }))
  };
}

function processPayload(form) {
  const deviceMode = form.mode === 'devices';
  const devices = deviceMode ? form.devices.map(d => ({
    ...(d.id ? {id: d.id} : {}),
    name: d.name.trim(),
    description: d.description,
    nfcUid: d.tag
  })) : [];
  return {
    name: form.name.trim(),
    description: form.description,
    stationNumber: form.station.trim(),
    nfcMode: deviceMode ? 'DEVICES' : 'PROCESS',
    targetDurationSeconds: form.targetDurationSeconds ?? null,
    deviceCount: devices.length,
    devices,
    ...(!deviceMode ? {nfcUid: form.processTag} : {})
  };
}
```

In `#records`, display name/description from their matching fields, Station No. from `stationNumber`, method from `nfcMode`, and either `nfcUid` or device count/names from `devices`. Device array order is not a persisted production sequence; the API returns devices ordered by name/ID.

## 6. Storage Configurations: fields and clicks

| UI field/selector | Request field | Response / rule |
| --- | --- | --- |
| Hidden identity | URL `{id}` | `id`, server UUID |
| `#storageNumber` | `storageNumber` | `storageNumber`; required; mock max 40, API max 80; saved uppercase |
| **New required input, proposed `#storageStation`** | `stationNumber` | `stationNumber`; station reused/created automatically |
| `#storageDesc` | `description` | `description`; optional |
| `#storageTag` | `nfcUid` | `nfcUid`; select one registered ACTIVE physical UID |
| No current input | Omit `name` | Backend defaults area name to the entered storage number |

The existing dummy model `{id, number, description, tag}` needs a `station` property for editing. Map `number <- storageNumber`, `station <- stationNumber`, `tag <- nfcUid`. Keep `station_id` separately only if needed internally.

| Existing control | Behavior | API |
| --- | --- | --- |
| `#newStorageBtn`, + Add New Storage | Open `#storageFormCard`; load tag choices | GET available tags; no create yet |
| `#resetStorageBtn`, Reset Demo Data | Remove, or replace with Reload List | GET storage areas only |
| `[data-edit-storage="<id>"]` | Load latest form, including station and current tag | GET storage area by UUID |
| `#storageForm` submit, Save Storage | Validate number/station/tag and save | POST or PUT storage area |
| `#closeStorageBtn`, `#cancelStorageBtn` | Discard draft | None |
| `[data-delete-storage="<id>"]` | Confirm then deactivate; refresh list/tags | DELETE storage area |

Use this body for POST or PUT:

```json
{
  "storageNumber": "ST-001",
  "description": "Finished goods storage",
  "stationNumber": "03",
  "nfcUid": "NFC-STORE-001"
}
```

In `#storageRecords`, Storage Number is `storageNumber`, Description is `description`, NFC Tag ID is `nfcUid`; action IDs use `id`. Adding a Station Number column is optional, but the form field is necessary. Reflect normalized values from the response after saving.

Changing a selection on PUT frees the old tag and assigns the new one atomically. Storage numbers must be unique among active areas. An area with storage units cannot change station. Occupied storage blocks deletion (`STORAGE_AREA_IN_USE`).

## 7. Tag Management: fields and clicks

| UI field/selector | Request field | Response / rule |
| --- | --- | --- |
| Hidden identity | URL `{id}` | `id`: registry UUID, not physical NFC UID |
| `#tagName`, Tag Name | `name` | `name`; required; mock max 80, API max 150 |
| `#tagActual`, Actual NFC Tag ID | `uid` | `uid`; required; mock max 120, API max 128; saved uppercase |
| Assigned To table cell | Not submitted | `assigned`, `asset_type` and associated asset UUID |
| No existing status input | Not needed on create | `status` is ACTIVE on creation, DELETED on deletion |

Use canonical `name`/`uid` keys for tag registration. Config forms use `nfcUid` for the physical UID. Although aliases exist, avoid the ambiguous name `tagId`: in a configuration request it means a **registry UUID**, whereas the tag registration compatibility alias means the physical UID. Alternatively, configuration forms can submit a registry UUID as `tagId` instead of `nfcUid`; use one convention consistently.

| Existing control | Behavior | API |
| --- | --- | --- |
| `#newTagBtn`, + Add New Tag | Open `#tagFormCard` | None |
| `[data-edit-tag="<id>"]` | Load latest tag and fill form | GET tag by UUID |
| `#tagForm` submit, Save Tag | Validate and save | POST or PUT tag |
| `#closeTagBtn`, `#cancelTagBtn` | Discard draft | None |
| `[data-delete-tag="<id>"]` | Disable while assigned; otherwise confirm/delete | DELETE tag by UUID |
| `#tagTotal` | Count current ACTIVE management records | Derived from loaded list |

POST `/api/v1/nfc-tags` or PUT `/api/v1/nfc-tags/{id}`:

```json
{"name": "Washing Line Tag 01", "uid": "04:A1:B2:C3:D4"}
```

Illustrative create response subset:

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "name": "Washing Line Tag 01",
  "uid": "04:A1:B2:C3:D4",
  "status": "ACTIVE",
  "assigned": false,
  "asset_type": null
}
```

Map the current dummy tag object as `{id: t.id, name: t.name, actual: t.uid}` and retain status/assignment metadata in state. Tag names are unique case-insensitively among enabled records. Physical UIDs remain globally unique, including deleted records; deletion does not make a physical UID available for a new registry record.

`ACTIVE` means lifecycle availability; `assigned: true` means currently attached to an asset. An assigned tag remains ACTIVE. Delete sets DELETED and preserves history; it is blocked while assigned. The current table has no status column: filter to ACTIVE to match its behavior, and optionally add a status badge later.

After any assignment history exists, changing `uid` returns 400 even if `assigned` is now false. Lock UID editing when history is known, or display the server error and retain the draft. `GET /api/v1/nfc-assignments/{tag UUID}/history` can establish whether history exists. Renaming remains available.

### Assigned To labels

Use the server's `assigned` flag first. If false, show Unassigned. Otherwise resolve its asset metadata using loaded records:

| `asset_type` | ID field | Display lookup |
| --- | --- | --- |
| PROCESS | `process_id` | Process name |
| MACHINE | `machine_id` | Nested device name and owning process |
| STORAGE_AREA | `storage_area_id` | Storage number |
| STORAGE_UNIT | `storage_location_id` | Unit ID/name if available |
| STATION | `station_id` | Station reference if available |
| TROLLEY | `trolley_id` | Trolley ID/code if available |

If an asset is outside these three screens or outside the loaded scope, display its type/UUID as fallback. A failed label lookup does not make an assigned tag available.

## 8. Shared NFC dropdown behavior

All selects display `name (uid)` and, to match this dummy, use physical `uid` as their value. Never send the friendly tag name as the UID.

1. For creation, offer only `status === 'ACTIVE' && assigned === false`.
2. For editing, union those choices with the edited asset's **own currently assigned ACTIVE tags**. Include all current device tags when editing a process. Otherwise the saved selection disappears because the available-tag endpoint excludes it.
3. Deduplicate by normalized UID. Exclude tags belonging to other assets and exclude values selected in another device row in the unsaved draft.
4. Refresh choices on form opening and after every successful configuration/tag save or deletion. Refresh affected lists as well.
5. On assignment conflict, keep the draft, reload tag availability and show the error. Another client may have claimed the tag after the dropdown loaded.

Process/storage save handles assignment; no separate assignment POST is necessary. The dummy has no Scan NFC button: these forms select already registered physical IDs and do not need operational NFC scan APIs.

There is also **no current Unassign button**. Replacing a tag through the process/storage form, or deleting that asset when allowed, frees its tags. If a dedicated Unassign action is added later, the existing API is:

```http
DELETE /api/v1/nfc-assignments/<registry tag UUID>
Content-Type: application/json
Authorization: Bearer <accessToken>
```

```json
{"reason": "Released for reassignment"}
```

This request requires a JSON body and returns no record body. Unassignment leaves lifecycle ACTIVE. Future lifecycle controls can use `PATCH /api/v1/nfc-tags/{id}/status` with `{"status":"ACTIVE"}` or `{"status":"DELETED"}`; there is no such control in the current dummy.

## 9. Persistence, errors and refresh behavior

Replace these demo stores with authenticated server loads/mutations:

| Local demo store | Server source |
| --- | --- |
| `nw-track-process-config-v3` | `/api/v1/processes` |
| `nw-track-storages-v1` | `/api/v1/storage-areas` |
| `nw-track-tags-v1` | `/api/v1/nfc-tags?status=ACTIVE` |

Do not treat localStorage as the source of truth or silently fall back to samples after a failed request. Build explicit request DTOs from the form; do not send an entire database response back as a write payload.

On Save: validate, disable duplicate submission, send POST/PUT, then close the form and refresh records/tags after success. On failure, retain values and keep the form open. On Delete: confirm, send DELETE, then refresh; do not permanently remove a row before server success. If a response is lost after a mutation, reload before retrying to avoid duplicate creates.

Display form errors in `#message` (process), `#storageMessage` (storage), or `#tagMessage` (tags). API validation responses have `fieldErrors`, for example `name` or `devices[0].name`; route these to their matching inputs where possible.

| Result | UI handling |
| --- | --- |
| 400 / VALIDATION_FAILED | Show `detail` and `fieldErrors`; correct missing/invalid fields |
| 400 / INVALID_ARGUMENT | Show `detail`: count mismatch, mode mismatch, UID edit history, process deletion with unfinished production, etc. |
| 401 | Expired/invalid authentication: refresh session if implemented, otherwise log in again; retain draft where practical |
| 403 / ACCESS_DENIED | Show access restriction; do not offer role-selector workarounds |
| 404 | Resource/tag lookup no longer available; reload relevant data |
| 409 | Show business `code`/`detail`; refresh affected state without discarding draft |
| Timeout/network failure | Keep draft, show connectivity error; do not report success |

Relevant conflict codes include `PROCESS_IN_USE`, `NFC_ALREADY_ASSIGNED`, `NFC_STILL_ASSIGNED`, `NFC_DISABLED`, `STORAGE_NUMBER_EXISTS`, `STORAGE_NFC_EXISTS`, `STORAGE_AREA_IN_USE` and `DATA_CONFLICT`. Not every route uses every code. Currently some missing-resource paths produce business code `NOT_FOUND` with **409**, while database lookup misses produce 404; handle the code as well as HTTP status. Missing/inactive tag lookup during configuration may produce 404 rather than NFC_DISABLED. Parse errors/security responses may not contain every Problem Details field, so provide a fallback message.

## 10. Suggested integration order and review checklist

1. Set API base URL, browser proxy/hosting, login and permission-based navigation.
2. Integrate Tag Management create/list/edit/delete first; verify ACTIVE/unassigned defaults.
3. Integrate PROCESS mode, then DEVICES mode, retaining device UUIDs during updates.
4. Add the Storage Station Number input and integrate storage-area CRUD.
5. Replace all local assignment inference with server metadata; refresh dropdowns after mutations.
6. Remove Reset Demo Data controls and sample fallback; test two browser sessions competing for a tag.

Before handoff verify: create and edit each mode; add/remove a device without changing remaining IDs; cancel without mutation; reuse a station reference; change a tag and see the old tag become available; reject duplicate tag assignment; block assigned-tag deletion; hide soft-deleted process/storage rows and DELETED tags; show permission and production-in-use errors.

User management/process assignment, styles/lots, operational NFC scanning, production progress, QC results, dashboards and storage-unit placement have backend APIs but **no screens in this HTML**, so they have no click mapping in this guide. See [the general integration guide](API_INTEGRATION_GUIDE.md), [current configuration contracts](PROCESS_ASSIGNMENT_V5.md), and [Postman setup and all use cases](postman/README.md) for those APIs. The [Postman collection](postman/NW_Track.postman_collection.json) contains normal request tabs without scripts; copy tokens and response UUIDs into its variables manually.
