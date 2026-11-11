# NW Track v21 UI → API integration handoff

Source: [NW_Track_Role_Based_Modules_v21.html](../NW_Track_Role_Based_Modules_v21.html). Updated **7 November 2026** for Flyway V6 and the implemented role/module APIs. The backend now matches v21's role creation, role assignment, independent screen grants, Style Configurator and custom module placeholders. The HTML remains a click dummy; replace its handlers with these API calls. Missing style business fields remain explicitly identified below.

## 1. Authorization contract and default roles

Use [ROLE_MODULES_V21.md](ROLE_MODULES_V21.md) for complete role/module request and response contracts, migration effects, validation and screen grants. This is part of the UI handoff, not a proposed design.

| UI persona | Backend role code | Default sidebar module keys |
| --- | --- | --- |
| NW | NW | dashboard, reports, process, storage, tags, styles, users, roles |
| IT Admin | ADMIN_IT | dashboard, reports, process, storage, tags, styles, users, roles |
| Top Manager | TOP_MANAGER | dashboard, reports |
| Production Manager | PRODUCTION_MANAGER | dashboard, reports, process, storage, tags, styles |
| Supervisor | SUPERVISOR | dashboard, reports |
| Process Manager | PROCESS_MANAGER | none; login and account APIs still work |
| Style Configurator | STYLE_CONFIGURATOR | newstyle; create and view own |
| Custom role | Generated CUSTOM_... code | Explicitly selected module keys; assigned-process/station scope |

V6 replaces STATION_0 with STYLE_CONFIGURATOR in place, preserving user associations and history but removing the old operational permissions. Built-in roles cannot be deleted. Assigned custom roles cannot be deleted until all users are reassigned.

**Desktop navigation follows the returned `modules`, not coarse `permissions` or hardcoded role names.** Each module can be granted independently. Permissions continue to represent existing PAD/action access; editing desktop module checkboxes preserves unexposed PAD permissions.

## 2. Connection, persistence and HTTP helper

Configure `baseUrl` as `http://<actual-server-Wi-Fi-IP>`; all paths here include `/api/v1`. Swagger is `/swagger-ui.html`, OpenAPI `/v3/api-docs`, health `/actuator/health`. Deploy current code/V6 before integration. No database reset is required.

REST CORS is configurable through `CORS_ALLOWED_ORIGINS` in the server `.env`, e.g. `CORS_ALLOWED_ORIGINS=http://localhost:5173`. Use exact frontend origins without a trailing slash; separate multiple origins with commas. Rebuild/recreate the API after changes. See [frontend CORS setup](FRONTEND_CORS.md). Alternatively use a same-origin proxy. This API does not bundle the mock as a running frontend; serve it over HTTP rather than file://.

Use an HTTP helper that adds `Authorization: Bearer <accessToken>`, JSON-encodes request bodies with Content-Type application/json, checks `response.ok`, handles empty success bodies and retains HTTP status plus error code/detail/fieldErrors. Collections return direct arrays. CRUD record handlers generally return 200; do not require 201 or assume every response has JSON.

Replace sample arrays, Date.now IDs and localStorage stores (`nw-track-process-config-v3`, `nw-track-storages-v1`, `nw-track-tags-v1`, `nw-track-demo-styles-v1`, `nw-track-demo-users-v16`, `nw-track-role-definitions-v17`, `nw-track-custom-modules-v18`) with server state. Never upload samples or silently restore them after a network failure. Keep real UUIDs/codes/keys from responses. Remove Reset Demo Data or convert to a GET-only Reload action.

## 3. Login, shell and lookup mapping

