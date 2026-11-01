# NW Track API

Spring Boot modular-monolith implementation of the consolidated NW Track backend and deployment design.

## Included

- Spring Boot 4.1 / Java 25 Maven build.
- PostgreSQL 17 schema managed automatically by Flyway.
- Local account authentication, Argon2 password hashes, JWT access tokens and revocable refresh sessions.
- One-time bootstrap Super Admin.
- Style Process route derivation with repeated-Station rejection.
- Placement-before-delink service for Process Area and Storage.
- NFC-managed Stations, Processes, Machines, Storage Areas, Storage Units and Trolleys with assignment history.
- Tagged multi-Lot Storage without manual tokens, plus exclusive Machine usage during Process execution.
- Versioned Admin/Manager Style revisions with atomic Lot propagation and immutable completed history.
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

## Deployment layout

Operational commands are intentionally grouped by operating system:

```text
deployment/windows   Windows one-time installation and lifecycle commands
deployment/linux     Linux one-time installation and lifecycle commands
```

Nginx is the only host-published service. PostgreSQL remains on an isolated backend network; the API joins the backend and edge networks for database access and future Fleet Manager connectivity.

## One-time Windows installation

From the repository root run:

```bat
deployment\windows\install.cmd start
```

The script requests Windows Administrator approval and then automatically:

1. Detects the active Wi-Fi adapter and IPv4 subnet.
2. Changes that Windows network profile to Private.
3. Creates `.env` and secure secrets when missing.
4. Binds Nginx only to the detected Wi-Fi address.
5. Creates a Windows Firewall allow rule limited to that Wi-Fi interface and subnet.
6. Builds and starts PostgreSQL, the API and Nginx.
7. Calls `/actuator/health` and reports the LAN URL.

Later lifecycle commands do not repeat network installation:

```bat
deployment\windows\nwtrack.cmd start
deployment\windows\nwtrack.cmd status
deployment\windows\nwtrack.cmd logs
deployment\windows\nwtrack.cmd stop
deployment\windows\install.cmd test
deployment\windows\install.cmd remove
```

`remove` deletes only the NW Track Wi-Fi firewall rule and returns binding to `127.0.0.1`. It does not delete PostgreSQL data.

Advanced override when automatic detection is unsuitable:

```bat
deployment\windows\install.cmd start -BindAddress 192.168.1.10 -AllowedSubnet 192.168.1.0/24 -Port 8080
```

The script does not and cannot create router port forwarding. Keep router port forwarding disabled. Devices on a bridged wired LAN may share the same subnet; strict SSID-only separation requires router guest-network, VLAN or wireless-isolation support.

## One-time Linux installation

From the repository root:

```bash
chmod +x deployment/linux/nwtrack.sh
sudo deployment/linux/nwtrack.sh install
```

Later operations:

```bash
sudo deployment/linux/nwtrack.sh status
sudo deployment/linux/nwtrack.sh logs
sudo deployment/linux/nwtrack.sh restart
sudo deployment/linux/nwtrack.sh stop
```

## Login

On an empty database the service creates one Super Admin from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`. Change the password immediately. Secrets are mandatory runtime configuration and have no production fallback values.

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

Swagger is disabled by default. For a controlled development environment, set `SWAGGER_ENABLED=true` in `.env`, recreate the API container, and open `/swagger-ui.html`. Disable it again before production handoff.

