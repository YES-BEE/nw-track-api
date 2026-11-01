# Production readiness

## Implemented baseline

- Stateless JWT authentication, revocable refresh sessions, Argon2 password hashes and role authorization.
- Flyway-owned PostgreSQL schema with PostgreSQL integration testing.
- Nginx-only host publication; PostgreSQL is isolated on an internal Docker network.
- Mandatory runtime secrets with no application fallback passwords or signing keys.
- Non-root API container, read-only API filesystem, bounded container logs and no-new-privileges controls.
- Health checks, graceful shutdown, transactional workflow operations, audit records and outbox delivery.
- Swagger disabled by default, scoped WebSocket origins and login-specific Nginx rate limiting.
- Windows and Linux deployment tooling, network binding and Windows firewall automation.
- CI verification and container build on every pull request.

## Site acceptance gates

The software baseline alone is not a production acceptance certificate. Before each factory go-live, record evidence for:

1. Internal DNS and HTTPS certificate trust on every supported mobile, browser and display device.
2. Independent PostgreSQL backup/WAL destination, restore rehearsal, measured RPO and measured RTO.
3. Factory server sizing, UPS, disk monitoring, time synchronization and OS patch policy.
4. Load test at the agreed operator/display concurrency and reconnect storm profile.
5. Authorization, API security, dependency/container scanning and penetration-test findings closure.
6. Signed APK distribution, signing-key custody, supported Android/NFC device qualification and lost-device procedure.
7. Upgrade, backward-compatible migration and rollback rehearsal using representative production data.
8. Router review confirming no inbound port forwarding or UPnP exposure.

TLS and independent disaster-recovery infrastructure are site concerns and cannot be completed by application source code alone.
