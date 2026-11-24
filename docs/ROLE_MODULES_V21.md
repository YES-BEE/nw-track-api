# v21 roles and module authorization â€” V6 contract

Implemented 7 November 2026. This supersedes the earlier fixed-role/module limitations in the v8 documentation. The backend now supports custom roles, independent desktop module grants and custom module placeholders. The click dummy remains a reference UI: its localStorage handlers must be replaced by these APIs using the [updated field/action handoff](UI_V21_API_INTEGRATION.md).

## Default personas

| Display name | Role code | Default PC modules |
| --- | --- | --- |
| NW | NW | dashboard, reports, process, storage, tags, styles, users, roles |
| IT Admin | ADMIN_IT | dashboard, reports, process, storage, tags, styles, users, roles |
| Top Manager | TOP_MANAGER | dashboard, reports |
| Production Manager | PRODUCTION_MANAGER | dashboard, reports, process, storage, tags, styles |
| Supervisor | SUPERVISOR | dashboard, reports |
| Process Manager | PROCESS_MANAGER | none; login/me still work |
| Style Configurator | STYLE_CONFIGURATOR | newstyle/lots; create, view and release own styles; manage own styles' lots |

All seven can log in on PC by default, including Supervisor. Existing PAD action permissions/channels remain for the operational personas. Style Configurator can also log in on PAD to list its own styles and lots, scan trolley tags, and map or unmap trolleys on its own lots. V12 enables PAD and grants Trolley Configuration by default. V6 renames the existing STATION_0 role **in place** to STYLE_CONFIGURATOR, preserving its user associations and historical IDs while removing its old operational permissions. Its refresh sessions are revoked; old role-bearing JWTs require login again. Station 0 locations/production history are not renamed or deleted.

Custom roles are scoped to assigned active processes/stations. A scoped configuration user can create a process only at an already authorized station; that new process is assigned back to its creator with activeOnShift false so it remains manageable without automatically granting operation access. They do not gain the global data scope of NW, IT Admin, Top Manager or Production Manager by choosing a similar name. Role/module names are case-insensitively unique, including retired records. Built-in roles cannot be deleted. A custom role cannot be deleted until every assigned user (including disabled users) is reassigned. Names/codes are immutable on edit, matching the dummy's read-only role name.

## Frontend navigation source

Login, refresh and `GET /api/v1/auth/me` now return `modules`:

```json
[
  {"key":"process","name":"Process Configurations","built_in":true},
  {"key":"custom_example","name":"Maintenance","built_in":false}
]
```

Use these keys directly for `nwShowConfig()`/sidebar visibility. Do **not** derive desktop navigation from old coarse `permissions`, role display names or local `roleDefs`. `permissions` still describes existing action/PAD permissions and is not a replacement for module grants. The server checks current grants on every HTTP request; changing grants applies to already-issued tokens without needing a fresh login. Changing a user's role requires login again. Role deactivation/channel disabling also blocks existing tokens.

PC endpoints map independently: dashboard APIs â†’ dashboard, reports/progress â†’ reports, process/machine configuration â†’ process, storage configuration â†’ storage, NFC registry/assignments â†’ tags, user administration â†’ users, access-role/module administration â†’ roles, style management â†’ styles. New Style is a restricted create/view-own path, not general style editing. Unmapped operational endpoints keep their existing action/channel guards and are unavailable to non-admin PC sessions. PAD operation APIs retain their existing action permissions and assignment/shift checks.

## Roles APIs and click mapping

All management calls need the `roles` module on PC.

| UI action | API |
| --- | --- |
| Open Role Management | GET /api/v1/access-roles and GET /api/v1/modules |
| Create New Role â†’ Save Role | POST /api/v1/access-roles |
| Edit role â†’ Save Role | PUT /api/v1/access-roles/{code} |
| Delete custom role | DELETE /api/v1/access-roles/{code} |
| Populate user role dropdown | GET /api/v1/access-roles/options; users or roles module suffices |
| Legacy/PAD action permission catalog | GET /api/v1/access-roles/permissions |

`#roleName` maps to create `name`. `input[name="roleModule"]` checked values map to `modules` (keys, not labels). Dynamic module names come from GET modules. Retain `newstyle` for Style Configurator: the server automatically preserves it even though v21 excludes it from the checkbox list. Style Configurator cannot receive `styles`; use another role if editing styles is required.

Create:

```json
{"name":"Line Manager","modules":["dashboard","process"],"pcAccess":true,"padAccess":false}
```

POST returns the created role record, including generated `code` such as `CUSTOM_<uuid-without-hyphens>`. Copy that exact code to user `role`; do not send the label Line Manager. Default channels on custom creation: PC true, PAD false. Initial legacy permission is READ unless an explicit validated `permissions` set is supplied.

Update:

```json
{"modules":["dashboard","storage","custom_example"]}
```

PUT replaces the complete module list and returns the full active role list. Omitted `pcAccess`, `padAccess` and `permissions` are preserved on update. No unexposed PAD permissions are cleared by editing module boxes. READ is retained/added for session compatibility when updating only modules. If legacy clients send only `permissions`, broad module grants are derived for compatibility (CONFIGURE â†’ process/storage/tags, REPORTS â†’ dashboard/reports, STYLES â†’ styles, USERS â†’ users, ROLES â†’ roles); new clients must send explicit `modules` for independent grants.

