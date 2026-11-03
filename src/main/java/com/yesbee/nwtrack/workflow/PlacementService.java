package com.yesbee.nwtrack.workflow;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class PlacementService {
    private final JdbcTemplate jdbc;private final StationAccessService access;
    public PlacementService(JdbcTemplate jdbc,StationAccessService access) { this.jdbc=jdbc;this.access=access; }

    @Transactional
    public PlacementResult receiveAndPlace(UUID visitId, UUID operatorId, PlacementCommand command) {
        VisitRow visit = jdbc.queryForObject("select sv.id,sv.lot_id,sv.station_id,lta.id assignment_id,lta.trolley_id from station_visits sv join lot_trolley_assignments lta on lta.lot_id=sv.lot_id and lta.released_at is null where sv.id=? and sv.status='DISPATCHED' for update",
                (rs,n)->new VisitRow((UUID)rs.getObject("id"),(UUID)rs.getObject("lot_id"),(UUID)rs.getObject("station_id"),(UUID)rs.getObject("assignment_id"),(UUID)rs.getObject("trolley_id")), visitId);
        if (visit == null) throw new BusinessRuleException("NO_ACTIVE_LOT", "No active trolley assignment exists for this Station Visit");
        access.assertCanOperate(operatorId,visit.stationId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (command.destinationType() == DestinationType.STORAGE) {
            if (command.storageLocationId() == null) throw new BusinessRuleException("STORAGE_REQUIRED", "Storage Location is required");
            Integer validStorage=jdbc.queryForObject("select count(*) from storage_locations where id=? and station_id=? and active",Integer.class,command.storageLocationId(),visit.stationId());
            if(validStorage==null||validStorage==0)throw new BusinessRuleException("STORAGE_STATION_MISMATCH","Storage Location does not belong to the receiving Station");
            UUID occupancyId = UUID.randomUUID();
            jdbc.update("insert into storage_occupancies(id,lot_id,station_id,storage_location_id,stored_by,stored_at) values (?,?,?,?,?,?)",
                    occupancyId, visit.lotId(), visit.stationId(), command.storageLocationId(), operatorId, now);
            jdbc.update("update lots set status='IN_STORAGE',current_location_type='STORAGE',current_location_id=?,version=version+1 where id=?", command.storageLocationId(), visit.lotId());
        } else {
            if (command.processExecutionId() == null) throw new BusinessRuleException("PROCESS_REQUIRED", "Process execution is required");
            Integer validProcess=jdbc.queryForObject("select count(*) from process_executions where id=? and station_visit_id=?",Integer.class,command.processExecutionId(),visitId);
            if(validProcess==null||validProcess==0)throw new BusinessRuleException("PROCESS_VISIT_MISMATCH","Process execution does not belong to this Station Visit");
            access.assertCanExecute(operatorId,command.processExecutionId());
            jdbc.update("update lots set status='AT_STATION',current_location_type='PROCESS_AREA',current_location_id=?,version=version+1 where id=?", visit.stationId(), visit.lotId());
        }
        jdbc.update("update lot_trolley_assignments set released_at=?,released_by=? where id=?", now, operatorId, visit.assignmentId());
        jdbc.update("update trolleys set status='AVAILABLE',version=version+1 where id=?",visit.trolleyId());
        jdbc.update("update station_visits set status='RECEIVED',received_at=?,received_by=?,version=version+1 where id=?", now, operatorId, visitId);
        jdbc.update("update lot_route_entries set status='RECEIVED' where id=(select lot_route_entry_id from station_visits where id=?)",visitId);
        return new PlacementResult(visit.lotId(), command.destinationType(), now);
    }
    public enum DestinationType { PROCESS_AREA, STORAGE }
    public record PlacementCommand(@NotNull DestinationType destinationType, UUID processExecutionId, UUID storageLocationId) {}
    public record PlacementResult(UUID lotId, DestinationType destinationType, OffsetDateTime placedAt) {}
    private record VisitRow(UUID id, UUID lotId, UUID stationId, UUID assignmentId,UUID trolleyId) {}
}
