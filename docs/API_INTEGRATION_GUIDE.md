# NW Track client integration guide

The complete frontend handoff is in [Postman setup and use cases](postman/README.md):

- [Collection: all REST APIs grouped by use case](postman/NW_Track.postman_collection.json)
- [LAN environment: HTTP by IP](postman/NW_Track_LAN.postman_environment.json)
- [Field coverage, access model and architecture](CONFIGURATION_V8_DESIGN.md)

Windows defaults to `http://<server-Wi-Fi-IP>`. Swagger is `/swagger-ui.html`, OpenAPI is `/v3/api-docs`, and public health is `/actuator/health`. Set the same base URL in every frontend. The server and clients must share the local network. Optional HTTPS is enabled with `deployment\windows\install.cmd start -EnableHttps`; that mode requires local domain DNS and client CA trust.

Login is `POST /api/v1/auth/login` with `username`, `password`, and required `clientType` (`PC` or `PAD`). Send `Authorization: Bearer <accessToken>` on protected requests. Get `/api/v1/auth/me` for the current role, permissions, stations and processes. Create a refresh session through `/auth/refresh-tokens`; `/auth/refresh` retains its PC/PAD channel. Honor forced password change and revoke refresh sessions on logout. Store tokens securely in the client.

The seven fixed roles replace the legacy roles. Requests check current database permissions and assignments, so permission removal and account disabling apply immediately. A changed role requires login again. Frontend navigation should follow the returned permissions, while backend authorization remains authoritative.

Create registered NFC tags before configuring processes and storage. A process uses PROCESS mode (one process NFC) or DEVICES mode (one or more devices, each with a tag). Every device has one owning process. Enter `stationReference` in process/storage forms; a station UUID is created or reused automatically. Storage means a storage area; units remain separately supported for placement. Include codes, names and machine types in the relevant forms in addition to the mock's inputs. Process save is transactional and unfinished production blocks reconfiguration.

Start PROCESS mode with `processNfcUid`; start DEVICES mode with exactly one `machineNfcUids` entry. Completion does not require another scan. A final process marked `quality=true` requires `passedQuantity`, `rejectedQuantity`, and remarks whenever rejects exist. Counts must sum to the lot quantity. The inspection and final completion commit together; the current lot closes and any rework is created separately. Ordinary final processes do not require a QC result. Biometric login and automatic pad logout are outside this phase.

Response IDs are server-generated UUIDs. Request fields use camelCase; existing database-backed responses generally retain snake_case. ISO-8601 dates include offsets. Handle `400` validation, `401` authentication, `403` access denial, and `409` business conflicts. Error responses use Problem Details with `code` and `detail`. Use the running OpenAPI document for the exact contract, and the collection for representative payloads and execution order.

Apply the Flyway migration through normal application startup; do not reset the database. Existing Operators become Process Managers, Managers become Production Managers, Admins become Admin (IT), and Super Admins become NW. Legacy display-only users are disabled. Review migrated station/process assignments and reconfigure legacy devices before using device mode. Old role-bearing tokens must be replaced by a new login.
