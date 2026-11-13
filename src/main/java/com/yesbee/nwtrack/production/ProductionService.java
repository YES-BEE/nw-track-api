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
    @Transactional public UUID createConfiguredStyle(String number,String description,UUID actor,List<UUID> processes,Integer lotCount,Integer quantity){
        if(lotCount!=null&&(lotCount<0||lotCount>1000))throw new IllegalArgumentException("lotCount must be between 0 and 1000");
        if(quantity!=null&&quantity<=0)throw new IllegalArgumentException("quantity must be positive when supplied");
        UUID id=createConfiguredStyle(number,description,actor,processes);
        jdbc.update("update styles set lot_count=?,lot_quantity=? where id=?",lotCount==null?0:lotCount,quantity,id);
        return id;
    }
    @Transactional public void setProcesses(UUID styleId,List<UUID> processIds){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if(!"DRAFT".equals(status))throw new BusinessRuleException("STYLE_IMMUTABLE","Processes cannot change after Style release");jdbc.update("delete from style_processes where style_id=?",styleId);int sequence=1;for(UUID processId:processIds)jdbc.update("insert into style_processes(id,style_id,process_id,sequence_no) values (?,?,?,?)",UUID.randomUUID(),styleId,processId,sequence++);}
    public List<RouteDerivationService.RouteEntry> derive(UUID styleId){return routes.derive(selected(styleId));}

    @Transactional public void release(UUID styleId){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if(!"DRAFT".equals(status))throw new BusinessRuleException("STYLE_ALREADY_RELEASED","Style is not in DRAFT state");List<RouteDerivationService.RouteEntry> route=derive(styleId);jdbc.update("delete from style_route_entries where style_id=?",styleId);for(var entry:route){UUID routeId=UUID.randomUUID();jdbc.update("insert into style_route_entries(id,style_id,station_id,sequence_no) values (?,?,?,?)",routeId,styleId,entry.stationId(),entry.sequence());int local=1;for(var p:entry.processes()){UUID styleProcessId=jdbc.queryForObject("select id from style_processes where style_id=? and process_id=?",UUID.class,styleId,p.processId());jdbc.update("insert into style_route_processes(route_entry_id,style_process_id,sequence_no) values (?,?,?)",routeId,styleProcessId,local++);}}jdbc.update("update styles set status='RELEASED',released_at=now(),version=version+1 where id=?",styleId);
        var plan=jdbc.queryForMap("select lot_count,lot_quantity,created_by from styles where id=?",styleId);
        int count=(Integer)plan.get("lot_count");
        if(count>0)createLots(styleId,count,(Integer)plan.get("lot_quantity"),(UUID)plan.get("created_by"));
    }

    @Transactional public UUID createLot(UUID styleId,Integer quantity,UUID actor){String status=jdbc.queryForObject("select status from styles where id=? for update",String.class,styleId);if("DRAFT".equals(status))throw new BusinessRuleException("STYLE_NOT_RELEASED","Release the Style before creating production Lots");if(quantity!=null&&quantity<=0)throw new IllegalArgumentException("quantity must be positive when supplied");LotNumber number=nextLotNumber(styleId);UUID lotId=UUID.randomUUID();jdbc.update("insert into lots(id,style_id,lot_number,quantity,status,current_route_sequence,created_by,lot_sequence) values (?,?,?,?,'READY',1,?,?)",lotId,styleId,number.value(),quantity,actor,number.sequence());List<RouteRow> route=jdbc.query("select sre.id,sre.station_id,sre.sequence_no from style_route_entries sre where sre.style_id=? order by sre.sequence_no",(rs,n)->new RouteRow((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getInt(3)),styleId);for(RouteRow r:route){UUID lotRouteId=UUID.randomUUID(),visitId=UUID.randomUUID();jdbc.update("insert into lot_route_entries(id,lot_id,station_id,sequence_no,status) values (?,?,?,?,'EXPECTED')",lotRouteId,lotId,r.stationId(),r.sequence());jdbc.update("insert into station_visits(id,lot_id,lot_route_entry_id,station_id,status) values (?,?,?,?,'EXPECTED')",visitId,lotId,lotRouteId,r.stationId());List<ProcessRow> processes=jdbc.query("select sp.process_id,srp.sequence_no from style_route_processes srp join style_processes sp on sp.id=srp.style_process_id where srp.route_entry_id=? order by srp.sequence_no",(rs,n)->new ProcessRow((UUID)rs.getObject(1),rs.getInt(2)),r.styleRouteId());for(ProcessRow p:processes)jdbc.update("insert into process_executions(id,station_visit_id,process_id,sequence_no,status) values (?,?,?,?,'PENDING')",UUID.randomUUID(),visitId,p.processId(),p.sequence());}jdbc.update("update styles set status='IN_PRODUCTION',version=version+1 where id=?",styleId);return lotId;}

    @Transactional public List<UUID> createLots(UUID styleId,int count,Integer quantity,UUID actor){
        if(count<1||count>1000)throw new IllegalArgumentException("lotCount must be between 1 and 1000");
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<count;i++)ids.add(createLot(styleId,quantity,actor));
        return ids;
    }
    @Transactional public void updateLotQuantity(UUID lotId,int quantity,String reason,UUID actor){
        if(quantity<=0)throw new IllegalArgumentException("quantity must be positive");
        if(reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("A change reason of at most 500 characters is required");
        var lot=jdbc.queryForMap("select quantity,status from lots where id=? for update",lotId);
        if(Set.of("DISPATCHED","COMPLETED","CANCELLED").contains(lot.get("status")))
            throw new BusinessRuleException("LOT_EDIT_NOT_ALLOWED","Lot quantity cannot change during transit or after completion/cancellation");
        Integer previous=(Integer)lot.get("quantity");
        if(Objects.equals(previous,quantity))return;
        jdbc.update("update lots set quantity=?,version=version+1 where id=?",quantity,lotId);
        jdbc.update("insert into lot_quantity_changes(lot_id,old_quantity,new_quantity,actor_id,reason) values (?,?,?,?,?)",lotId,previous,quantity,actor,reason.trim());
        jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason,detail) values (?,'UPDATE_QUANTITY','LOT',?,'SUCCESS',?,jsonb_build_object('oldQuantity',?::integer,'newQuantity',?::integer))",actor,lotId,reason.trim(),previous,quantity);
    }
    @Transactional public void cancelLot(UUID lotId,String reason,UUID actor){
        String status=jdbc.queryForObject("select status from lots where id=? for update",String.class,lotId);
        if("COMPLETED".equals(status))throw new BusinessRuleException("COMPLETED_LOT_IMMUTABLE","A completed Lot cannot be cancelled");
        if("CANCELLED".equals(status))return;
        var assignments=jdbc.queryForList("select id,trolley_id from lot_trolley_assignments where lot_id=? and released_at is null for update",lotId);
        for(var assignment:assignments){
            UUID trolley=(UUID)assignment.get("trolley_id");
            jdbc.queryForObject("select id from trolleys where id=? for update",UUID.class,trolley);
            jdbc.update("update lot_trolley_assignments set released_at=now(),released_by=? where id=?",actor,assignment.get("id"));
            jdbc.update("update trolleys set status=case when active then 'AVAILABLE' else 'INACTIVE' end,version=version+1 where id=?",trolley);
        }
        jdbc.update("update storage_occupancies set retrieved_at=now(),retrieved_by=? where lot_id=? and retrieved_at is null",actor,lotId);
        jdbc.update("update lots set status='CANCELLED',current_location_type='CANCELLED',current_location_id=null,cancelled_at=now(),cancellation_reason=?,version=version+1 where id=?",reason,lotId);
        jdbc.update("insert into lot_location_events(lot_id,location_type,actor_id,detail) values (?,'CANCELLED',?,jsonb_build_object('reason',?::text))",lotId,actor,reason);
        jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason) values (?,'CANCEL','LOT',?,'SUCCESS',?)",actor,lotId,reason);
    }

    // Called inside the creation/release transaction; style then clock is the
    // common lock order. Both reservations roll back if lot creation fails.
    private LotNumber nextLotNumber(UUID styleId){
        long sequence=jdbc.queryForObject("update styles set last_lot_sequence=last_lot_sequence+1 where id=? returning last_lot_sequence",Long.class,styleId);
        long epoch=jdbc.queryForObject("update lot_number_clock set last_epoch_ms=greatest(last_epoch_ms+1,floor(extract(epoch from clock_timestamp())*1000)::bigint) where singleton returning last_epoch_ms",Long.class);
        return new LotNumber(String.format(Locale.ROOT,"LOT-%d-%03d",epoch,sequence),sequence);
    }
    private record LotNumber(String value,long sequence){}
    private List<RouteDerivationService.SelectedProcess> selected(UUID styleId){return jdbc.query("select p.id,p.code,sp.sequence_no,p.station_id,s.code from style_processes sp join processes p on p.id=sp.process_id join stations s on s.id=p.station_id where sp.style_id=? and p.active and s.active order by sp.sequence_no",(rs,n)->new RouteDerivationService.SelectedProcess((UUID)rs.getObject(1),rs.getString(2),rs.getInt(3),(UUID)rs.getObject(4),rs.getString(5)),styleId);}
    private record RouteRow(UUID styleRouteId,UUID stationId,int sequence){} private record ProcessRow(UUID processId,int sequence){}
}
