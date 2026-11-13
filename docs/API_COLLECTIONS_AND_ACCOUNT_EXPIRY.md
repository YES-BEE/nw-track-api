# User status, modules, collections and initial passwords

These changes require a frontend update: collection responses now use a page object.
Flyway migration V7 runs automatically on service startup.

## User status

Use `Authorization: Bearer <accessToken>` from a PC login with User Management access.

Set `status` to `ACTIVE` or `INACTIVE` in the same create or update request:

```http
POST /api/v1/users
Content-Type: application/json

{
  "username":"manager1",
  "displayName":"Production Manager",
  "initialPassword":"InitialPassword123!",
  "role":"PRODUCTION_MANAGER",
  "processAssignments":[],
  "status":"INACTIVE"
}
```

```http
PUT /api/v1/users/{userId}
Content-Type: application/json

{
  "username":"manager1",
  "displayName":"Production Manager",
  "role":"PRODUCTION_MANAGER",
  "processAssignments":[],
  "status":"ACTIVE"
}
```

Omitting `status` defaults to `ACTIVE` on POST and preserves the current value on PUT.
Invalid status values return 400. User responses include `status` alongside the legacy
`enabled` boolean. There is no separate user status endpoint. User DELETE still
deactivates an account. Inactive users cannot log in or continue using application APIs.

## Module grants

| Module key | Name | Default built-in roles |
| --- | --- | --- |
| `trolleys` | Trolley Configuration | `ADMIN_IT`, `NW`, `PRODUCTION_MANAGER` |
| `lots` | Lot Management | `ADMIN_IT`, `NW`, `PRODUCTION_MANAGER`, `STYLE_CONFIGURATOR` |

These grants are returned in the existing login and `/auth/me` module arrays and can
also be assigned to custom roles. They authorize the PC application; PAD operational
permissions retain their existing checks.

Trolley Configuration uses POST/GET `/api/v1/trolleys`, GET `/api/v1/trolleys/available`,
GET/PUT `/api/v1/trolleys/{id}`, and PATCH `/api/v1/trolleys/{id}/status`.
PUT accepts `{"code":"TROLLEY-01"}`; status PATCH accepts `{"active":false}`.

Lot Management uses:

- GET `/api/v1/lots` to search all lots.
- GET/POST `/api/v1/styles/{styleId}/lots` to list/create lots for a style.
- GET `/api/v1/lots/{id}` and `/api/v1/lots/{id}/timeline`.
- POST `/api/v1/lots/{id}/cancel` with `{"reason":"..."}`.

Style Configurator manages lots only for styles it created, including lots added by
other users to those styles. IT Admin, NW Admin and Production Manager retain general
lot access. The style selector follows the same ownership rule.

Style creation accepts `lotCount` and optional `quantity`; planned lots are generated
only when the style is released. Additional creation accepts `{"lotCount":3,"quantity":100}`
and returns `createdCount` plus `items`. Numbers are generated as
`LOT-<sanitized styleNumber>-<per-style sequence>` with a minimum three-digit suffix. See [Lot Management](LOT_MANAGEMENT.md) for migration and uniqueness guarantees. Do not send `lotNumber`.

Lot Management also grants PUT/DELETE `/api/v1/lots/{id}/trolley` for assignment and
unassignment, plus GET `/api/v1/trolleys/available` for selection. See
[Lot Management](LOT_MANAGEMENT.md) for complete requests, state rules and ownership.
Style Configurator may release its own styles. Lot Management supports PUT `/api/v1/lots/{id}` with `{quantity,reason}` for quantity editing before completion, excluding transit and cancelled lots. Style editing, dispatch and QC retain their existing permissions.
## Search and pagination

```http
GET /api/v1/lots?search=LOT-01&page=0&size=20
```

```json
{"items":[],"page":0,"size":20,"totalElements":0}
```

`page` starts at zero and defaults to zero. `size` defaults to 20, with a range of
1–100. Invalid values return 400. `search` is an optional case-insensitive literal
substring, up to 200 characters, across projected database fields. `%` and `_` are
literal characters. Filters and authorization are applied before the total and page
are calculated. Related fields added when formatting an item are not searched.

This applies to users, roles/options, modules, stations, processes, machines, trolleys,
storage areas/units/occupancies, tags/statuses/assignment history, transport locations
and requests, styles/revisions/lots, lot timelines, incoming work, station-visit
processes, sessions, notifications, dashboard lot/WIP lists and report histories.
Notifications use `size` instead of the former `limit` parameter. The role update
response is also a page of roles.

Nested collections are also pages: UI lookup fields, dashboard `stations`, style
progress `lots`, station display `incoming`/`workInProgress`, and sync bootstrap
`visits`/`processes` and change `processEvents`/`locationEvents`. A request's paging
parameters apply independently to each collection. Read all pages of each collection.
Status count summaries, permission constants, derived routes and configuration arrays
inside individual resources/authentication responses remain complete structures.

For `/api/v1/sync/changes`, keep `since` fixed while reading pages. Use the first
response's `serverTime` as `until` on every subsequent page, then advance `since` to
that watermark only after both event collections are fully consumed. Do not use
search when synchronizing a complete dataset. List pages reflect live data; records
can move between pages when other users edit them.

Filtering, count and LIMIT/OFFSET run in PostgreSQL, so only a page is loaded into
the API. Generic substring searches and deep offsets can still scan many rows;
very large installations may need dedicated indexed searches for frequent queries.

## Seven-day initial account lifetime

Only users newly created through POST `/users` after this change receive an
`initial_password_expires_at` timestamp, exactly seven days after creation. The
timestamp is returned in user details and `/auth/me`.

Before expiry, the user logs in, reads `/auth/me` if needed, and changes the password:

```http
POST /api/v1/auth/change-password
Content-Type: application/json

{"currentPassword":"InitialPassword123!","newPassword":"MyNewPassword123!"}
```

The new password must differ from the current password. Until this first password
change, the user can call only `/auth/me`, `/auth/change-password` and `/auth/logout`.
A successful password change clears the deadline permanently.

After seven days without that change, login, refresh and existing application tokens
are rejected immediately. A scheduled cleanup deletes the pending account within
the next minute while the service is running (and after restart). Creating users
also cleans expired accounts, allowing an expired username to be recreated without
waiting for the scheduled run. Old tokens cannot access a recreated account.

Inactive new accounts also expire. Reactivation, profile edits and temporary password
resets do not extend their original deadline. Existing users, the bootstrap admin,
and accounts that have completed the first password change are excluded. A later
admin password reset never starts a new deletion period.

## Windows installer

The empty-argument Administrator launch is fixed. Run from the repository root:

```bat
deployment\windows\install.cmd
```

The default action is `start`; `deployment\windows\install.cmd start` is equivalent.
