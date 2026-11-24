# Incoming lot integration changes

`INCOMING_LOT` notifications are delivered to the station topic `/topic/stations/{stationId}` and assigned users' `/user/queue/events`, and stored as notifications. The payload is a snapshot taken when dispatch succeeds. Existing fields remain: `lotId`, `lotNumber`, `styleNumber`, `destinationStationId`, `destinationStation` (station code), and `visitId`. Added fields:

| Field | Meaning |
| --- | --- |
| `lotStatus` | `DISPATCHED` at dispatch time |
| `quantity` | Current lot quantity; may be null |
| `styleId` | Style UUID |
| `trolleyId`, `trolleyCode` | Active trolley UUID and code |
| `routeSequence` | Destination sequence in the lot route |
| `destinationStationName` | Destination station name |

`POST /api/v1/lots/{id}/dispatch` returns the same expanded payload. `GET /api/v1/stations/{id}/incoming` adds `lot_status`, `quantity`, `style_id`, and `trolley_id` to each existing result. Its field names follow the existing SQL-style response. Status can change after a notification is sent; the notification is not a live status subscription. Previously queued notifications retain their original payload.