| Existing selector/action | API request or response mapping |
| --- | --- |
| `#loginName` | Login `username`, trimmed |
| `#loginPassword` | Login `password`, unchanged |
| `#loginForm` / Sign in | POST /api/v1/auth/login with username, password, clientType: PC |
| `#loginError` | Auth/connectivity error text; no embedded account fallback |
| `#currentUser` | /auth/me.username or chosen display_name |
| `#currentRole` | Display name from returned role code/options |
| `#role` | Remove interactive role simulation; server assigns actual role |
| `#roleLandingTitle` | Welcome text from authenticated user |
| `#loginOverlay`, body.logged-out | Toggle after authenticated initialization |
| `#logoutBtn` | POST /auth/logout if refresh session exists; clear tokens/caches and show login |
| `#muiSidebar` | Build items from returned modules key/name records |
| `#restricted` | No granted web modules or access denied |

```json
{"username":"<actual username>","password":"<actual password>","clientType":"PC"}
```

Login/refresh return accessToken, tokenType, expiresIn, username, roles, processAssignments and **modules**. GET `/api/v1/auth/me` returns id, username, display_name, enabled, status, must_change_password, role, clientType, permissions, processAssignments, modules. A module item is `{key,name,built_in}`. Call auth/me after role/module edits or page initialization; current database grants apply on every request. User role changes require login again.

Add the missing forced-password-change flow when must_change_password is true: POST `/api/v1/auth/change-password` with currentPassword/newPassword. Optional refresh: POST `/auth/refresh-tokens` with Bearer authorization and no body, save refreshToken/expiresAt securely; POST `/auth/refresh` with refreshToken to obtain another access token. Logout POST `/auth/logout` takes refreshToken and Bearer authorization; refresh first if necessary. Logout revokes refresh sessions, not existing JWTs. Clear local session even when network revocation fails, without claiming server revocation succeeded.

The embedded accounts/passwords, roleDefs, permissionRows, nwRolePermissions and nwCustomModules local storage are no longer authorities. New users sign in using server credentials; there is no separate demo authentication flow.

| Navigation key | Workspace | Required desktop grant |
| --- | --- | --- |
| dashboard | #extraWorkspace | dashboard |
| reports | #extraWorkspace | reports |
| process | #workspace | process |
| storage | #storageWorkspace | storage |
| tags | #tagWorkspace | tags |
| styles | #styleWorkspace | styles |
| newstyle | #styleWorkspace | newstyle; own-only create/view behavior |
| users | #extraWorkspace | users |
| roles | #extraWorkspace | roles |
| custom_... | #extraWorkspace | That exact custom key; GET /modules/{key}/content |

`nwShowConfig(view)` and local navigation events toggle views and load data; they do not write. Configuration sidebar expansion is local only. Load only the selected module's endpoints; do not globally prefetch unauthorized screens.

GET `/api/v1/ui/lookups` supplies form dependencies without granting the source management screen. Its processes entries are `{id,name,station_id,stationNumber}`; tags entries are `{id,name,uid,status,assigned}`. The response contains only applicable arrays. Use processes for styles/users and tags for process/storage selects. Custom roles receive assigned-process/station scope; Style Configurator gets all active process choices for route creation. GET `/api/v1/access-roles/options` supplies `{code,name}` to user forms without requiring role management. Do not call GET processes/nfc-tags/access-roles administration routes just for dropdowns when those modules are absent.

## 4. Configuration endpoint inventory

| Resource | List/detail | Create | Update | Delete |
| --- | --- | --- | --- | --- |
| Processes | GET /api/v1/processes, GET /processes/{id} | POST /api/v1/processes | PUT /api/v1/processes/{id} | DELETE /api/v1/processes/{id} |
| Storage areas | GET /api/v1/storage-areas, GET /storage-areas/{id} | POST /api/v1/storage-areas | PUT /api/v1/storage-areas/{id} | DELETE /api/v1/storage-areas/{id} |
| Tags | GET /api/v1/nfc-tags?status=ACTIVE, GET /nfc-tags/{id} | POST /api/v1/nfc-tags | PUT /api/v1/nfc-tags/{id} | DELETE /api/v1/nfc-tags/{id} |

