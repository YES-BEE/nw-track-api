# Reports and Analysis API

NW Track exposes eight database-backed report categories. Report files are generated from the production records visible to the authenticated user and streamed to the client rather than assembled fully in memory.

## Catalogue

```http
GET /api/v1/reports/catalog
Authorization: Bearer <token>
```

The response is ordered by `report_categories.display_order` and includes each active category's database `id`, internal key, display name, description, formats, filters, and required parameters. The portal must use `id` when requesting an export.

| Report key | Formats |
|---|---|
| `style-lot-production` | XLSX, CSV, PDF |
| `station-processing-time` | XLSX, CSV, PDF |
| `delay-bottleneck` | XLSX, CSV, PDF |
| `lot-movement-traceability` | XLSX, CSV, PDF |
| `storage-waiting-time` | XLSX, CSV, PDF |
| `trolley-utilization` | XLSX, CSV, PDF |
| `operator-activity` | XLSX, CSV, PDF |
| `daily-production-summary` | XLSX, PDF |

The database seeds all eight categories. `display_name`, `description`, `display_order`, and `active` are configurable data. `report_key` is a stable implementation identifier and must not be changed.

## Export

```http
GET /api/v1/reports/{reportId}/export?format=xlsx&from=2026-10-01&to=2026-10-31
Authorization: Bearer <token>
```

`reportId` is the UUID returned by the catalogue. `from`, `to`, and `format` are required. Dates are inclusive calendar dates in `nwtrack.factory-timezone`. There is no application-level date-range or row-count limit.

Optional filters, where listed by the catalogue:

- `styleId`
- `lotId`
- `stationId`
- `processId`
- `operatorId`
- `status`

Supplying a filter not supported by the selected report returns `400`. Delay status is `ON_TIME` or `DELAYED`; storage status is `WAITING` or `RETRIEVED`; trolley status is `AVAILABLE`, `IN_USE`, or `INACTIVE`.

Example response headers:

```http
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="nw-track_style-lot-production_2026-10-01_to_2026-10-31.xlsx"
X-Report-Export-ID: 20eac61e-33cf-4cfb-a44a-53ff1cf186d5
Cache-Control: no-store
```

## Streaming behavior

- CSV rows are fetched and written incrementally as UTF-8 with RFC-style quoting.
- XLSX uses Apache POI's streaming workbook with a bounded in-memory row window and temporary worksheet files.
- PDF writes tables in bounded row batches and allows the PDF writer to paginate them.
- Detail queries use forward-only JDBC result sets with fetch sizing.
- XLSX temporary files and all writer resources are closed after success or failure.

No cap means generation time, XLSX temporary disk use, network transfer, and final file size scale with the selected history. If streaming fails after response headers or bytes have been sent, the connection ends with an incomplete file; the server does not return a success-shaped fallback.

## Calculation rules

- Date filters select each report's primary event date: lot creation, process start, dispatch, storage entry, or trolley link.
- Elapsed duration is wall-clock time from first start/entry/link until completion/exit/delink. Active records use the report transaction snapshot time.
- Paused and blocked time remains part of elapsed duration.
- Lot progress is completed or skipped required processes divided by all required processes. Waiting alone does not increase progress.
- Superseded process placeholders from route revisions are excluded.
- Delay uses `processes.target_duration_seconds`; executions without a configured target are excluded.
- Daily boundaries and date buckets use the configured factory timezone.

## Database records

`report_categories` stores the seeded catalogue configuration. Its UUID `id` is the public report identifier; `report_key` is the stable internal implementation mapping.

`report_export_history` stores audit metadata for every accepted export:

- report category ID and format;
- requester;
- inclusive date range and selected filters;
- request and completion timestamps;
- `REQUESTED`, `COMPLETED`, or `FAILED` outcome;
- a bounded failure message when generation fails.

Generated file contents are not stored in PostgreSQL.

## Access control

The existing Reports/Analysis module and `REPORTS` permission protect these endpoints. Every detail and aggregate query applies the existing lot, station, and process visibility predicates. Export history does not grant access to report contents.
