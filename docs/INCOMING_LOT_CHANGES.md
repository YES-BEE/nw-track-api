# Incoming lot integration changes

`INCOMING_LOT` notifications are delivered to the station topic `/topic/stations/{stationId}` and assigned users' `/user/queue/events`, and stored as notifications. The notification time is retained; destination and visit fields show the lot's current route step. Existing fields remain: `lotId`, `lotNumber`, `styleNumber`, `destinationStationId`, `destinationStation` (station code), and `visitId`. Added fields:

| Field | Meaning |
| --- | --- |
| `lotStatus` | `DISPATCHED` at dispatch time |
| `quantity` | Current lot quantity; may be null |
| `styleId` | Style UUID |
| `trolleyId`, `trolleyCode` | Active trolley UUID and code |
| `routeSequence` | Destination sequence in the lot route |
| `destinationStationName` | Destination station name |
| `currentLocationType`, `currentLocationId` | Lot's location at dispatch time |
| `createdAt`, `completedAt`, `version` | Lot creation/completion timestamps and current version |
| `progressPercent` | Percentage of route steps completed, rounded to two decimal places |
| `completedRouteSteps`, `totalRouteSteps` | Completed route steps and route steps with non-superseded process work |
| `currentProcessExecutionId`, `currentProcessId`, `currentProcessName`, `currentProcessCode`, `currentProcessStatus` | The current unfinished process at the current route step |
| `currentStepId`, `currentStepSequence`, `currentStepStatus`, `currentStepStationId`, `currentStepStationCode`, `currentStepStationName` | The current route step and its station |
| `nextProcessExecutionId`, `nextProcessId`, `nextProcessName`, `nextProcessCode`, `nextProcessStatus` | The first unfinished process at the next route step, when one exists |
| `nextStepId`, `nextStepSequence`, `nextStepStatus`, `nextStepStationId`, `nextStepStationCode`, `nextStepStationName` | The next route step and its station; fields are null when there is no next step |

`POST /api/v1/lots/{id}/dispatch` returns the same expanded payload. The notification and dispatch response also include the lot detail fields `currentLocationType`, `currentLocationId`, `createdAt`, `completedAt`, and `version`; `routeSequence` represents `current_route_sequence`. `GET /api/v1/stations/{id}/incoming` includes the corresponding lot fields plus progress, process, and step fields, using the existing SQL-style field names (for example, `current_route_sequence`, `progress_percent`, and `current_step_sequence`). It also includes the destination station ID, code, and name. `GET /api/v1/notifications` overlays current lot progress and destination on stored notifications. After changes, connected recipients and all previously notified station topics receive `LOT_UPDATED` payloads; clients should replace the displayed live fields for the corresponding lot.

Progress is calculated as `completedRouteSteps / totalRouteSteps * 100`, rounded to two decimal places. A route step is complete when all of its non-superseded process executions are completed or skipped. Steps containing only processes superseded by a manager route revision are excluded from the total. The current and next process fields describe unfinished processes; they are null when no unfinished process exists at the corresponding step.
