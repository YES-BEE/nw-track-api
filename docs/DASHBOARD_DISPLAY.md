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
