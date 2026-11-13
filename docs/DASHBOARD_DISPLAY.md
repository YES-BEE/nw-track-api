# Shared factory dashboard and display API

Every factory display uses the same screen and data contract, independent of its
physical location. Use a factory-wide read account such as TOP_MANAGER with a PC
login and Dashboard access on all displays. Existing account visibility rules still
apply; this does not grant restricted users access to other users' data. The backend
does not select a different dashboard based on display location.

## Requests

| Screen | Summary | Production table | View action |
| --- | --- | --- | --- |
| Dashboard | GET `/api/v1/dashboard` | GET `/api/v1/dashboard/lots` | GET `/api/v1/dashboard/lots/{id}` |
| Factory displays | GET `/api/v1/displays/factory-summary` | GET `/api/v1/displays/lots` | GET `/api/v1/displays/lots/{id}` |

The two summary endpoints return the same structure. Dashboard and display lot
endpoints share the same read model and visibility checks, including direct UUID
lookups. Dashboard readers can use the view action without Lot Management access.
The existing station-specific display endpoint remains available for other clients;
it is not the endpoint for this shared factory screen.

## Screenshot coverage

| Screen value | Response field |
| --- | --- |
| Updated date/time | Summary `snapshotAt`; format in the factory's timezone |
| Total Lots | `metrics.totalLots` |
| Open Lots | `metrics.openLots` (all except COMPLETED and CANCELLED) |
| Completed Lots | `metrics.completedLots` |
| Styles with Lots | `metrics.stylesWithLots` |
| Stored Lots | `metrics.storedLots` |
| Available Trolleys | `metrics.availableTrolleys` |
| Station Work in Progress tiles | `stations.items`: `id`, `code`, `name`, `wip` |
| Station Status table / Share | `stations.items[].share_percent` |
| Completed share | `metrics.completedSharePercent` |
| In transit | `metrics.inTransitLots` (DISPATCHED) |
| Ready to start | `metrics.readyToStartLots` (READY) |
| Highest station workload | `metrics.highestStationWorkloadCount`; `metrics.highestStationWorkload` has `id`, `code`, `name`, `wip`, or is null when no work is in progress |
| Lots by Status | `lots`: complete `{status,count}` array |
| Styles by Status | `styles`: complete `{status,count}` array, counting styles with visible lots |
| Total station lot visits in progress | `metrics.totalStationLotVisits` |

WIP counts station visits in DISPATCHED, RECEIVED, ACTIVE or READY_FOR_DISPATCH,
excluding completed/cancelled lots. It is a visit count, not garment quantity.
The percentage denominator includes all visible station WIP, even when the station
page or search shows only a subset. Summary metrics and status counts also use the
complete visible dataset and do not change with page size or station search.
Zero denominators return 0; percentages are rounded to two decimal places.
Ties for highest workload select the first station by code, then UUID.

Legacy `storageOccupancies` and `availableTrolleys` fields remain in the summary.

## Production table

| Column | Lot response field |
| --- | --- |
| Lot Number | `lot_number` |
| Style | `style_number` (`style_id` is also available) |
| Quantity | `quantity` (may be null) |
| Status | `status` |
| Current Station | `current_station` (station code) |
| Current Process | `current_process`, with `current_process_id` and `current_process_status` |
| Current Route Step | `current_route_sequence` |
| Location | `location_label`; raw `current_location_type` and `current_location_id` are preserved |
| Created / Completed At | `created_at`, `completed_at` |
| Progress bar | `progress_percent` (0–100); `completed_processes` and `total_processes` explain the calculation |
| Actions | Use `id` in the read-only View endpoint |

Current process is selected from the current route step: active first, then paused,
blocked, or the next pending process. Finished/skipped executions are excluded.
The status lets the UI distinguish work waiting to start from work actively running.
Location labels resolve trolley codes, station names/codes and storage-unit names/codes.
A never-placed lot may have a null label; show a dash in the UI. Completed and cancelled
locations have readable labels when their stored location type is terminal.

Table requests support `search`, `status`, zero-based `page`, and `size` (1–100).
Responses contain `items`, `page`, `size`, and `totalElements`. Search and status filter
the table; cards and status charts retain their full visible totals. Status chart clicks
can set/clear the table's `status` parameter. Fetch subsequent station pages if there
are more tiles/table rows than the requested station page size.

## Refreshing all display panels

Each summary returns `snapshotAt` and `refreshIntervalSeconds` (currently 5). Poll
summary and the current production-table page on that interval, with the same account,
filters and page settings across displays. This catches every committed change,
including creation, quantity edits, placement, process changes, cancellation and QC.
The summary's queries run in a consistent database snapshot. Separate table requests
can reflect slightly newer data; the next refresh reconciles them.

Existing WebSocket station notifications can prompt an earlier refresh, but are not
a complete dashboard change feed. Keep polling as the reliable update mechanism.
Only show a healthy Live Updates indicator while refresh requests are succeeding;
on failure retain the last successful timestamp and indicate stale/offline data.

These are backend contracts and integration guidance; the display UI must wire these
fields and polling requests into the agreed screen.

## Lot and style progress reports

The ID-specific reports have been replaced with:

- GET `/api/v1/reports/style-progress`: paginated styles, including styles with no lots,
  with `lot_count`, `completed_lots`, and `progress_percent`.
- GET `/api/v1/reports/lot-progress`: paginated lots with the same fields as dashboard
  lot rows, including progress, style, current station/process, and location.

