# NW Track frontend handoff

Import `NW_Track.postman_collection.json` and `NW_Track_LAN.postman_environment.json` into Postman. Select the LAN environment. Set `baseUrl` to `http://<server-Wi-Fi-IP>` (no trailing slash), `username`, and `password`. Credentials and token values are deliberately blank in the exported environment. Use the same Wi-Fi; no certificates are needed in HTTP mode.

## Open requests as normal Postman tabs

1. In Postman, choose **Import > Files** and select `NW_Track.postman_collection.json`. Import it as a collection; do not paste the JSON into a request body or the Scripts editor.
2. Import `NW_Track_LAN.postman_environment.json` separately and select that environment. Set `baseUrl`, `username`, and `password`.
3. Open **Collections > NW Track - Manual API requests** in the sidebar. Expand any use-case folder and double-click a request to open its own normal request tab.
4. Use the tab's **Params**, **Authorization**, **Headers**, and **Body** controls, then click **Send**. Request bodies are already populated as raw JSON. Query parameters are separate editable rows.
5. The collection contains **no pre-request scripts, post-response scripts, or JavaScript tests**. Run requests individually using Send; a collection runner is not required.

## Login and variables (manual)

1. Open the authentication folder's login request. Choose `clientType=PC` or `PAD` in the environment and click Send.
2. Copy `accessToken` from the response into the environment's `accessToken` value. Protected requests inherit the collection's Bearer Token authorization using that variable.
3. Run `GET /api/v1/auth/me`. Honor `must_change_password` and change the password when needed; update the environment password afterwards.
4. Run refresh-tokens and copy the returned `refreshToken` into its environment variable. When refreshing access, copy the new `accessToken` manually. Logout revokes the refresh session; clear both token variables afterwards.
5. Copy resource IDs from responses into the corresponding environment variables, such as `stationId`, `processId`, `styleId`, `lotId`, `visitId`, and `executionId`.

These folders contain alternative flows and administration actions, not one collection-wide sequence. Choose the requests relevant to your use case.

## Configuration and production sequence

1. **NFC tag management:** register the process, device, quality and storage tags using the named examples. Registration requires friendly `name` and physical `uid` (normalized to uppercase); no asset is needed yet. New tags return ACTIVE status, independently of assignment. GET `/nfc-tags/statuses` lists lifecycle states; DELETE sets DELETED after unassignment, and PATCH status accepts ACTIVE or DELETED. Dropdowns should use `status=ACTIVE`, `assigned=false` tags, plus the record's currently selected tag while editing.
2. **Process configuration:** create a process using `stationNumber` such as `01`. This creates the station if necessary; copy `station_id` and `id` from the response into `stationId` and `processId`. Existing references are reused without requiring a separate station-creation call.
3. Choose the alternate device-mode example when appropriate. Copy its process `id` into `deviceProcessId` and its device `id` into `deviceId`. Each nested device requires a name and registered NFC UID (or registry tag UUID); description is optional, and type defaults to GENERAL. Set deviceCount to the array length. Its owner is the process being created. Save operations replace the active device list; include the existing device ID to retain it.
4. Create a final quality process using the named example. Copy the returned process `id` into `qualityProcessId`. This explicit `quality=true` flag replaces reliance on a station being named QC. The station reference itself remains a normal reusable reference.
5. **Storage configuration:** create a storage area using `stationNumber`. Use `storageNumber`, `description`, `stationNumber`, and one registered NFC UID (or registry tag UUID). An extra name is not required; it defaults to the storage number. Storage units remain separate optional configuration for lot placement; create units under an area and use their IDs for storage operations. Set `storageUnitId` from the unit response.
6. **Station configuration:** enrich automatically created stations by ID if needed. Keep `code` equal to the original reference; name/type and other station metadata can change. Station code is the reference used in routes and reports.
7. **User use cases:** create users with one built-in or custom role and any number of process assignments. Send `processAssignments: [{processId, activeOnShift}]`; stations are derived from the processes and must not be submitted separately. `activeOnShift=true` enables that process, not every process at its station. Update the complete process list using PUT assignments. PUT the user item updates profile/role/assignments; DELETE disables the user.
8. **Style creation and lots:** create a style, set its ordered process ID array, preview the derived route, release it, then create lots. The example uses `processId` followed by `qualityProcessId`. To include the device process, insert `deviceProcessId` in the desired order. A station cannot appear twice in non-contiguous route groups.
9. **Trolley configuration:** create a trolley, register its tag, then assign the tag through NFC assignments with `assetType=TROLLEY`, `assetId=trolleyId`. Link it to a lot and dispatch.
10. **Station receipt and placement:** copy the dispatch response's `visitId` into the environment. You can also copy `visit_id` from incoming work. GET the visit's processes and copy the selected pending execution's `id` into `executionId`. Select the correct pending execution from that response as work progresses. Receive into process or storage using the two examples.
11. **Production operations:** PROCESS mode starts with `processNfcUid` and no devices. DEVICES mode starts with exactly one UID in `machineNfcUids` and no process UID. Complete ordinary work with `{}`; rescanning is not required. Pause/resume/block/skip and correction are separate requests; set reasons as appropriate.
12. **Quality completion:** for a final quality execution, POST process-executions complete with `passedQuantity`, `rejectedQuantity`, `remarks`. Their sum must equal lot quantity; remarks are required for rejects. The final quality process must be in progress and all earlier work resolved. Completion records the inspection and closes the current lot atomically. Rework is created separately, outside this lot. The older lot-level QC endpoint is also included and completes the final process using `goodQuantity`/`reason`; its outcome must agree with its counts.

