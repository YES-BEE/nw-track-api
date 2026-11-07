package com.yesbee.nwtrack.production;

import com.yesbee.nwtrack.routing.RouteDerivationService;
import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ProductionService {
    private final JdbcTemplate jdbc; private final RouteDerivationService routes;
    public ProductionService(JdbcTemplate jdbc,RouteDerivationService routes){this.jdbc=jdbc;this.routes=routes;}

    @Transactional public UUID createStyle(String number,String description,UUID actor){UUID id=UUID.randomUUID();jdbc.update("insert into styles(id,style_number,description,status,created_by) values (?,?,?,'DRAFT',?)",id,number.trim(),description,actor);return id;}
    @Transactional public UUID createConfiguredStyle(String number,String description,UUID actor,List<UUID> processes){
        UUID id=createStyle(number,description,actor);
        if(processes!=null&&!processes.isEmpty()){setProcesses(id,processes);derive(id);}
        return id;
    }
    @Transactional public void setProcesses(UUID styleId,List<UUID> processIds){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if(!"DRAFT".equals(status))throw new BusinessRuleException("STYLE_IMMUTABLE","Processes cannot change after Style release");jdbc.update("delete from style_processes where style_id=?",styleId);int sequence=1;for(UUID processId:processIds)jdbc.update("insert into style_processes(id,style_id,process_id,sequence_no) values (?,?,?,?)",UUID.randomUUID(),styleId,processId,sequence++);}
    public List<RouteDerivationService.RouteEntry> derive(UUID styleId){return routes.derive(selected(styleId));}

    @Transactional public void release(UUID styleId){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if(!"DRAFT".equals(status))throw new BusinessRuleException("STYLE_ALREADY_RELEASED","Style is not in DRAFT state");List<RouteDerivationService.RouteEntry> route=derive(styleId);jdbc.update("delete from style_route_entries where style_id=?",styleId);for(var entry:route){UUID routeId=UUID.randomUUID();jdbc.update("insert into style_route_entries(id,style_id,station_id,sequence_no) values (?,?,?,?)",routeId,styleId,entry.stationId(),entry.sequence());int local=1;for(var p:entry.processes()){UUID styleProcessId=jdbc.queryForObject("select id from style_processes where style_id=? and process_id=?",UUID.class,styleId,p.processId());jdbc.update("insert into style_route_processes(route_entry_id,style_process_id,sequence_no) values (?,?,?)",routeId,styleProcessId,local++);}}jdbc.update("update styles set status='RELEASED',released_at=now(),version=version+1 where id=?",styleId);}

    @Transactional public UUID createLot(UUID styleId,String lotNumber,int quantity,UUID actor){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if("DRAFT".equals(status))throw new BusinessRuleException("STYLE_NOT_RELEASED","Release the Style before creating production Lots");UUID lotId=UUID.randomUUID();jdbc.update("insert into lots(id,style_id,lot_number,quantity,status,current_route_sequence,created_by) values (?,?,?,?,'READY',1,?)",lotId,styleId,lotNumber.trim(),quantity,actor);List<RouteRow> route=jdbc.query("select sre.id,sre.station_id,sre.sequence_no from style_route_entries sre where sre.style_id=? order by sre.sequence_no",(rs,n)->new RouteRow((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getInt(3)),styleId);for(RouteRow r:route){UUID lotRouteId=UUID.randomUUID(),visitId=UUID.randomUUID();jdbc.update("insert into lot_route_entries(id,lot_id,station_id,sequence_no,status) values (?,?,?,?,'EXPECTED')",lotRouteId,lotId,r.stationId(),r.sequence());jdbc.update("insert into station_visits(id,lot_id,lot_route_entry_id,station_id,status) values (?,?,?,?,'EXPECTED')",visitId,lotId,lotRouteId,r.stationId());List<ProcessRow> processes=jdbc.query("select sp.process_id,srp.sequence_no from style_route_processes srp join style_processes sp on sp.id=srp.style_process_id where srp.route_entry_id=? order by srp.sequence_no",(rs,n)->new ProcessRow((UUID)rs.getObject(1),rs.getInt(2)),r.styleRouteId());for(ProcessRow p:processes)jdbc.update("insert into process_executions(id,station_visit_id,process_id,sequence_no,status) values (?,?,?,?,'PENDING')",UUID.randomUUID(),visitId,p.processId(),p.sequence());}jdbc.update("update styles set status='IN_PRODUCTION',version=version+1 where id=?",styleId);return lotId;}

    @Transactional public void cancelLot(UUID lotId,String reason,UUID actor){String status=jdbc.queryForObject("select status from lots where id=? for update",String.class,lotId);if("COMPLETED".equals(status))throw new BusinessRuleException("COMPLETED_LOT_IMMUTABLE","A completed Lot cannot be cancelled");jdbc.update("update lot_trolley_assignments set released_at=now(),released_by=? where lot_id=? and released_at is null",actor,lotId);jdbc.update("update storage_occupancies set retrieved_at=now(),retrieved_by=? where lot_id=? and retrieved_at is null",actor,lotId);jdbc.update("update lots set status='CANCELLED',current_location_type='CANCELLED',current_location_id=null,cancelled_at=now(),cancellation_reason=?,version=version+1 where id=?",reason,lotId);jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason) values (?,'CANCEL','LOT',?,'SUCCESS',?)",actor,lotId,reason);}

    private List<RouteDerivationService.SelectedProcess> selected(UUID styleId){return jdbc.query("select p.id,p.code,sp.sequence_no,p.station_id,s.code from style_processes sp join processes p on p.id=sp.process_id join stations s on s.id=p.station_id where sp.style_id=? and p.active and s.active order by sp.sequence_no",(rs,n)->new RouteDerivationService.SelectedProcess((UUID)rs.getObject(1),rs.getString(2),rs.getInt(3),(UUID)rs.getObject(4),rs.getString(5)),styleId);}
    private record RouteRow(UUID styleRouteId,UUID stationId,int sequence){} private record ProcessRow(UUID processId,int sequence){}
}
