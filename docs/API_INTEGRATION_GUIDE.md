# NW Track client integration guide

This guide describes how the Android operator app, admin web portal, supervisor screens, and factory display application integrate with the NW Track API. The running OpenAPI document remains the authoritative field-level contract.

## Connection details

| Item | Value |
|---|---|
| Base URL | `https://nowash-track.com` |
| REST prefix | `/api/v1` |
| Swagger UI | `https://nowash-track.com/swagger-ui.html` |
| OpenAPI JSON | `https://nowash-track.com/v3/api-docs` |
| WebSocket/STOMP endpoint | `wss://nowash-track.com/ws` |
| Health | `https://nowash-track.com/actuator/health` |

Every client must trust the factory CA certificate in `deployment/certificates/ca.crt`. Do not disable TLS validation in an app. Android builds should include this CA in a Network Security Configuration or managed trust store.

All protected REST calls use:

```http
Authorization: Bearer <access-token>
Content-Type: application/json
Accept: application/json
```

Use UUID values returned by the server; do not construct them in UI code unless the API explicitly calls for a client-generated ID, such as offline sync events.

## Authentication and session lifecycle

### Login

```http
POST /api/v1/auth/login
```

```json
{
  "username": "operator01",
  "password": "secret"
}
```

The response contains `accessToken`, `tokenType`, `expiresIn`, `username`, and `roles`. Store access and refresh tokens in Android Keystore-backed encrypted storage or a secure browser memory/session mechanism. Never use local storage for long-lived admin credentials.

After login:

1. Call `GET /api/v1/auth/me`.
2. If `must_change_password` is true, show the forced password-change screen and call `POST /api/v1/auth/change-password`.
3. Create a refresh token with `POST /api/v1/auth/refresh-tokens`.
4. Refresh an expired access token using `POST /api/v1/auth/refresh` and `{ "refreshToken": "..." }`.
5. On logout call `POST /api/v1/auth/logout`; clear both tokens locally even when the request fails.

Session management endpoints are `GET /api/v1/auth/sessions` and `DELETE /api/v1/auth/sessions/{id}`.

## Error handling

Errors use RFC 9457-style `application/problem+json` responses. Clients should use the machine-readable `code` and show `detail` to the user when appropriate.

```json
{
  "type": "about:blank",
  "title": "Business rule violation",
  "status": 409,
  "detail": "Scan the Process NFC tag",
  "code": "PROCESS_SCAN_REQUIRED"
}
```

Handle status codes consistently:

- `400`: invalid payload; highlight validation fields.
- `401`: token missing/expired; try refresh once, then return to login.
- `403`: role or station assignment does not permit the action.
- `404`: refresh the local list because the object no longer exists.
- `409`: workflow/business conflict; keep the screen state and show `code`/`detail`.
- `5xx` or network timeout: do not assume a command failed. Query the affected resource or use offline synchronization before retrying.

For state-changing calls, disable double submission while a request is outstanding.

## Android operator application

### Startup and station context

After authentication call:

- `GET /api/v1/auth/me` for user, station assignment, and on-shift state.
- `GET /api/v1/factory-config` for factory name and transport provider.
- `GET /api/v1/sync/bootstrap` for assigned station visits and process executions.
- `GET /api/v1/notifications?limit=50` for immediate-delivery notifications retained by the server.

An operator must be assigned and active/on-shift at the lot's current station. Admin and super-admin users bypass station assignment checks.

### NFC scanning

Read the physical NFC UID using Android NFC APIs, normalize it as supplied by the reader, and resolve it through:

```http
POST /api/v1/nfc/resolve
```

```json
{ "uid": "04AABBCCDDEE" }
```

The response identifies the tag as `TROLLEY`, `STATION`, `PROCESS`, `MACHINE`, `STORAGE_AREA`, or `STORAGE_UNIT` and includes the mapped object identifiers. Route the user to the appropriate screen from `asset_type`; never infer asset type from UID format.

Lots do not have NFC tags. The app identifies a lot through its ID/number and through the currently linked trolley.

