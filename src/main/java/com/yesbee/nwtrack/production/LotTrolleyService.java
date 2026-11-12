package com.yesbee.nwtrack.production;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import com.yesbee.nwtrack.workflow.OperationsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class LotTrolleyService {
    private final JdbcTemplate jdbc;
    private final OperationsService operations;
    public LotTrolleyService(JdbcTemplate jdbc,OperationsService operations){this.jdbc=jdbc;this.operations=operations;}
    private Map<String,Object> editable(UUID lot){
        var row=jdbc.queryForMap("select status,current_location_type,current_location_id from lots where id=? for update",lot);
        if(!Set.of("READY","AT_STATION","READY_FOR_DISPATCH").contains(row.get("status")))
            throw new BusinessRuleException("TROLLEY_MAPPING_NOT_ALLOWED","Trolley mapping requires a ready Lot or a Lot waiting at a Station");
        return row;
    }
    @Transactional public void assign(UUID lot,UUID trolley,UUID actor){
        var before=editable(lot);
        operations.linkTrolley(lot,trolley,actor);
        jdbc.update("update lot_trolley_assignments set previous_location_type=?,previous_location_id=? where lot_id=? and released_at is null",before.get("current_location_type"),before.get("current_location_id"),lot);
    }
    @Transactional public void unassign(UUID lot,UUID actor){
        var before=editable(lot);
        var rows=jdbc.queryForList("select id,trolley_id,previous_location_type,previous_location_id from lot_trolley_assignments where lot_id=? and released_at is null for update",lot);
        if(rows.isEmpty())return;
        var assignment=rows.getFirst();UUID trolley=(UUID)assignment.get("trolley_id");
        jdbc.queryForObject("select id from trolleys where id=? for update",UUID.class,trolley);
        Object type=assignment.get("previous_location_type"),location=assignment.get("previous_location_id");
        if(type==null&&!"READY".equals(before.get("status"))){
            location=jdbc.queryForObject("select lr.station_id from lot_route_entries lr join lots l on l.id=lr.lot_id where l.id=? and lr.sequence_no=l.current_route_sequence",UUID.class,lot);
            type="PROCESS_AREA";
        }
        jdbc.update("update lot_trolley_assignments set released_at=now(),released_by=? where id=?",actor,assignment.get("id"));
        jdbc.update("update trolleys set status=case when active then 'AVAILABLE' else 'INACTIVE' end,version=version+1 where id=?",trolley);
        jdbc.update("update lots set current_location_type=?,current_location_id=?,version=version+1 where id=?",type,location,lot);
        jdbc.update("insert into lot_location_events(lot_id,location_type,location_id,actor_id,detail) values (?,?,?,?,jsonb_build_object('action','TROLLEY_UNASSIGNED','trolleyId',?::text))",lot,type==null?"UNASSIGNED":type,location,actor,trolley.toString());
    }
}
