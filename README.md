# NW Track API

Spring Boot modular-monolith implementation of the consolidated NW Track backend and deployment design.

## Included

- Spring Boot 4.1 / Java 25 Maven build.
- PostgreSQL 17 schema managed automatically by Flyway.
- Local account authentication, Argon2 password hashes, JWT access tokens and revocable refresh sessions.
- One-time bootstrap Super Admin.
- Style Process route derivation with repeated-Station rejection.
- Placement-before-delink service for Process Area and Storage.
- Factory-wide optional Storage token policy and Station-scoped active-token uniqueness.
- WebSocket/STOMP endpoint for live dashboards and immediate notifications.
- Transport provider interface and simulator provider.
- Station, Process, Trolley, NFC, Storage, User, Style and Lot administration APIs.
- Operator receive/place, trolley link, dispatch, storage, ordered Process and QC workflows.
- Dashboard/display/report APIs, notification outbox, offline synchronization and persistent transport requests.
- Actuator health/metrics, Swagger/OpenAPI, unit tests and PostgreSQL integration tests.

## Build

Prerequisites: JDK 25 and Maven 3.6.3 or later.

```powershell
mvn clean verify
```

## Run with PostgreSQL

1. Copy `.env.example` to `.env` and replace all secrets.
2. From PowerShell run `powershell -ExecutionPolicy Bypass -File .\nwtrack.ps1 start`, or from Command Prompt run `nwtrack.cmd start`.
3. The script generates missing development secrets, builds the image and starts PostgreSQL, API and Nginx.
4. Verify `http://localhost:8080/actuator/health` and `http://localhost:8080/swagger-ui.html`.

The Compose file intentionally does not publish API or PostgreSQL ports. The production Nginx service joins the `backend` network and is the only host-published entry point.

## Login

On an empty database the service creates one Super Admin from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`. Change the password and remove the bootstrap secret as part of deployment hardening.

```http
POST /api/v1/auth/login
Content-Type: application/json

{"username":"admin","password":"..."}
```

Use the returned JWT as `Authorization: Bearer <token>`.

## Deployment notes

- Nginx is the only host-published service. PostgreSQL and the API remain on the Compose backend network.
- Replace every generated secret before production and restrict the host firewall to the factory LAN/Wi-Fi subnet.
- TLS is intentionally not enabled because the current deployment decision is HTTP on the dedicated factory network. Add an internal CA certificate before carrying credentials over any shared or untrusted network.
- The simulator implements the same transport-provider interface intended for the Phase 2 AGV/AMR Fleet Manager adapter.
- Back up the named PostgreSQL volume/database externally to meet the selected five-minute RPO; a single physical server alone cannot provide that RPO after server loss.
## API documentation

After the service starts, open `http://localhost:8080/swagger-ui.html` for the interactive Swagger UI.
The OpenAPI documents are available at `http://localhost:8080/v3/api-docs` and
`http://localhost:8080/v3/api-docs.yaml`. Use the login endpoint first, then enter the returned JWT
in Swagger's **Authorize** dialog as a bearer token.

