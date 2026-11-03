# Configuration v8 and access design

## Agreed contract

The HTML file is a UI reference, not a frontend implementation task. Process and storage forms include existing required codes/names/types. A station reference creates or reuses a station (case-insensitive); its reference is immutable and remains the label used in routes and reports. Station metadata can be enriched later.

| UI field / behavior | API implementation |
|---|---|
| Process name, description, station reference | Process CRUD; station reference resolves transactionally |
| Process code, target duration | Retained additional frontend fields |
| Process NFC mode | PROCESS or DEVICES, mutually exclusive |
| Device name, description, type, NFC | Machine with one owning process; process save includes devices |
| Device count | Derived from the devices array; maximum 50 |
| Storage number, description, station reference, NFC | Storage area CRUD; storage units remain available for placement |
| Tag friendly name and physical UID | Independent tag registry, assignment and unassignment |
| Delete | Deactivation, retaining historical references |
| Roles | Seven fixed roles; editable permission sets and PC/PAD access |
| User assignment | One role, many station and process assignments |
| Quality process | Explicit quality flag; final quality completion requires quantities and remarks for rejects |

## Access model

Roles: NW, TOP_MANAGER, PRODUCTION_MANAGER, PROCESS_MANAGER, SUPERVISOR, ADMIN_IT, STATION_0. Migration maps SUPER_ADMIN to NW, ADMIN to ADMIN_IT, MANAGER to PRODUCTION_MANAGER, OPERATOR to PROCESS_MANAGER. Legacy display-only users are disabled. Legacy devices are disabled until explicitly reconfigured into DEVICES mode; historical usage and tag assignment records are retained. Existing multi-role users resolve deterministically to the highest applicable role. Existing tokens with old role names must log in again.

PC/PAD is a declared application channel bound into the signed login token, not a hardware attestation. Login requires clientType PC or PAD; refresh preserves it. Requests consult current database roles, permissions, enabled status and assignments. Role changes take effect immediately. Administrators can edit only these seven roles; NW/ADMIN_IT PC administration access is retained to prevent lockout.

Non-administrator PC sessions permit reporting/style configuration; operation/QC/override/transport actions require PAD. Effective permissions returned by `/auth/me` reflect the current channel. Station scope is separate from configurable action permissions. NW, ADMIN_IT and PRODUCTION_MANAGER have factory-wide scope. Other roles have assigned station scope; process operations and QC additionally require process assignment. TOP_MANAGER has factory reporting scope but no operation permission by default. Lists and aggregates must be scoped as well as mutations and direct lookups.

## Workflow

PROCESS mode starts with one process tag and uses no machine. DEVICES mode starts with exactly one device tag belonging to that process. Completion uses the authenticated user and requires no scan. Machine ownership, availability and active use are checked transactionally. Terminal transitions release reservations.

Final QC completion and inspection are one transaction. Passed plus rejected equals the complete lot quantity, quantities cannot be negative, and rejection requires remarks. The outcome is derived from the counts. The current lot closes; rework is a separately created style/lot and is not generated here. Earlier quality processes can complete normally; only a final quality process closes the lot. Biometrics and automatic pad logout are deferred.

## Integration deliverables

Postman v2.1 collection grouped by use case, local HTTP environment without secrets, manual token/ID variables (no request scripts), examples, setup order and role/channel notes. OpenAPI and the integration guide reflect the same fields. Database changes use a new Flyway migration; prior migrations remain unchanged.
