# NW Track client integration guide

For the latest HTML, start with the [v21 screen, field and action integration handoff](UI_V21_API_INTEGRATION.md). It covers login, configurations, styles, users and roles, and identifies unsupported fields/actions. The [earlier configuration-only mapping](UI_CLICK_DUMMY_INTEGRATION.md) remains available for the v8 dummy.

The complete frontend handoff is in [Postman setup and use cases](postman/README.md):

- [Collection: all REST APIs grouped by use case](postman/NW_Track.postman_collection.json)
- [LAN environment: HTTP by IP](postman/NW_Track_LAN.postman_environment.json)
- [Field coverage, access model and architecture](CONFIGURATION_V8_DESIGN.md)
- [V6 roles, independent module grants and migration](ROLE_MODULES_V21.md)
- [Frontend CORS configuration and preflight diagnosis](FRONTEND_CORS.md)

Windows defaults to `http://<server-Wi-Fi-IP>`. Swagger is `/swagger-ui.html`, OpenAPI is `/v3/api-docs`, and public health is `/actuator/health`. Set the same base URL in every frontend. The server and clients must share the local network. Optional HTTPS is enabled with `deployment\windows\install.cmd start -EnableHttps`; that mode requires local domain DNS and client CA trust.

Login is `POST /api/v1/auth/login` with `username`, `password`, and required `clientType` (`PC` or `PAD`). Send `Authorization: Bearer <accessToken>` on protected requests. Get `/api/v1/auth/me` for the current role, permissions and `processAssignments`, including derived station information. Create a refresh session through `/auth/refresh-tokens`; `/auth/refresh` retains its PC/PAD channel. Honor forced password change and revoke refresh sessions on logout. Store tokens securely in the client.

Users are assigned only to processes using `processAssignments: [{processId, activeOnShift}]`; no station assignment is submitted. Login/refresh, user reads, `/auth/me` and sync bootstrap expose each process with its derived station. Moving a process changes derived scope without editing the user. V6 supports seven built-in roles plus custom roles. STATION_0 becomes STYLE_CONFIGURATOR with create/view-own access. Desktop navigation follows the returned `modules`; existing `permissions` remain the action/PAD contract. Independent module grants are checked on every request; removing a grant applies immediately. A changed user role requires login again. See the V6 contract for role/module CRUD and scoped form lookups.

Create registered NFC tags before configuring processes and storage. A process uses PROCESS mode (one process tag) or DEVICES mode (named devices, each with a tag). Enter `stationNumber`; the server creates/reuses its UUID. Provide `deviceCount` matching the devices array. No process code, device type or storage name is required: code is generated, type defaults to GENERAL and the area name defaults to `storageNumber`. Use `name`, `description`, `stationNumber`, `nfcMode` and tag selection for processes; use `storageNumber`, `description`, `stationNumber` and tag selection for storage areas. [Current payloads and migration details](PROCESS_ASSIGNMENT_V5.md).

Start PROCESS mode with `processNfcUid`; start DEVICES mode with exactly one `machineNfcUids` entry. Completion does not require another scan. A final process marked `quality=true` requires `passedQuantity`, `rejectedQuantity`, and remarks whenever rejects exist. Counts must sum to the lot quantity. The inspection and final completion commit together; the current lot closes and any rework is created separately. Ordinary final processes do not require a QC result. Biometric login and automatic pad logout are outside this phase.

Response IDs are server-generated UUIDs. Request fields use camelCase; existing database-backed responses generally retain snake_case. ISO-8601 dates include offsets. Handle `400` validation, `401` authentication, `403` access denial, and `409` business conflicts. Error responses use Problem Details with `code` and `detail`. Use the running OpenAPI document for the exact contract, and the collection for representative payloads and execution order.

Apply the Flyway migration through normal application startup; do not reset the database. Existing Operators become Process Managers, Managers become Production Managers, Admins become Admin (IT), and Super Admins become NW. Legacy display-only users are disabled. Review migrated process assignments and derived station scope and reconfigure legacy devices before using device mode. Old role-bearing tokens must be replaced by a new login.

Tag lifecycle is separate from assignment: new tags return status ACTIVE and assigned false. Deletion sets DELETED and preserves history; assigned tags must be unassigned first. Tag Management can list dropdown choices with GET /api/v1/nfc-tags?status=ACTIVE&assigned=false; process/storage-only screens use GET /api/v1/ui/lookups without acquiring Tag Management. Flyway V5 moves on-shift state to process assignments; V6 adds roles/modules. New style creation can submit `processIds` atomically with metadata; create-only users must use that path.
