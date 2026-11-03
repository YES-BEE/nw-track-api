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

1. **NFC tag management:** register the process, device, quality and storage tags using the named examples. Registration requires friendly `name` and physical `uid` (normalized to uppercase); no asset is needed yet. Dropdowns should use `enabled=true`, `active=false` tags, plus the record's currently selected tag while editing.
2. **Process configuration:** create a process using a station reference such as `01`. This creates the station if necessary; copy `station_id` and `id` from the response into `stationId` and `processId`. Existing references are reused without requiring a separate station-creation call.
3. Choose the alternate device-mode example when appropriate. Copy its process `id` into `deviceProcessId` and its device `id` into `deviceId`. Each nested device requires name, type, description if desired, and a registered NFC UID. Its owner is the process being created. Save operations replace the active device list; include the existing device ID to retain it.
4. Create a final quality process using the named example. Copy the returned process `id` into `qualityProcessId`. This explicit `quality=true` flag replaces reliance on a station being named QC. The station reference itself remains a normal reusable reference.
5. **Storage configuration:** create a storage area using a station reference. Storage number maps to `code`; include `name`, `description`, and one registered NFC UID. Storage units remain separate optional configuration for lot placement; create units under an area and use their IDs for storage operations. Set `storageUnitId` from the unit response.
6. **Station configuration:** enrich automatically created stations by ID if needed. Keep `code` equal to the original reference; name/type and other station metadata can change. Station code is the reference used in routes and reports.
7. **User use cases:** create users with one fixed role and any number of station/process assignments. Every selected process must belong to a selected station. `activeOnShift=true` enables station operations. Update all assignments together using PUT assignments.
8. **Style creation and lots:** create a style, set its ordered process ID array, preview the derived route, release it, then create lots. The example uses `processId` followed by `qualityProcessId`. To include the device process, insert `deviceProcessId` in the desired order. A station cannot appear twice in non-contiguous route groups.
9. **Trolley configuration:** create a trolley, register its tag, then assign the tag through NFC assignments with `assetType=TROLLEY`, `assetId=trolleyId`. Link it to a lot and dispatch.
10. **Station receipt and placement:** copy the dispatch response's `visitId` into the environment. You can also copy `visit_id` from incoming work. GET the visit's processes and copy the selected pending execution's `id` into `executionId`. Select the correct pending execution from that response as work progresses. Receive into process or storage using the two examples.
11. **Production operations:** PROCESS mode starts with `processNfcUid` and no devices. DEVICES mode starts with exactly one UID in `machineNfcUids` and no process UID. Complete ordinary work with `{}`; rescanning is not required. Pause/resume/block/skip and correction are separate requests; set reasons as appropriate.
12. **Quality completion:** for a final quality execution, POST process-executions complete with `passedQuantity`, `rejectedQuantity`, `remarks`. Their sum must equal lot quantity; remarks are required for rejects. The final quality process must be in progress and all earlier work resolved. Completion records the inspection and closes the current lot atomically. Rework is created separately, outside this lot. The older lot-level QC endpoint is also included and completes the final process using `goodQuantity`/`reason`; its outcome must agree with its counts.

## Folder reference

| Folder | Contents |
|---|---|
| Authentication and token generation | Login, identity/permissions, password change, access and refresh tokens, sessions, logout |
| Access roles | Seven fixed roles, permission catalogue, permission/channel updates |
| User use cases | Create/list/get users, role, multiple assignments, status and password reset |
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

## Roles and integration rules

The role codes are `NW`, `TOP_MANAGER`, `PRODUCTION_MANAGER`, `PROCESS_MANAGER`, `SUPERVISOR`, `ADMIN_IT`, `STATION_0`. Only these roles exist; no create-role endpoint is provided. NW and Admin (IT) start with full access. Top Manager starts with PC reporting only; Supervisor starts with Pad access only. Production Manager has factory-wide operations/reporting and style configuration. Process Manager and Supervisor operate assigned stations/processes. Station 0 has style configuration and assigned-station operations.

Administrators can edit action permissions and PC/PAD access. NW and Admin (IT) retain PC user/role administration to avoid lockout. Scope is independent of permissions: enabling an action does not remove assignment restrictions. Non-administrator PC sessions provide reporting/style configuration; production operations and QC require a PAD session. PC/PAD is the application's declared channel signed into its JWT; it is not proof of physical hardware. Do not infer it from a browser user agent.

IDs are UUIDs from responses. Command fields use camelCase; many database-backed response fields use snake_case (`station_id`, `nfc_mode`, `must_change_password`). Use the running `/v3/api-docs` for precise response contracts. Requests do not capture IDs automatically. Copy the correct response IDs into the environment when creating resources or moving to another process. Set `sourceLocationId` and `destinationLocationId` from transport-location responses.

`401`: reauthenticate or refresh once. `403`: permission/channel/assignment denied. `400`: invalid inputs. `409`: workflow/state conflict. Deletion preserves history by disabling or unassigning; assigned tags must be unassigned before disabling. Do not retry state-changing requests blindly. Offline process starts include scan fields; final QC stays online so quantities and completion commit together. Biometrics and automatic pad logout are deferred.

WebSocket is STOMP at `ws://<IP>/ws`, with `/topic/stations/<stationId>` and `/user/queue/events` subscriptions. Native clients can supply the bearer handshake header. Browser integrations should use scoped REST polling because the standard browser WebSocket API cannot set that header.