Abbreviated detail paths above also use /api/v1. Each resource requires its respective process/storage/tags module on PC. Filter process/storage lists by active === true; tag lifecycle is ACTIVE/DELETED, separate from assigned. The forms below use physical nfcUid values, not friendly tag names.

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
| `#newBtn`, + Create Process | Open blank `#formCard`, refresh available tags | GET /api/v1/ui/lookups; no create yet |
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
| `#newStorageBtn`, + Add New Storage | Open `#storageFormCard`; load tag choices | GET /api/v1/ui/lookups; no create yet |
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

1. Load form dependencies from `GET /api/v1/ui/lookups` (`tags` array) when the user lacks Tag Management. With tags access, the direct tag list/filter endpoints also work. For creation, offer only `status === 'ACTIVE' && assigned === false`.
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


## 9. Style Management and New Style

Style Management uses the styles module. New Style uses newstyle: built-in Style Configurator and create-only custom roles can create and view their own styles, with no update/delete/release/revision access. The backend enforces ownership on collection/detail/route reads.
### Style form and table mapping

| Existing selector / local property | Current request field | Response/read mapping and limitation |
| --- | --- | --- |
| Editing identity `id` | URL `{id}` | Server style `id`, UUID |
| `#styleNumber` / `number` or `code` | `styleNumber` on POST | `style_number`; no rename endpoint; lock after creation |
| `#styleCreated` / `created` | **None** | `created_at` is server-generated; show formatted timestamp/date read-only; cannot choose a creation date |
| `#styleDesc` / `description` | `description` on POST or revision | `description`; no general metadata PUT |
| `#styleClient` / `client` | **None** | No client field in style API/schema contract |
| `#styleGarments` / `garments` | **None** | No total-garment field; do not equate with one lot's `quantity` |
| `#styleLots` / `lots` | **None** | No planned lot-count field; GET lots returns actual created lots only |
| `#styleWeight` / `weight` | **None** | No weight-per-lot field |
| `#styleAvg` / `avg` | **None** | No average garment weight field; do not calculate or store it as description |
| `#styleDelivery` / `delivery` | **None** | No delivery-date field |
| `#styleSteps` / `steps` | `processIds` array on POST; raw array on management PUT | Read derived route, flatten its `processes` in route/sequence order |
| Dynamic `#styleStep0`, `#styleStep1`, etc.; `[data-step-select]` | Array element: process UUID | Lookup options from active `GET /api/v1/processes`; label `name` |
| Linked Station read-only display | Not separately submitted | Lookup process `stationNumber`; actual route uses station UUID |
| Dummy `createdBy` | Not submitted | `created_by` user UUID; own-only filtering is enforced server-side |
| Additional useful read-only state | Not on basic create form | `status`, `released_at`, `version` |

`#styleRecords` columns: Style Number ← `style_number`; Created ← `created_at`; Route ← flattened process IDs joined to process `name`. Client, Garments and Delivery Date have no response source; show an explicit unavailable marker or remove them for the supported subset. Lots can be relabeled **Actual Lots** and populated from `GET /api/v1/styles/{id}/lots` length, but this is a different meaning from the planned Number of Lots input. Do not imply those fields are saved.

`#styleSearch` can filter loaded `style_number` and `description` locally. Client search is unavailable. Collection GET has no search/pagination query contract. Formatting `created_at` as a local calendar date is display-only; do not send it back as `styleCreated`.

### Save and sequence integration

POST `/api/v1/styles` now accepts the ordered route atomically with metadata:

```json
{
  "styleNumber":"STY-2026-001",
  "description":"Denim wash",
  "processIds":["11111111-1111-4111-8111-111111111111"]
}
```

Use actual process UUIDs from lookups. New Style requires a non-empty processIds array; no subsequent route PUT is needed or allowed for create-only users. Failure rolls back this entire create. All process IDs must be active and valid. Custom roles can select only assigned processes; Style Configurator can select all active processes.

