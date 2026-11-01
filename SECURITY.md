# Security policy

Report suspected vulnerabilities privately to the YesBee NW Track product owner. Do not open a public issue containing credentials, tokens, NFC identifiers, factory topology, production data, or exploit details.

Production deployments must use unique per-factory secrets, a restricted host firewall, no router port forwarding, externally verified backups, and HTTPS before credentials traverse any shared or untrusted network. `.env`, certificates, private keys, database exports, and production logs must never be committed.

Supported releases are the current production release and the immediately preceding release during its documented rollback window.