GET role record fields: `code`, `name`, `description`, `built_in`, `active`, `pc_access`, `pad_access`, `permissions`, `modules`. GET options returns only `{code,name}` records, so user administration does not need role-administration access. DELETE soft-deactivates an unassigned custom role and returns `{code,active:false}`.

NW and IT Admin must retain PC access and users/roles modules. Their legacy action permission updates must also retain READ/USERS/ROLES. These protections prevent role-editor lockout. Built-in deletion â†’ 409 BUILT_IN_ROLE; assigned custom deletion â†’ 409 ROLE_IN_USE; invalid module or lockout edit â†’ 400; duplicate name â†’ 409 DATA_CONFLICT. Role/module management writes are audited.

## Add New Module

`#addNewModuleCheck` reveals `#newModuleName`; no request is needed until Save Role.

1. POST `/api/v1/modules` with `{"name":"Maintenance"}`.
2. Save returned `key` (generated `custom_<uuid-without-hyphens>`) and append it to the role's `modules` array.
3. POST/PUT the role. These are two requests: if role save fails, retain the registered module key and retry the role save; do not create the module again.
4. GET modules reloads the shared catalog for future role editors. A new module grants no one access automatically.
5. Assigned users receive it through `modules` at login/refresh/me. Render its sidebar label and call GET `/api/v1/modules/{key}/content` when opening it.

Content response: `{key,name,status:"WORK_IN_PROGRESS",message:"Work in Progress"}`. Missing module grant â†’ 403, even with a known key. This registers a navigation/permission placeholder, not executable code, routes or business APIs. Built-in content uses its actual APIs, not this placeholder endpoint. Module rename/delete is outside the current dummy and has no API.

## Form dependencies without extra screen access

GET `/api/v1/ui/lookups` is available to process, storage, styles, newstyle or users module holders. It returns only applicable dependency keys:

- `processes`: `{id,name,station_id,stationNumber}` active process choices for process/style/user forms. Custom roles receive assigned processes only. Style Configurator receives all active process choices for creating a route, without permission to browse Process Configurations.
- `tags`: `{id,name,uid,status,assigned}` ACTIVE registered tags for process/storage forms: unassigned tags plus tags already assigned within the caller's process/station scope. Filter out assigned tags except the edited entity's current selections.

Use this instead of calling Process or Tag Management collection APIs when those modules are absent. A storage-only user can select tags without acquiring Tag Management. A style-only user can select processes without acquiring Process Configurations. A users-only user uses lookups and role options without acquiring either management screen. A role with no modules can still log in, change password, refresh, log out and read `/auth/me`; its sidebar is empty/restricted.

## Style Configurator security and save

Create via POST `/api/v1/styles` with the entire route:

```json
{
  "styleNumber":"STY-2026-001",
  "description":"Denim wash",
  "processIds":["11111111-1111-4111-8111-111111111111"]
}
```

`processIds` is required for create-only users. Metadata and validated route commit in one transaction; invalid/repeated-station routes leave no partial style. Other style-management users can also use this atomic create, or retain the legacy metadata-then-route flow. Style responses now expose `created_by` (creator user UUID).

Style Configurator can POST `/styles/{id}/release` for its own styles. It can GET `/styles`, `/styles/{id}`, `/styles/{id}/derived-route` for its **own** styles. The collection is filtered server-side; guessing another style's UUID is forbidden. It cannot update routes, revise, delete or access style progress/revisions through that role. Migration V7 adds the independent `lots` module, allowing it to create, view, edit quantity, cancel and map lots only for styles it created, including lots added to those styles by other users. GET/POST `/styles/{id}/lots`, direct lot access and style lookup choices enforce this ownership. See [Lot Management](LOT_MANAGEMENT.md) for generated numbers, release-time creation and trolley mapping. Create-only custom roles granted newstyle without styles retain the own-style restriction; adding `lots` independently grants lot management. Role changes and module grants cannot silently convert built-in Style Configurator into a general style editor. See [the collection/account change guide](API_COLLECTIONS_AND_ACCOUNT_EXPIRY.md) for grants, pagination and initial-password expiry.

This authorization change does not add missing style client/count/weight/delivery fields or a general style edit/delete endpoint. Those remain separately identified in the UI field map.

## Deploy and verify

Deploy the rebuilt API through the normal Windows service lifecycle (`deployment\windows\install.cmd start`); application startup applies V6. No database reset is needed. Re-login after migration, particularly for former Station 0 users, and re-import the updated Postman collection/environment. Review role changes: desktop defaults now match v21 rather than preserving legacy module guesses.

Postman has 142 normal requests in 19 folders, with new requests in **19 v21 Roles modules and form lookups**. Set `roleCode` and `moduleKey` manually from create responses. No scripts are introduced. Regression tests exercise real PostgreSQL migrations, independent module access, dynamic roles, current-token revocation of grants, scoped reads/writes and own-style enforcement.