Responses/list records expose id, style_number, description, status, released_at, created_at, created_by, version. created_by is the creator user UUID. Never send a local createdBy username as authority.

| Existing action | API or local behavior |
| --- | --- |
| Open styles/newstyle | GET /api/v1/styles plus GET /api/v1/ui/lookups |
| `#newStyleBtn` | Open blank form; no write |
| `#styleAddStep` | Add local row |
| `[data-step-select]` | Store process UUID, derive Linked Station from stationNumber |
| `#styleSteps [data-up]`, [data-down], [data-remove] | Change draft order only |
| `#styleSaveBtn` / `#styleForm`, create | POST styles with styleNumber, description, processIds |
| `[data-style-view]` | GET /styles/{id} and /styles/{id}/derived-route; read-only |
| `[data-style-edit]` | styles module only; load detail/route; use explicitly supported update action below |
| `[data-style-delete]` | No style DELETE API; hide/disable |
| `#closeStyleBtn`, `#cancelStyleBtn` | Discard draft; no write |
| `#styleSearch` | Local filter of style_number/description; client search unavailable |

GET `/api/v1/styles/{id}/derived-route` returns `[{sequence,stationId,stationCode,processes:[{processId,processCode,sequence,stationId,stationCode}]}]`. Flatten by route sequence then process sequence to fill steps; join processId with lookup name. Adjacent processes on the same station group together. A,A,B is allowed; A,B,A returns REPEATED_STATION_NOT_ALLOWED. Avoid duplicate process UUIDs.

For existing style-management users only, PUT `/api/v1/styles/{id}/processes` takes a **raw UUID array**, replaces DRAFT process selection and returns the route. This legacy path is not atomic with metadata creation and can commit selection before subsequent route derivation fails; prefer the atomic POST for all new styles. An incomplete legacy draft may return STYLE_PROCESS_REQUIRED when reading its route.

There is no general PUT /styles/{id}, rename or DELETE endpoint. Existing production revision APIs remain separate actions requiring explicit reason/scope UI:

- POST `/api/v1/styles/{id}/revisions/unstarted` or `/revisions/in-progress`: `{description,processIds,reason,quantity?}`; result revisionId/revisionNumber/affectedLots/scope. Optional quantity changes each affected lot, not the total style garment count.
- POST `/api/v1/styles/{id}/release`, no body: releases DRAFT. No current v21 Release button; do not release automatically on Save.
- POST `/api/v1/styles/{id}/lots`: `{lotNumber,quantity}`, creates one lot after release. Number of Lots input is not a direct mapping to this operation.

Do not silently map generic Edit to an in-progress revision. Style Configurator/create-only users are denied these actions even on their own styles. All future own-style UI filters are backed by server ownership checks; the local createdBy comparison is not security.
## 10. User Management

The current generated form has only username, role and status. It cannot supply all required create fields. Extend it before claiming complete user integration.

### User field mapping

| Selector / local field | Request mapping | Response / notes |
| --- | --- | --- |
| Hidden selected user ID | URL `{id}` | `id`, UUID |
| `#userName` / `username` | `username` | `username`; required |
| `#userRole` / `role` | `role` | One backend role code, not display label; see section 3 |
| `#userStatus` / `status` | `status: "ACTIVE"` or `"INACTIVE"` in POST/PUT | Response `status`; legacy `enabled` boolean also returned |
| **Proposed new `#userDisplayName`** | `displayName` | `display_name`; required on create/update |
| **Proposed new `#userInitialPassword`** | `initialPassword` on create only | Required on create; no password returned; never prefill/log |
| **Proposed new process assignment editor** | `processAssignments: [{processId, activeOnShift}]` | Detailed assignment array; preserve all selections on update |
| Read-only derived station | Do not send station assignment | Each assignment returns `stationId`, `stationNumber` |
| Optional account indicator | Not a create/update property | `must_change_password`; new/reset accounts true |

