# NW Track API

For the agreed dashboard/display screen fields and refresh contract, see [Dashboard and factory displays](docs/DASHBOARD_DISPLAY.md).

For generated lot numbers, planned lot creation at release, optional quantities,
ownership and trolley mapping, see [Lot Management](docs/LOT_MANAGEMENT.md).

For user active/inactive fields, Trolley/Lot module grants, paged collection responses,
and the seven-day initial account lifetime, see [the API change guide](docs/API_COLLECTIONS_AND_ACCOUNT_EXPIRY.md).

Spring Boot modular-monolith implementation of the consolidated NW Track backend and deployment design.

For on-prem retention and one-command support ZIP extraction, see [on-prem logging](docs/ON_PREM_LOGGING.md).

For a database backup ZIP, run `deployment\windows\install.cmd backup`. It writes to
`backups\` by default; see [database backup and local recovery](docs/DATABASE_BACKUP.md).

Client developers should start with [docs/API_INTEGRATION_GUIDE.md](docs/API_INTEGRATION_GUIDE.md) for Android operator, admin portal, supervisor, and display-screen integration.

For the latest role-based HTML, use the [v21 screen, field and button integration map](docs/UI_V21_API_INTEGRATION.md), including supported APIs and remaining backend gaps.

## Included

- Spring Boot 4.1 / Java 25 Maven build.
- PostgreSQL 17 schema managed automatically by Flyway.
- Local account authentication, Argon2 password hashes, JWT access tokens and revocable refresh sessions.
- One-time bootstrap NW administrator.
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

Nginx publishes the API on the local network. Windows defaults to HTTP access by IP address; the optional HTTPS/domain deployment is preserved.

## Windows installation

From the repository root run:

```bat
deployment\windows\install.cmd start
```

Approve the Administrator prompt. The installer detects the active Wi-Fi IPv4 address, allows HTTP port 80 from that Wi-Fi subnet, and starts the deployment. It prints the server IP address. No client certificates or DNS changes are required in this mode. Existing certificates are preserved; local DNS and certificate installation are skipped.

Connect your phone to the same Wi-Fi and open these URLs using the printed address:

```text
http://192.168.1.10/actuator/health
http://192.168.1.10/swagger-ui.html
```

Replace `192.168.1.10` with the printed server address. The root `/` has no page. HTTP sends credentials and data without encryption. Access remains limited to the local network; changing to another network does not provide remote access. Guest Wi-Fi/client isolation can prevent devices reaching each other. Rerun the installer if the server's Wi-Fi address changes.

Lifecycle commands:

```bat
deployment\windows\nwtrack.cmd start
deployment\windows\nwtrack.cmd status
deployment\windows\nwtrack.cmd logs
deployment\windows\nwtrack.cmd stop
deployment\windows\install.cmd test
deployment\windows\install.cmd remove
```

To enable the preserved HTTPS/domain mode later:

```bat
deployment\windows\install.cmd start -EnableHttps
```

This enables `https://nowash-track.com`, local CoreDNS, port 443 and certificate generation. Configure the router DHCP DNS server to the printed server IP and trust `deployment/certificates/ca.crt` on each client. The server receives its own domain DNS policy and CA trust automatically. Keep router port forwarding disabled. To return to IP-based HTTP, run `install.cmd start` without the flag. This removes the server domain DNS policy and restores Internet Connection Sharing if previously disabled by the installer.

Advanced address override:

```bat
deployment\windows\install.cmd start -BindAddress 192.168.1.10 -AllowedSubnet 192.168.1.0/24
```

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
sudo deployment/linux/nwtrack.sh certificate
sudo deployment/linux/nwtrack.sh stop
```

## Login

On an empty database the service creates one NW administrator from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`. Change the password immediately. Secrets are mandatory runtime configuration and have no production fallback values.

```http
POST /api/v1/auth/login
Content-Type: application/json

{"username":"admin","password":"...","clientType":"PC"}
```

Use the returned JWT as `Authorization: Bearer <token>`.

## Deployment notes

- Nginx and CoreDNS are the only host-published services. PostgreSQL and the API remain on Compose networks.
- Replace every generated secret before production and restrict the host firewall to the factory LAN/Wi-Fi subnet.
- In optional HTTPS mode, the installer creates a separate internal CA at each factory for the shared local hostname; its private key remains on that factory server and its CA certificate must be trusted by clients.
- The simulator implements the same transport-provider interface intended for the Phase 2 AGV/AMR Fleet Manager adapter.
- Back up the named PostgreSQL volume/database externally to meet the selected five-minute RPO; a single physical server alone cannot provide that RPO after server loss.
## Frontend integration handoff

Import the collection and LAN environment from [docs/postman](docs/postman/README.md). It includes 157 requests in 22 use-case folders. The [v21 role/module contract](docs/ROLE_MODULES_V21.md) documents custom roles, independent screen grants, Style Configurator ownership and migration. Process-only assignments, NFC modes and final QC remain documented in the [configuration design](docs/CONFIGURATION_V8_DESIGN.md).

## API documentation

Swagger UI is enabled for the factory-local deployment at `/swagger-ui.html`. Set `SWAGGER_ENABLED=false` and recreate the API container if a factory security policy requires it to be disabled.
