package com.yesbee.nwtrack.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesbee.nwtrack.shared.ActorResolver;
import com.yesbee.nwtrack.shared.BusinessRuleException;
import com.yesbee.nwtrack.workflow.OperationsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@RestController @RequestMapping("/api/v1/sync") @Tag(name="Offline synchronization")
@PreAuthorize("isAuthenticated()")
public class SyncController {
    private final JdbcTemplate jdbc;private final ActorResolver actors;private final OperationsService operations;private final ObjectMapper json;private final com.yesbee.nwtrack.auth.AccessPolicy access;private final com.yesbee.nwtrack.workflow.ProcessMachineService machines;private final org.springframework.transaction.support.TransactionTemplate transactions;
    public SyncController(JdbcTemplate jdbc,ActorResolver actors,OperationsService operations,ObjectMapper json,com.yesbee.nwtrack.auth.AccessPolicy access,com.yesbee.nwtrack.workflow.ProcessMachineService machines,org.springframework.transaction.PlatformTransactionManager manager){this.jdbc=jdbc;this.actors=actors;this.operations=operations;this.json=json;this.access=access;this.machines=machines;this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);}
    @GetMapping("/bootstrap") public Map<String,Object> bootstrap(@AuthenticationPrincipal Jwt jwt){
        UUID actor=actors.id(jwt);Map<String,Object> result=new LinkedHashMap<>();
        result.put("assignments",jdbc.queryForList("select usa.station_id,s.code,s.name,usa.active_on_shift from user_station_assignments usa join stations s on s.id=usa.station_id where usa.user_id=?",actor));
        result.put("visits",jdbc.queryForList("select sv.id,sv.station_id,sv.lot_id,l.lot_number,st.style_number,sv.status,sv.dispatched_at,sv.version from station_visits sv join lots l on l.id=sv.lot_id join styles st on st.id=l.style_id where "+access.stationPredicate("sv.station_id")+" and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH')"));
        result.put("processes",jdbc.queryForList("select pe.id,pe.station_visit_id,pe.process_id,p.code,p.name,p.quality,p.nfc_mode,pe.sequence_no,pe.status,pe.version from process_executions pe join processes p on p.id=pe.process_id join station_visits sv on sv.id=pe.station_visit_id where "+access.stationPredicate("sv.station_id")+" and "+access.processPredicate("pe.process_id")+" and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH')"));
        result.put("serverTime",OffsetDateTime.now());return result;
    }
    @GetMapping("/changes") public Map<String,Object> changes(@RequestParam OffsetDateTime since,@AuthenticationPrincipal Jwt jwt){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("processEvents",jdbc.queryForList("select pse.* from process_state_events pse join process_executions pe on pe.id=pse.process_execution_id join station_visits sv on sv.id=pe.station_visit_id where "+access.stationPredicate("sv.station_id")+" and "+access.processPredicate("pe.process_id")+" and pse.occurred_at>? order by pse.occurred_at",since));
        result.put("locationEvents",jdbc.queryForList("select le.* from lot_location_events le where "+access.lotPredicate("le.lot_id")+" and le.occurred_at>? order by le.occurred_at",since));
        result.put("serverTime",OffsetDateTime.now());return result;
    }
    @PostMapping("/events") public List<EventResult> events(@Valid @RequestBody EventBatch batch,@AuthenticationPrincipal Jwt jwt){
        UUID actor=actors.id(jwt);List<EventResult> results=new ArrayList<>();
        for(ClientEvent event:batch.events()){
            String hash=hash(event);
            try{
                results.add(transactions.execute(status->record(batch.deviceId(),event,hash,actor,null)));
            }catch(RuntimeException ex){
                if(ex instanceof BusinessRuleException rule && "SYNC_EVENT_CONFLICT".equals(rule.code()))throw ex;
                if(ex instanceof org.springframework.security.access.AccessDeniedException)throw ex;
                results.add(transactions.execute(status->record(batch.deviceId(),event,hash,actor,Objects.toString(ex.getMessage(),"Event rejected"))));
            }
        }
        return results;
    }
    private EventResult record(String device,ClientEvent event,String hash,UUID actor,String rejection){
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?::text,0))",event.id().toString());
        List<Map<String,Object>> existing=jdbc.queryForList("select actor_id,payload_hash,outcome from sync_events where id=?",event.id());
        if(!existing.isEmpty()){
            var row=existing.getFirst();
            if(!actor.equals(row.get("actor_id")))throw new org.springframework.security.access.AccessDeniedException("Event belongs to another user");
            if(!hash.equals(row.get("payload_hash")))throw new BusinessRuleException("SYNC_EVENT_CONFLICT","Event ID was already used with different content");
            return new EventResult(event.id(),row.get("outcome").toString(),"duplicate");
        }
        if(rejection==null)apply(event,actor);
        String outcome=rejection==null?"APPLIED":"REJECTED",detail=Objects.requireNonNullElse(rejection,"");
        jdbc.update("insert into sync_events(id,device_id,actor_id,aggregate_type,aggregate_id,event_type,payload_hash,outcome,client_occurred_at,detail) values (?,?,?,?,?,?,?,?,?,jsonb_build_object('message',?::text))",event.id(),device,actor,event.aggregateType(),event.aggregateId(),event.eventType(),hash,outcome,event.occurredAt(),detail);
        return new EventResult(event.id(),outcome,detail);
    }
    private void apply(ClientEvent event,UUID actor){
        String action=switch(event.eventType()){case "PROCESS_START"->"start";case "PROCESS_RESUME"->"resume";case "PROCESS_PAUSE"->"pause";case "PROCESS_BLOCK"->"block";case "PROCESS_UNBLOCK"->"unblock";case "PROCESS_COMPLETE"->"complete";default->throw new BusinessRuleException("OFFLINE_ACTION_NOT_ALLOWED","This action requires an online connection");};
        if(!"PROCESS_EXECUTION".equals(event.aggregateType()))throw new IllegalArgumentException("aggregateType must be PROCESS_EXECUTION");
        machines.transition(event.aggregateId(),action,event.reason(),event.processNfcUid(),event.machineNfcUids(),actor);
    }
    private String hash(ClientEvent event){try{byte[] value=json.writeValueAsBytes(event);return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    public record EventBatch(@NotBlank String deviceId,@NotEmpty List<@Valid ClientEvent> events){} public record ClientEvent(@NotNull UUID id,@NotBlank String aggregateType,@NotNull UUID aggregateId,@NotBlank String eventType,String reason,@NotNull OffsetDateTime occurredAt,String processNfcUid,List<String> machineNfcUids){} public record EventResult(UUID id,String outcome,String detail){}
}