## Folder reference

| Folder | Contents |
|---|---|
| Authentication and token generation | Login, identity/permissions, password change, access and refresh tokens, sessions, logout |
| Access roles | Built-in/custom role reads and module/channel updates; folder 19 adds creation/deletion |
| User use cases | Create/list/get users, role, multiple process assignments, status and password reset |
| NFC tag management | Register, rename, list, deactivate, assign, unassign and assignment history |
| Station configuration | Optional station creation, details and status |
| Process configuration | Process/device modes, descriptions, quality flag, update and deactivation |
| Device configuration | Machine details, owner, tags, availability and deactivation |
| Storage configuration | Areas, units, descriptions, NFC and occupancies |
| Trolley configuration | Trolleys and availability |
| Style creation and lots | Ordered processes, route, release, lots, revisions and cancellation |
| Station receipt and placement | Receive into process/storage |
| Production operations | NFC resolution, dispatch, retrieval, transitions and overrides |
| Quality completion | Lot-level QC and final-process completion with counts and remarks |
| Dashboard and reporting | Scoped summaries, WIP, style progress, lot history and displays |
| Transport use cases | Locations, mission requests, cancellation/retry/recovery and provider events |
| Offline synchronization | Scoped bootstrap/changes and replayable process events |
| Notifications | Current user's notifications |
| Factory configuration | Factory details and public service health |
| v21 Roles modules and form lookups | Custom role/module creation, role deletion, role options, scoped dependencies and custom placeholders |

## Roles and integration rules

V6 supports seven built-in roles plus custom roles. Built-in codes: NW, ADMIN_IT, TOP_MANAGER, PRODUCTION_MANAGER, SUPERVISOR, PROCESS_MANAGER, STYLE_CONFIGURATOR. The old STATION_0 role is renamed in place. Login/refresh/auth-me return modules for independent PC screen grants. Do not derive the sidebar from old coarse permissions. Custom roles retain assigned-process/station scope; Style Configurator creates and views only its own styles. See [the full role/module contract](../ROLE_MODULES_V21.md).

Folder **19 v21 Roles modules and form lookups** adds role creation/deletion, role options, the module catalog/creation/custom content and UI lookup calls. Copy generated role code to roleCode and module key to moduleKey manually. Use PUT access-roles with a complete modules array; omitted PAD/action permissions and channel flags are preserved. Built-in deletion and assigned custom-role deletion are blocked. NW/IT Admin retain PC user/role administration. New module registration creates a Work in Progress placeholder, not business functionality. GET ui/lookups supplies dropdown dependencies without granting their management screens. The collection contains 146 requests across 20 folders.

New style POST can include processIds atomically with styleNumber/description. Style Configurator must submit that full create request; it can release its own style using the release POST, but cannot perform a later route PUT or revision. Lot Management permits adding, editing quantity and mapping lots only for its own styles. Missing client/weight/delivery business fields remain documented in [the v21 UI mapping](../UI_V21_API_INTEGRATION.md). The older production sequence below/above is for style-management and operational users, not the create-only persona. PAD operational permissions and channel restrictions remain separate from desktop modules.

IDs are UUIDs from responses. Command fields use camelCase; many database-backed response fields use snake_case (`station_id`, `nfc_mode`, `must_change_password`). Use the running `/v3/api-docs` for precise response contracts. Requests do not capture IDs automatically. Copy the correct response IDs into the environment when creating resources or moving to another process. Set `sourceLocationId` and `destinationLocationId` from transport-location responses.

`401`: reauthenticate or refresh once. `403`: permission/channel/assignment denied. `400`: invalid inputs. `409`: workflow/state conflict. Deletion preserves history by disabling or unassigning; assigned tags must be unassigned before disabling. Do not retry state-changing requests blindly. Offline process starts include scan fields; final QC stays online so quantities and completion commit together. Biometrics and automatic pad logout are deferred.

WebSocket is STOMP at `ws://<IP>/ws`, with `/topic/stations/<stationId>` and `/user/queue/events` subscriptions. Native clients can supply the bearer handshake header. Browser integrations should use scoped REST polling because the standard browser WebSocket API cannot set that header.

## Current mock-only payloads

See [process assignment and configuration examples](../PROCESS_ASSIGNMENT_V5.md). Process code, device type and storage name are no longer required. Existing generated codes/types and quality flags are retained on update when omitted. Tag assignment uses a separate assigned flag; do not interpret ACTIVE as already attached to an asset. Per-process activeOnShift controls operation access and notification recipients.

## Updated API contract

List requests now return `items`, `page`, `size` and `totalElements`; use query
parameters `search`, `page` and `size`. POST/PUT users accept `status` (`ACTIVE` or `INACTIVE`). New accounts
must change their initial password within seven days. See the
[API change guide](../API_COLLECTIONS_AND_ACCOUNT_EXPIRY.md) for details and examples.

For planned lots, generated numbers, batch creation and trolley mapping, see [Lot Management](../LOT_MANAGEMENT.md). Folder 20 contains trolley assignment/unassignment. Create style with lotCount and optional quantity; release creates the planned lots. Additional lot creation returns createdCount and items; copy a lot UUID from items into lotId.

Folder 20 includes PUT /api/v1/lots/{lotId} with quantity and reason for lot editing. Transit, completed and cancelled lots cannot be edited. Style Configurator may also POST /styles/{id}/release for its own styles.
