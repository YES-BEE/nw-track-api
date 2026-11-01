package com.yesbee.nwtrack.workflow;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ProcessMachineService {
    private final JdbcTemplate jdbc;private final OperationsService operations;
    public ProcessMachineService(JdbcTemplate jdbc,OperationsService operations){this.jdbc=jdbc;this.operations=operations;}

    @Transactional
    public String transition(UUID executionId,String action,String reason,String processNfcUid,List<String> machineNfcUids,UUID actor){
        String target=switch(action){case"start","resume"->"IN_PROGRESS";case"pause"->"PAUSED";case"block"->"BLOCKED";case"unblock"->"PENDING";case"skip"->"SKIPPED";case"complete"->"COMPLETED";default->throw new IllegalArgumentException(action);};
        if("start".equals(action))startMachines(executionId,processNfcUid,machineNfcUids,actor);
        if("complete".equals(action))verifyCompletionScans(executionId,processNfcUid,machineNfcUids);
        String status=operations.transition(executionId,target,reason,actor,false);
        if("complete".equals(action))releaseMachines(executionId,actor);
        return status;
    }

    @Transactional
    public void replaceMachine(UUID executionId,String oldUid,String newUid,String reason,UUID actor){
        UUID oldMachine=machine(oldUid),replacement=machine(newUid);
        Map<String,Object> usage=one("select pmu.id,m.station_id from process_machine_usage pmu join machines m on m.id=pmu.machine_id where pmu.process_execution_id=? and pmu.machine_id=? and pmu.completed_at is null for update",executionId,oldMachine);
        UUID station=(UUID)usage.get("station_id");verifyMachineAvailable(replacement,station);
        jdbc.update("update process_machine_usage set completed_at=now(),completed_by=?,reason=? where id=?",actor,reason,usage.get("id"));
        jdbc.update("update machines set status=case when status in ('DOWN','MAINTENANCE') then status else 'AVAILABLE' end,version=version+1 where id=?",oldMachine);
        jdbc.update("insert into process_machine_usage(id,process_execution_id,machine_id,started_by,replacement_for_usage_id,reason) values (?,?,?,?,?,?)",UUID.randomUUID(),executionId,replacement,actor,usage.get("id"),reason);
        jdbc.update("update machines set status='IN_USE',version=version+1 where id=?",replacement);
        jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason,detail) values (?,'REPLACE_MACHINE','PROCESS_EXECUTION',?,'SUCCESS',?,jsonb_build_object('oldMachineId',?,'newMachineId',?))",actor,executionId,reason,oldMachine,replacement);
    }

    private void startMachines(UUID executionId,String processUid,List<String> machineUids,UUID actor){
        Execution e=execution(executionId);verifyProcessTag(processUid,e.processId());
        if(machineUids==null||machineUids.isEmpty())throw new BusinessRuleException("MACHINE_SCAN_REQUIRED","Scan at least one Machine before starting the Process");
        Set<UUID> machines=new LinkedHashSet<>();for(String uid:machineUids)machines.add(machine(uid));
        if(machines.size()!=machineUids.size())throw new BusinessRuleException("DUPLICATE_MACHINE_SCAN","The same Machine was scanned more than once");
        for(UUID machine:machines){verifyMachineAvailable(machine,e.stationId());jdbc.update("insert into process_machine_usage(id,process_execution_id,machine_id,started_by) values (?,?,?,?)",UUID.randomUUID(),executionId,machine,actor);jdbc.update("update machines set status='IN_USE',version=version+1 where id=?",machine);}
    }
    private void verifyCompletionScans(UUID executionId,String processUid,List<String> machineUids){Execution e=execution(executionId);verifyProcessTag(processUid,e.processId());Set<UUID> expected=new HashSet<>(jdbc.query("select machine_id from process_machine_usage where process_execution_id=? and completed_at is null",(rs,n)->(UUID)rs.getObject(1),executionId));Set<UUID> scanned=new HashSet<>();if(machineUids!=null)for(String uid:machineUids)scanned.add(machine(uid));if(!expected.equals(scanned))throw new BusinessRuleException("MACHINE_SCAN_MISMATCH","Completion requires scanning every Machine active on this Process execution");}
    private void releaseMachines(UUID executionId,UUID actor){List<UUID> ids=jdbc.query("select machine_id from process_machine_usage where process_execution_id=? and completed_at is null",(rs,n)->(UUID)rs.getObject(1),executionId);jdbc.update("update process_machine_usage set completed_at=now(),completed_by=? where process_execution_id=? and completed_at is null",actor,executionId);for(UUID id:ids)jdbc.update("update machines set status='AVAILABLE',version=version+1 where id=? and status='IN_USE'",id);}
    private void verifyProcessTag(String uid,UUID process){if(uid==null||uid.isBlank())throw new BusinessRuleException("PROCESS_SCAN_REQUIRED","Scan the Process NFC tag");Integer count=jdbc.queryForObject("select count(*) from nfc_tags where uid=? and process_id=? and asset_type='PROCESS' and active",Integer.class,uid.trim(),process);if(count==null||count==0)throw new BusinessRuleException("PROCESS_NFC_MISMATCH","Scanned Process NFC does not match the execution");}
    private UUID machine(String uid){List<UUID> ids=jdbc.query("select machine_id from nfc_tags where uid=? and asset_type='MACHINE' and active",(rs,n)->(UUID)rs.getObject(1),uid.trim());if(ids.isEmpty())throw new BusinessRuleException("MACHINE_NFC_INVALID","Machine NFC is unknown or inactive");return ids.getFirst();}
    private void verifyMachineAvailable(UUID id,UUID station){Integer count=jdbc.queryForObject("select count(*) from machines where id=? and station_id=? and active and status='AVAILABLE'",Integer.class,id,station);if(count==null||count==0)throw new BusinessRuleException("MACHINE_UNAVAILABLE","Machine is unavailable, already in use, or belongs to another Station");}
    private Execution execution(UUID id){Map<String,Object> row=one("select pe.process_id,sv.station_id from process_executions pe join station_visits sv on sv.id=pe.station_visit_id where pe.id=?",id);return new Execution((UUID)row.get("process_id"),(UUID)row.get("station_id"));}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new BusinessRuleException("NOT_FOUND","Requested execution or Machine usage was not found");return rows.getFirst();}
    private record Execution(UUID processId,UUID stationId){}
}
