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
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
public class SyncController {
    private final JdbcTemplate jdbc;private final ActorResolver actors;private final OperationsService operations;private final ObjectMapper json;
    public SyncController(JdbcTemplate jdbc,ActorResolver actors,OperationsService operations,ObjectMapper json){this.jdbc=jdbc;this.actors=actors;this.operations=operations;this.json=json;}
    @GetMapping("/bootstrap") public Map<String,Object> bootstrap(@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);Map<String,Object> result=new LinkedHashMap<>();List<Map<String,Object>> assignments=jdbc.queryForList("select usa.station_id,s.code,s.name,usa.active_on_shift from user_station_assignments usa join stations s on s.id=usa.station_id where usa.user_id=?",actor);result.put("assignments",assignments);if(!assignments.isEmpty()){UUID station=(UUID)assignments.getFirst().get("station_id");result.put("visits",jdbc.queryForList("select sv.id,sv.lot_id,l.lot_number,st.style_number,sv.status,sv.dispatched_at,sv.version from station_visits sv join lots l on l.id=sv.lot_id join styles st on st.id=l.style_id where sv.station_id=? and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH')",station));result.put("processes",jdbc.queryForList("select pe.id,pe.station_visit_id,pe.process_id,p.code,p.name,pe.sequence_no,pe.status,pe.version from process_executions pe join processes p on p.id=pe.process_id join station_visits sv on sv.id=pe.station_visit_id where sv.station_id=? and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH')",station));}result.put("serverTime",OffsetDateTime.now());return result;}
    @GetMapping("/changes") public Map<String,Object> changes(@RequestParam OffsetDateTime since,@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);Map<String,Object> result=new LinkedHashMap<>();result.put("processEvents",jdbc.queryForList("select pse.* from process_state_events pse join process_executions pe on pe.id=pse.process_execution_id join station_visits sv on sv.id=pe.station_visit_id join user_station_assignments usa on usa.station_id=sv.station_id where usa.user_id=? and pse.occurred_at>? order by pse.occurred_at",actor,since));result.put("locationEvents",jdbc.queryForList("select le.* from lot_location_events le where le.occurred_at>? order by le.occurred_at",since));result.put("serverTime",OffsetDateTime.now());return result;}
    @PostMapping("/events") @Transactional public List<EventResult> events(@Valid @RequestBody EventBatch batch,@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);List<EventResult> results=new ArrayList<>();for(ClientEvent event:batch.events()){String hash=hash(event);List<Map<String,Object>> existing=jdbc.queryForList("select payload_hash,outcome,detail from sync_events where id=?",event.id());if(!existing.isEmpty()){if(!hash.equals(existing.getFirst().get("payload_hash")))throw new BusinessRuleException("SYNC_EVENT_CONFLICT","Event ID was already used with different content");results.add(new EventResult(event.id(),existing.getFirst().get("outcome").toString(),"duplicate"));continue;}String outcome="APPLIED",detail="";try{apply(event,actor);}catch(RuntimeException ex){outcome="REJECTED";detail=ex.getMessage();}jdbc.update("insert into sync_events(id,device_id,actor_id,aggregate_type,aggregate_id,event_type,payload_hash,outcome,client_occurred_at,detail) values (?,?,?,?,?,?,?,?,?,jsonb_build_object('message',?))",event.id(),batch.deviceId(),actor,event.aggregateType(),event.aggregateId(),event.eventType(),hash,outcome,event.occurredAt(),detail);results.add(new EventResult(event.id(),outcome,detail));}return results;}
    private void apply(ClientEvent event,UUID actor){String target=switch(event.eventType()){case"PROCESS_START","PROCESS_RESUME"->"IN_PROGRESS";case"PROCESS_PAUSE"->"PAUSED";case"PROCESS_BLOCK"->"BLOCKED";case"PROCESS_UNBLOCK"->"PENDING";case"PROCESS_COMPLETE"->"COMPLETED";default->throw new BusinessRuleException("OFFLINE_ACTION_NOT_ALLOWED","This action requires an online connection");};operations.transition(event.aggregateId(),target,event.reason(),actor,false);}
    private String hash(ClientEvent event){try{byte[] value=json.writeValueAsBytes(event);return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    public record EventBatch(@NotBlank String deviceId,@NotEmpty List<@Valid ClientEvent> events){} public record ClientEvent(@NotNull UUID id,@NotBlank String aggregateType,@NotNull UUID aggregateId,@NotBlank String eventType,String reason,@NotNull OffsetDateTime occurredAt){} public record EventResult(UUID id,String outcome,String detail){}
}
