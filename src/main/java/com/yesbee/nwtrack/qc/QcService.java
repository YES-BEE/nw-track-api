package com.yesbee.nwtrack.qc;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import com.yesbee.nwtrack.workflow.StationAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import com.yesbee.nwtrack.workflow.OperationsService;
import java.util.Set;

@Service
public class QcService {
    private final JdbcTemplate jdbc;private final StationAccessService access;private final OperationsService operations;
    public QcService(JdbcTemplate jdbc,StationAccessService access,OperationsService operations){this.jdbc=jdbc;this.access=access;this.operations=operations;}
    @Transactional public UUID completeExecution(UUID execution,int passed,int rejected,String remarks,UUID actor){
        if(!operations.isFinalQuality(execution))throw new BusinessRuleException("QC_NOT_FINAL","Execution is not the final quality process");
        UUID lot=jdbc.queryForObject("select sv.lot_id from station_visits sv join process_executions pe on pe.station_visit_id=sv.id where pe.id=?",UUID.class,execution);
        return complete(lot,rejected==0?"PASS":passed==0?"REJECT":"PARTIAL_REJECT",passed+rejected,passed,rejected,remarks,actor);
    }
    @Transactional public UUID complete(UUID lotId,String outcome,int inspected,int good,int rejected,String reason,UUID actor){
        Lot lot=jdbc.queryForObject("select l.quantity,l.style_id,l.status from lots l where l.id=? for update",(rs,n)->new Lot((Integer)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3)),lotId);
        if(lot==null)throw new BusinessRuleException("LOT_NOT_FOUND","Lot was not found");
        if("COMPLETED".equals(lot.status())||"CANCELLED".equals(lot.status()))throw new BusinessRuleException("LOT_TERMINAL","Lot is already terminal");
        if(good<0||rejected<0||inspected<=0||(lot.quantity()!=null&&inspected!=lot.quantity())||(long)good+rejected!=inspected)throw new BusinessRuleException("QC_QUANTITY_MISMATCH","Passed plus rejected must equal the positive Lot quantity");
        if(rejected>0&&(reason==null||reason.isBlank()))throw new BusinessRuleException("QC_REMARKS_REQUIRED","Remarks are required when pieces are rejected");
        String expected=rejected==0?"PASS":good==0?"REJECT":"PARTIAL_REJECT";
        if(!expected.equals(outcome))throw new BusinessRuleException("QC_OUTCOME_MISMATCH","Outcome does not match the passed and rejected quantities");
        if(!Set.of("PASS","PARTIAL_REJECT","REJECT").contains(outcome))throw new BusinessRuleException("QC_OUTCOME_INVALID","Unknown QC outcome");
        UUID finalExecution=jdbc.queryForObject("select pe.id from process_executions pe join station_visits sv on sv.id=pe.station_visit_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=? order by lr.sequence_no desc,pe.sequence_no desc limit 1",UUID.class,lotId);
        Boolean atQc=operations.isFinalQuality(finalExecution);
        if(!Boolean.TRUE.equals(atQc))throw new BusinessRuleException("QC_NOT_FINAL","Lot route does not end at QC");
        UUID qcStation=jdbc.queryForObject("select sv.station_id from station_visits sv join lot_route_entries lre on lre.id=sv.lot_route_entry_id where sv.lot_id=? order by lre.sequence_no desc limit 1",UUID.class,lotId);
        access.assertCanOperate(actor,qcStation);
        access.assertCanExecute(actor,finalExecution);
        operations.transition(finalExecution,"COMPLETED",reason,actor,false,true);
        jdbc.update("update machines set status='AVAILABLE',version=version+1 where id in(select machine_id from process_machine_usage where process_execution_id=? and completed_at is null)",finalExecution);
        jdbc.update("update process_machine_usage set completed_at=now(),completed_by=? where process_execution_id=? and completed_at is null",actor,finalExecution);
        Integer unresolved=jdbc.queryForObject("select count(*) from process_executions pe join station_visits sv on sv.id=pe.station_visit_id where sv.lot_id=? and pe.status not in ('COMPLETED','SKIPPED')",Integer.class,lotId);
        if(unresolved!=null&&unresolved>0)throw new BusinessRuleException("PROCESSES_INCOMPLETE","All required Processes must be resolved before QC completion");
        if(lot.quantity()==null)jdbc.update("update lots set quantity=? where id=?",inspected,lotId);
        UUID inspectionId=UUID.randomUUID();
        jdbc.update("insert into qc_inspections(id,lot_id,outcome,inspected_quantity,good_quantity,rejected_quantity,reason,operator_id,completed_at) values (?,?,?,?,?,?,?,?,now())",inspectionId,lotId,outcome,inspected,good,rejected,reason,actor);
        jdbc.update("update station_visits set status='COMPLETED',completed_at=now(),version=version+1 where lot_id=? and station_id=?",lotId,qcStation);
        jdbc.update("update lot_route_entries set status='COMPLETED' where lot_id=?",lotId);
        jdbc.update("update lots set status='COMPLETED',current_location_type='COMPLETED',current_location_id=null,completed_at=now(),version=version+1 where id=?",lotId);
        jdbc.update("insert into lot_location_events(lot_id,location_type,actor_id,detail) values (?,'COMPLETED',?,jsonb_build_object('qcOutcome',?))",lotId,actor,outcome);
        Integer open=jdbc.queryForObject("select count(*) from lots where style_id=? and status not in ('COMPLETED','CANCELLED')",Integer.class,lot.styleId());
        if(open!=null&&open==0)jdbc.update("update styles set status='COMPLETED',version=version+1 where id=?",lot.styleId());
        return inspectionId;
    }
    private record Lot(Integer quantity,UUID styleId,String status){}
}