### Typical operator workflow

1. Load inbound work with `GET /api/v1/stations/{stationId}/incoming`.
2. Scan the trolley and call `/api/v1/nfc/resolve` to identify its lot.
3. Receive the station visit and choose process or storage placement:

```http
POST /api/v1/station-visits/{visitId}/receive-and-place
```

Process placement:

```json
{
  "destinationType": "PROCESS",
  "processExecutionId": "<uuid>",
  "storageLocationId": null
}
```

Storage placement:

```json
{
  "destinationType": "STORAGE",
  "processExecutionId": null,
  "storageLocationId": "<uuid>"
}
```

4. Load ordered steps using `GET /api/v1/station-visits/{visitId}/processes`.
5. Start a process only after scanning its process NFC and at least one available machine NFC:

```http
POST /api/v1/process-executions/{executionId}/start
```

```json
{
  "reason": null,
  "processNfcUid": "PROCESS-NFC-UID",
  "machineNfcUids": ["MACHINE-1-UID"]
}
```

6. Use the same transition route with `pause`, `resume`, `block`, `unblock`, `skip`, or `complete`. Completion requires rescanning the process and every active machine used by that execution.
7. Replace a failed machine using `POST /api/v1/process-executions/{id}/replace-machine` with `oldMachineNfcUid`, `newMachineNfcUid`, and `reason`.
8. Before leaving a non-final station, link a trolley using `POST /api/v1/lots/{lotId}/trolley-link` and `{ "trolleyId": "<uuid>" }`.
9. Dispatch using `POST /api/v1/lots/{lotId}/dispatch`. The backend enforces the configured station order.

For storage retrieval, scan the storage unit and trolley, resolve both NFC tags, then call:

```http
POST /api/v1/storage-locations/{storageUnitId}/retrieve
```

```json
{
  "lotId": "<uuid>",
  "trolleyId": "<uuid>"
}
```

### QC

At the final QC station submit:

```http
POST /api/v1/lots/{lotId}/qc
```

```json
{
  "outcome": "PASS",
  "inspectedQuantity": 100,
  "goodQuantity": 98,
  "rejectedQuantity": 2,
  "reason": "Two rejected pieces"
}
```

Retrieve the recorded result with `GET /api/v1/lots/{lotId}/qc`.

### Offline synchronization

- Initial dataset: `GET /api/v1/sync/bootstrap`
- Incremental changes: `GET /api/v1/sync/changes?since=<ISO-8601-offset-time>`
- Upload events: `POST /api/v1/sync/events`

Each client event needs a stable client-generated UUID. Retrying the same event ID with identical content is idempotent; reusing it with different content produces `SYNC_EVENT_CONFLICT`. Preserve event order per lot and retain rejected events for operator resolution.

## Admin web portal

Use roles returned at login to build navigation, but treat backend authorization as authoritative.

### Users

- `POST /api/v1/users`
- `GET /api/v1/users`
- `GET /api/v1/users/{id}`
- `PUT /api/v1/users/{id}/roles`
- `PUT /api/v1/users/{id}/station-assignment`
- `PATCH /api/v1/users/{id}/status`
- `PUT /api/v1/users/{id}/password`

The station assignment payload contains `stationId` and `activeOnShift`. A password reset creates a temporary password and revokes active refresh tokens.

### Master data and NFC administration

The portal manages stations, processes, trolleys, machines, storage areas/units, transport locations, and NFC assignments through:

- `/api/v1/stations`
- `/api/v1/processes`
- `/api/v1/trolleys`
- `/api/v1/machines`
- `/api/v1/storage-areas`
- `/api/v1/storage-areas/{areaId}/units`
- `/api/v1/storage-units/{id}`
- `/api/v1/transport-locations`
- `/api/v1/nfc-tags`
- `/api/v1/nfc-assignments`
- `/api/v1/nfc-assignments/{tagId}/history`

Create/update forms should scan NFC UIDs rather than asking users to type them. NFC reassignment and deactivation require a reason and are audited.

### Styles and lots