Neither request requires a style ID or lot ID. Both accept `page`, `size` (1–100),
and optional `search`; fetch subsequent pages to populate client-side filters.
Responses use `items`, `page`, `size`, and `totalElements`. Reports require Reports
access and preserve account ownership and station restrictions. UI clients must
replace `/styles/{id}/progress` and `/reports/lot-history?lotId=...` with the new
collection endpoints. Lot event history remains available through `/lots/{id}/timeline`
with Lot Management access.

Each effective process has equal weight. `completed_processes` counts completed and
intentionally skipped steps; steps superseded by a manager route revision are omitted
from both counts. `progress_percent` is that count divided by `total_processes`, times
100, rounded to two decimals. Unstarted/no-process lots return 0; completed lots return
100. Cancelled lots retain the percentage reached and their CANCELLED status.
Style progress is the arithmetic mean of its visible lots' percentages, including
cancelled lots; styles without lots return 0. Garment quantities do not weight progress.

## Production table filters and dropdown APIs

The following table requests accept the same optional filters, combined with AND:

- GET `/api/v1/dashboard/lots` (Dashboard access)
- GET `/api/v1/displays/lots` (Dashboard access)
- GET `/api/v1/reports/lot-progress` (Reports access)

| Parameter | Meaning |
| --- | --- |
| `lotId` | UUID selected from the lot-number dropdown |
| `styleId` | Style UUID selected from the style dropdown |
| `stationId` | Station UUID |
| `processId` | Process UUID (primarily for Reports) |
| `routeScope` | `CURRENT` (default) or `ANY` |
| `status` | Exact backend lot status, e.g. `READY` |
| `search` | Existing case-insensitive text search across displayed fields |
| `page`, `size` | Zero-based page; size 1–100, default 20 |

`CURRENT` matches `current_station_id` and `current_process_id`; completed lots may
have no current process. `ANY` matches any recorded station visit/process on the
lot's route, including previous and future steps. Superseded process executions are
excluded. If both station and process are selected in ANY mode, the process must
belong to a visit at that station. UUID filters match exactly; use option search to
find a lot by its number, a style by its number, or a station/process by its label.
Omit a filter to select all values; ANY changes route matching, not authorization.

For example:

```http
GET /api/v1/dashboard/lots?styleId=<UUID>&stationId=<UUID>&routeScope=ANY&page=0&size=10
GET /api/v1/reports/lot-progress?lotId=<UUID>&processId=<UUID>&routeScope=CURRENT
```

Populate searchable dropdowns using these dedicated read endpoints. They require
only the corresponding module, without granting master-data or Lot Management access:

| Dropdown | Dashboard request | Reports request |
| --- | --- | --- |
| Lot number | `/api/v1/dashboard/filter-options/lots` | `/api/v1/reports/filter-options/lots` |
| Style | `/api/v1/dashboard/filter-options/styles` | `/api/v1/reports/filter-options/styles` |
| Station | `/api/v1/dashboard/filter-options/stations` | `/api/v1/reports/filter-options/stations` |
| Process | `/api/v1/dashboard/filter-options/processes` | `/api/v1/reports/filter-options/processes` |

Each GET returns `items`, `page`, `size`, and `totalElements`, and accepts the same
filters, `routeScope`, `search`, `page`, and `size`. Option search applies to option
fields, not hidden master-data fields. Options are derived only from visible lots;
unrelated styles/stations/processes are not exposed. Lot options have `id`,
`lot_number`, `style_id`, `style_number`; style options have `id`, `style_number`;
station options have `id`, `code`; process options have `id`, `name`. ANY options
also include station names and process codes. Use `id` as the selected value.

Pass selected filters when loading dependent options. For example, selecting a style
narrows lot options; in ANY mode selecting a station narrows process options to that
station. Clear a dropdown's own selected parameter to load its other choices.
Dashboard and Reports grants stay independent, and ownership/station restrictions
apply to both options and table results.

Only production-table results use these selected filters. Summary cards, station
workload panels and the production trend retain the full account-visible totals.

## Today's production trend

Both `/dashboard` and `/displays/factory-summary` include `productionTrend`:

```json
{
  "date": "2026-11-13",
  "timezone": "Asia/Kolkata",
  "interval": "HOUR",
  "points": [
    {"bucket_start": "2026-11-13T08:00:00+05:30",
     "bucket_end": "2026-11-13T09:00:00+05:30",
     "completed": 8, "in_progress": 24}
  ]
}
```

The example illustrates one point; the response includes every elapsed hour from
local midnight through the current hour, including zero counts. Future hours are
omitted. `completed` counts lots completed within that hour (not cumulative).
`in_progress` counts lots first dispatched by the bucket end and not completed or
cancelled by then, including work carried over from previous days and stored lots.
READY lots awaiting their first dispatch are excluded. Historical WIP uses each
lot's earliest recorded dispatch and terminal timestamps, rather than its current
status. Legacy lots missing a dispatch timestamp cannot contribute to historical WIP.
The current bucket ends at `snapshotAt`; other buckets end at the next hour.
Bucket timestamps are instants; format them in the supplied factory timezone.

Set `FACTORY_TIMEZONE` to the factory's IANA timezone (default `Asia/Kolkata`). Counts
respect lot visibility and are independent of station pagination/search. Plot
`completed` and `in_progress` as the two lines shown in the dashboard image.

## Cancellation and trolley availability

Cancelling a lot releases its trolley assignment and sets an active trolley to
AVAILABLE in the same transaction. An inactive trolley remains INACTIVE. Repeated
cancellation cannot release a later lot's assignment. The upgrade also repairs old
IN_USE trolley statuses when there is no active assignment.