Process assignment command shape:

```json
{"processId":"11111111-1111-4111-8111-111111111111","activeOnShift":true}
```

Assignment response fields: `processId`, `processName`, `stationId`, `stationNumber`, `activeOnShift`, `processActive`, `stationActive`. The UI must distinguish an on-shift flag from process/station active flags. All assignments are replaced on PUT; an empty array removes them all. Never clear them merely because the current form did not render an assignment editor. Load active process choices from GET /api/v1/ui/lookups; keep station display read-only.

### User API/action mapping

| Existing action | API |
| --- | --- |
| Navigate users, render `#userTable` | `GET /api/v1/users` |
| `#userSearch` | GET `/api/v1/users?search=...&page=0&size=20`; render `items` and use `totalElements` |
| `#addUser` | Open form; no create yet |
| `[data-edit-user]` | `GET /api/v1/users/{id}`, populate and preserve assignments/display name |
| `#userForm` submit new | POST user including `status` |
| `#userForm` submit edit | PUT user including `status` |
| `[data-delete-user]` | Confirm, `DELETE /api/v1/users/{id}`; disables account, not physical deletion |
| `#cancelUser` | Close form, discard draft; no API |

All `/api/v1/users` routes require the `users` module on PC. Populate `#userRole` from `GET /api/v1/access-roles/options` (code/name pairs, including custom roles). This requires users or roles access, without granting role administration. Use `GET /api/v1/ui/lookups` for process choices; it respects assigned-process scope for custom roles.

Create:

```http
POST /api/v1/users
```

```json
{
  "username":"operator.wash",
  "displayName":"Washing Process Manager",
  "status":"ACTIVE",
  "initialPassword":"<temporary password entered by administrator>",
  "role":"PROCESS_MANAGER",
  "processAssignments":[
    {"processId":"11111111-1111-4111-8111-111111111111","activeOnShift":true}
  ]
}
```

Create saves status atomically with profile, role and assignments and returns `must_change_password: true`. Omitted status defaults to ACTIVE on POST and preserves the stored status on PUT. New accounts must change their initial password within seven days; see [the account expiry guide](API_COLLECTIONS_AND_ACCOUNT_EXPIRY.md).

Update:

```http
PUT /api/v1/users/<user UUID>
```

```json
{
  "username":"operator.wash",
  "displayName":"Washing Process Manager",
  "status":"ACTIVE",
  "role":"PROCESS_MANAGER",
  "processAssignments":[
    {"processId":"11111111-1111-4111-8111-111111111111","activeOnShift":false}
  ]
}
```

List/detail/save responses contain `id`, `username`, `display_name`, `status`, `enabled`, `must_change_password`, `created_at`, `role`, `processAssignments`. Map the current table's status to `status`; DELETE also returns a disabled record. The list still includes disabled accounts. Prefer keeping the row with Inactive status to accurately represent server state; if design hides them, filter explicitly.

Other supported user APIs have **no v21 controls**:

- `PUT /api/v1/users/{id}/assignments`: `{"processAssignments":[...]}`.
- `PUT /api/v1/users/{id}/roles`: `{"role":"SUPERVISOR"}`.
- `PUT /api/v1/users/{id}/password`: `{"temporaryPassword":"..."}`; forces password change and revokes refresh sessions.

Full user updates revoke refresh sessions. A role change invalidates old role-bearing access on subsequent protected calls; the user must log in again. Avoid accidentally locking out the current administrator through self-edit/deactivation; show the effect before confirmation.

## 11. Role Management and custom module click mapping

Use the exact [V6 role/module contract](ROLE_MODULES_V21.md). The previous unsupported-role limitations are resolved.