Recommended portal sequence:

1. `POST /api/v1/styles` with `styleNumber` and `description`.
2. Load active processes and save the ordered UUID array using `PUT /api/v1/styles/{id}/processes`.
3. Preview station grouping using `GET /api/v1/styles/{id}/derived-route`.
4. Release with `POST /api/v1/styles/{id}/release`; QC must be last.
5. Create lots through `POST /api/v1/styles/{id}/lots` with `lotNumber` and positive `quantity`.
6. Monitor `GET /api/v1/styles/{id}/progress` and `GET /api/v1/lots/{id}/timeline`.

Admin edits for unstarted lots use `POST /api/v1/styles/{id}/revisions/unstarted`. Manager edits across all in-progress lots use `/revisions/in-progress`. Both accept `description`, ordered `processIds`, `quantity`, and mandatory `reason`. Display revision history through `GET /api/v1/styles/{id}/revisions`.

### Supervisor functions

- Process correction: `POST /api/v1/process-executions/{id}/correction`
- Wrong-station override: `POST /api/v1/station-visits/{id}/wrong-station-override`
- Factory dashboard and reports under `/api/v1/dashboard` and `/api/v1/reports`
- AGV/AMR request monitoring and recovery under `/api/v1/transport-requests`
- Provider state: `GET /api/v1/transport/providers/health`

Every override must capture a reason in the UI.

## Display application and supervisor screens

Create a dedicated user with the `DISPLAY_DEVICE` role. Do not embed an administrator password in a TV browser.

Available read models:

- Factory summary: `GET /api/v1/displays/factory-summary`
- Station screen: `GET /api/v1/displays/stations/{stationId}`
- Dashboard summary: `GET /api/v1/dashboard`
- Filtered lots: `GET /api/v1/dashboard/lots?status=<status>`
- Station WIP: `GET /api/v1/stations/{stationId}/work-in-progress`
- Style progress: `GET /api/v1/styles/{styleId}/progress`

Recommended display behavior:

1. Authenticate once at display startup using a dedicated display account.
2. Refresh access tokens before expiry.
3. Poll the display read model every 10–15 seconds with a randomized 0–2 second offset to prevent all screens refreshing simultaneously.
4. Show a stale-data indicator after two failed refreshes and retain the last successful response.
5. Return to an activation/login screen after repeated authentication failure.
6. Use CSS responsive layout and browser full-screen/kiosk mode for Android TV or Smart TV browsers.

### WebSocket updates

STOMP destinations are:

- `/topic/stations/{stationId}` for station events.
- `/user/queue/events` for the authenticated user's events.

The handshake endpoint currently requires the bearer token in the HTTP `Authorization` header. Native Android/STOMP clients can supply it. Standard browser `WebSocket` cannot add arbitrary handshake headers, so web portal and TV integrations should use REST polling until a browser-compatible WebSocket authentication mechanism is implemented. Do not pass bearer tokens in URL query strings.

## Client implementation conventions

- Generate client models from `/v3/api-docs` where practical, but wrap the generated client behind app-specific repositories/services.
- Use ISO-8601 timestamps with offsets and treat server time as authoritative.
- Treat enums and workflow states as case-sensitive values returned by the API.
- Use connect timeout 5 seconds and request timeout 15 seconds for normal LAN calls; process no command indefinitely.
- Retry GET requests with bounded exponential backoff. Retry mutations only when the operation is explicitly idempotent or after reading current server state.
- Log correlation information and problem `code`, but never passwords, tokens, or full NFC identifiers in production logs.
- On `401`, perform only one token refresh per group of concurrent failed calls to avoid a refresh storm.

## Operations commands

From the repository root, stop all application containers with one command while retaining PostgreSQL data:

```powershell
.\deployment\windows\nwtrack.cmd stop
```

Linux:

```bash
sudo deployment/linux/nwtrack.sh stop
```

These commands remove running containers and Compose networks but preserve the named PostgreSQL volume. Do not use the `reset` action unless permanent database deletion is intended and confirmed.

