package com.yesbee.nwtrack.qc;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import com.yesbee.nwtrack.workflow.StationAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class QcService {
    private final JdbcTemplate jdbc;private final StationAccessService access;
    public QcService(JdbcTemplate jdbc,StationAccessService access){this.jdbc=jdbc;this.access=access;}
    @Transactional public UUID complete(UUID lotId,String outcome,int inspected,int good,int rejected,String reason,UUID actor){
        Lot lot=jdbc.queryForObject("select l.quantity,l.style_id,l.status from lots l where l.id=? for update",(rs,n)->new Lot(rs.getInt(1),(UUID)rs.getObject(2),rs.getString(3)),lotId);
        if(lot==null)throw new BusinessRuleException("LOT_NOT_FOUND","Lot was not found");
        if("COMPLETED".equals(lot.status())||"CANCELLED".equals(lot.status()))throw new BusinessRuleException("LOT_TERMINAL","Lot is already terminal");
        if(inspected!=lot.quantity()||good+rejected!=inspected)throw new BusinessRuleException("QC_QUANTITY_MISMATCH","Inspected quantity must equal Lot quantity and good plus rejected quantity");
        if(!java.util.Set.of("PASS","PARTIAL_REJECT","REJECT").contains(outcome))throw new BusinessRuleException("QC_OUTCOME_INVALID","Unknown QC outcome");
        Boolean atQc=jdbc.queryForObject("select s.is_qc from station_visits sv join stations s on s.id=sv.station_id join lot_route_entries lre on lre.id=sv.lot_route_entry_id where sv.lot_id=? order by lre.sequence_no desc limit 1",Boolean.class,lotId);
        if(!Boolean.TRUE.equals(atQc))throw new BusinessRuleException("QC_NOT_FINAL","Lot route does not end at QC");
        UUID qcStation=jdbc.queryForObject("select sv.station_id from station_visits sv join lot_route_entries lre on lre.id=sv.lot_route_entry_id where sv.lot_id=? order by lre.sequence_no desc limit 1",UUID.class,lotId);
        access.assertCanOperate(actor,qcStation);
        Integer unresolved=jdbc.queryForObject("select count(*) from process_executions pe join station_visits sv on sv.id=pe.station_visit_id where sv.lot_id=? and pe.status not in ('COMPLETED','SKIPPED')",Integer.class,lotId);
        if(unresolved!=null&&unresolved>0)throw new BusinessRuleException("PROCESSES_INCOMPLETE","All required Processes must be resolved before QC completion");
        UUID inspectionId=UUID.randomUUID();
        jdbc.update("insert into qc_inspections(id,lot_id,outcome,inspected_quantity,good_quantity,rejected_quantity,reason,operator_id,completed_at) values (?,?,?,?,?,?,?,?,now())",inspectionId,lotId,outcome,inspected,good,rejected,reason,actor);
        jdbc.update("update station_visits set status='COMPLETED',completed_at=now(),version=version+1 where lot_id=? and station_id=(select id from stations where is_qc)",lotId);
        jdbc.update("update lot_route_entries set status='COMPLETED' where lot_id=?",lotId);
        jdbc.update("update lots set status='COMPLETED',current_location_type='COMPLETED',current_location_id=null,completed_at=now(),version=version+1 where id=?",lotId);
        jdbc.update("insert into lot_location_events(lot_id,location_type,actor_id,detail) values (?,'COMPLETED',?,jsonb_build_object('qcOutcome',?))",lotId,actor,outcome);
        Integer open=jdbc.queryForObject("select count(*) from lots where style_id=? and status not in ('COMPLETED','CANCELLED')",Integer.class,lot.styleId());
        if(open!=null&&open==0)jdbc.update("update styles set status='COMPLETED',version=version+1 where id=?",lot.styleId());
        return inspectionId;
    }
    private record Lot(int quantity,UUID styleId,String status){}
}