| Existing element/action | API/field mapping |
| --- | --- |
| Role screen / #roleTable | GET /api/v1/access-roles plus GET /api/v1/modules |
| #roleName | POST name; read-only on edit; identity is returned code |
| #roleModuleChoices / name=roleModule | modules array of checked module keys |
| #addRole / Save Role | POST /api/v1/access-roles with name/modules; defaults PC true/PAD false |
| [data-edit-role] / Save Role | PUT /api/v1/access-roles/{code} with complete modules array |
| [data-delete-role] | DELETE /api/v1/access-roles/{code}; hide built-in delete; assigned custom deletion blocked |
| #addNewModuleCheck | Reveal #newModuleName locally |
| #newModuleName | POST /api/v1/modules with name, then include returned key in role modules |
| #cancelRole | Discard draft, no write |
| Custom sidebar entry | GET /api/v1/modules/{key}/content; show Work in Progress |

```json
{"name":"Line Manager","modules":["dashboard","process"],"pcAccess":true,"padAccess":false}
```

Create returns one role record with generated code; PUT returns the entire active role list. GET records contain code/name/description/built_in/active/pc_access/pad_access/permissions/modules. Omit channel/action permission fields on module-only PUT to preserve them. New modules are registered first, then the role saved; if the second request fails retain the key and retry role save, not module registration.

Style Configurator's newstyle grant is preserved even though the dummy excludes newstyle from role checkbox choices. It cannot be given styles; use another role for general style editing. Built-in roles are protected from deletion. NW/IT Admin retain PC user/role administration to prevent lockout.

## 12. Dashboard, Reports and placeholders

Current dashboard()/reports() render Work in Progress; there are no KPI/filter controls to map yet. Available future APIs: GET /api/v1/dashboard and /dashboard/lots?status=... (dashboard module), GET /api/v1/reports/lot-history?lotId=... and /styles/{id}/progress (reports module). These grants are independent. Custom content returns key/name/status WORK_IN_PROGRESS/message; it has no business data APIs yet.

## 13. Errors and state refresh

Use #loginError, #message, #storageMessage, #tagMessage, #styleError for errors in their respective forms. Add inline error regions to user/role forms. Problem Details uses status/code/detail and optional fieldErrors (e.g. devices[0].name); security/network failures may lack JSON.

400: invalid fields/module/count/mode/UID history or lockout edit. 401: refresh/relogin. 403: module/channel/scope/ownership denied; never show a fake saved state. 404: missing lookup. 409: business conflict/uniqueness; keep draft and refresh affected data. BUILT_IN_ROLE and ROLE_IN_USE are role-deletion conflicts. Some NOT_FOUND business errors currently use 409 rather than 404.

Disable repeated submissions. Close/Cancel and row reordering do not mutate. After successful process/storage/tag mutations refresh resource lists and form lookups; after style saves reload styles/route; after user saves reload users; after role edits reload role list and auth/me and rebuild sidebar. Module changes apply to existing JWTs. User profile and status are saved in one request. Handle module-create+role-save explicitly, retaining the successful first result for retry.

## 14. Integration acceptance and remaining business-field work

Verify independent grants for every module; direct forbidden API calls must fail as well as hidden navigation. Verify custom-role user assignment, immediate module revocation, protected built-in deletion, assigned-role deletion conflict, custom Work in Progress modules, process/station scope and Style Configurator own-only reads/mutation denial. A Process Manager with no web modules must still log in and log out normally.

The roles/permissions APIs are implemented. Remaining v21 business-field gaps are style client, aggregate garment/planned-lot counts, weights, delivery date/manual creation date and general style update/delete behavior. They were not part of this role authorization implementation. Do not mark unsupported fields saved or store them silently in localStorage.

Reference: [general guide](API_INTEGRATION_GUIDE.md), [role/module contracts](ROLE_MODULES_V21.md), [process assignment contracts](PROCESS_ASSIGNMENT_V5.md), [Postman instructions](postman/README.md), [143-request collection](postman/NW_Track.postman_collection.json). New APIs are in folder 19; no scripts are used. Set roleCode/moduleKey manually from responses.