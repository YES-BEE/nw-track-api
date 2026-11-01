# NW Track API

Spring Boot modular-monolith implementation of the consolidated NW Track backend and deployment design.

Client developers should start with [docs/API_INTEGRATION_GUIDE.md](docs/API_INTEGRATION_GUIDE.md) for Android operator, admin portal, supervisor, and display-screen integration.

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

Nginx publishes HTTP/HTTPS and CoreDNS publishes local DNS. PostgreSQL remains on an isolated backend network; the API joins the backend and edge networks for database access and future Fleet Manager connectivity. Every factory uses the fixed local URL `https://nowash-track.com`.

## One-time Windows installation

From the repository root run:

```bat
deployment\windows\install.cmd start
```

The script requests Windows Administrator approval and then automatically:

1. Detects the active Wi-Fi adapter and IPv4 subnet.
2. Changes that Windows network profile to Private.
3. Creates `.env` and secure secrets when missing.
4. Generates a factory-internal CA and HTTPS certificate for `nowash-track.com`.
5. Binds DNS, HTTP and HTTPS only to the detected Wi-Fi address.
6. Creates Windows Firewall rules limited to that Wi-Fi interface and subnet.
7. Builds and starts PostgreSQL, the API, Nginx and CoreDNS.
8. Validates local DNS and calls the HTTPS `/actuator/health` endpoint.
9. Adds a Windows name-resolution policy for `nowash-track.com` that uses local CoreDNS without replacing the router's IPv4/IPv6 DNS servers; `install.cmd remove` removes the policy.

Windows Internet Connection Sharing/mobile hotspot also uses DNS port 53. If active, the installer backs up and disables connection sharing before stopping that service so NW Track DNS can start; `install.cmd remove` restores the saved sharing configuration and its Manual startup type.

Later lifecycle commands do not repeat network installation:

```bat
deployment\windows\nwtrack.cmd start
deployment\windows\nwtrack.cmd status
deployment\windows\nwtrack.cmd logs
deployment\windows\nwtrack.cmd certificate
deployment\windows\nwtrack.cmd stop
deployment\windows\install.cmd test
deployment\windows\install.cmd remove
```

`remove` deletes only the NW Track Wi-Fi firewall rule and returns binding to `127.0.0.1`. It does not delete PostgreSQL data.

Advanced override when automatic detection is unsuitable:

```bat
deployment\windows\install.cmd start -BindAddress 192.168.1.10 -AllowedSubnet 192.168.1.0/24
```

The installer configures a domain-specific DNS policy automatically on the Windows server itself. To make phones, TVs and other computers resolve the same name, set the router DHCP DNS server to the displayed factory server address; generic scripts cannot safely change arbitrary router models. Keep router port forwarding and UPnP exposure disabled. Install `deployment/certificates/ca.crt` as a trusted root certificate on every client device. Devices that cannot trust a private CA are not compatible with this HTTPS mode.

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

On an empty database the service creates one Super Admin from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`. Change the password immediately. Secrets are mandatory runtime configuration and have no production fallback values.

```http
POST /api/v1/auth/login
Content-Type: application/json

{"username":"admin","password":"..."}
```

Use the returned JWT as `Authorization: Bearer <token>`.

## Deployment notes

- Nginx and CoreDNS are the only host-published services. PostgreSQL and the API remain on Compose networks.
- Replace every generated secret before production and restrict the host firewall to the factory LAN/Wi-Fi subnet.
- HTTPS is mandatory. The installer creates a separate internal CA at each factory for the shared local hostname; its private key remains on that factory server and its CA certificate must be trusted by clients.
- The simulator implements the same transport-provider interface intended for the Phase 2 AGV/AMR Fleet Manager adapter.
- Back up the named PostgreSQL volume/database externally to meet the selected five-minute RPO; a single physical server alone cannot provide that RPO after server loss.
## API documentation

Swagger UI is enabled for the factory-local deployment at `/swagger-ui.html`. Set `SWAGGER_ENABLED=false` and recreate the API container if a factory security policy requires it to be disabled.

