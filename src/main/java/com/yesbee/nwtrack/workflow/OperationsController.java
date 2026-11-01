package com.yesbee.nwtrack.workflow;

import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/v1") @Tag(name="Operator workflow")
public class OperationsController {
    private final OperationsService service;private final ActorResolver actors;private final JdbcTemplate jdbc;private final StationAccessService access;private final ProcessMachineService machineOperations;
    public OperationsController(OperationsService service,ActorResolver actors,JdbcTemplate jdbc,StationAccessService access,ProcessMachineService machineOperations){this.service=service;this.actors=actors;this.jdbc=jdbc;this.access=access;this.machineOperations=machineOperations;}
    @PostMapping("/nfc/resolve") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN','SUPERVISOR')") public Map<String,Object> resolve(@Valid @RequestBody NfcScan r){return service.resolveNfc(r.uid());}
    @PostMapping("/lots/{id}/trolley-link") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> link(@PathVariable UUID id,@Valid @RequestBody TrolleyLink r,@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);access.assertCanOperateCurrentLotStation(actor,id);service.linkTrolley(id,r.trolleyId(),actor);return service.resolveNfc(jdbc.queryForObject("select n.uid from nfc_tags n where n.trolley_id=? and n.active",String.class,r.trolleyId()));}
    @PostMapping("/lots/{id}/dispatch") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> dispatch(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);access.assertCanOperateCurrentLotStation(actor,id);return service.dispatch(id,actor);}
    @PostMapping("/storage-locations/{id}/retrieve") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> retrieve(@PathVariable UUID id,@Valid @RequestBody StorageRetrieve r,@AuthenticationPrincipal Jwt jwt){service.retrieveStorage(id,r.lotId(),r.trolleyId(),actors.id(jwt));return Map.of("retrieved",true,"lotId",r.lotId(),"trolleyId",r.trolleyId());}
    @PostMapping("/process-executions/{id}/{action:start|pause|resume|block|unblock|skip|complete}") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> transition(@PathVariable UUID id,@PathVariable String action,@RequestBody(required=false) ActionRequest r,@AuthenticationPrincipal Jwt jwt){ActionRequest request=r==null?new ActionRequest(null,null,List.of()):r;String status=machineOperations.transition(id,action,request.reason(),request.processNfcUid(),request.machineNfcUids(),actors.id(jwt));return Map.of("id",id,"status",status);}
    @PostMapping("/process-executions/{id}/replace-machine") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> replaceMachine(@PathVariable UUID id,@Valid @RequestBody MachineReplacement r,@AuthenticationPrincipal Jwt jwt){machineOperations.replaceMachine(id,r.oldMachineNfcUid(),r.newMachineNfcUid(),r.reason(),actors.id(jwt));return Map.of("id",id,"replaced",true);}
    @PostMapping("/process-executions/{id}/correction") @PreAuthorize("hasAnyRole('SUPERVISOR','SUPER_ADMIN')") public Map<String,Object> correction(@PathVariable UUID id,@Valid @RequestBody Correction r,@AuthenticationPrincipal Jwt jwt){return Map.of("id",id,"status",service.transition(id,r.targetStatus(),r.reason(),actors.id(jwt),true));}
    @PostMapping("/station-visits/{id}/wrong-station-override") @PreAuthorize("hasAnyRole('SUPERVISOR','SUPER_ADMIN')") public Map<String,Object> wrongStation(@PathVariable UUID id,@Valid @RequestBody WrongStationOverride r,@AuthenticationPrincipal Jwt jwt){UUID actor=actors.id(jwt);Map<String,Object> visit=jdbc.queryForMap("select station_id,lot_id from station_visits where id=?",id);jdbc.update("update station_visits set station_id=?,version=version+1 where id=?",r.actualStationId(),id);jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason,detail) values (?,'WRONG_STATION_OVERRIDE','STATION_VISIT',?,'SUCCESS',?,jsonb_build_object('expectedStationId',?,'actualStationId',?))",actor,id,r.reason(),visit.get("station_id"),r.actualStationId());return jdbc.queryForMap("select id,lot_id,station_id,status,version from station_visits where id=?",id);}
    @GetMapping("/stations/{id}/incoming") public List<Map<String,Object>> incoming(@PathVariable UUID id){return jdbc.queryForList("select sv.id visit_id,sv.lot_id,l.lot_number,s.style_number,sv.dispatched_at,t.code trolley_code from station_visits sv join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id left join lot_trolley_assignments a on a.lot_id=l.id and a.released_at is null left join trolleys t on t.id=a.trolley_id where sv.station_id=? and sv.status='DISPATCHED' order by sv.dispatched_at",id);}
    @GetMapping("/station-visits/{id}/processes") public List<Map<String,Object>> visitProcesses(@PathVariable UUID id){return jdbc.queryForList("select pe.id,pe.process_id,p.code,p.name,pe.sequence_no,pe.status,pe.started_at,pe.completed_at,pe.reason,pe.version from process_executions pe join processes p on p.id=pe.process_id where pe.station_visit_id=? order by pe.sequence_no",id);}
    public record NfcScan(@NotBlank String uid){} public record TrolleyLink(@NotNull UUID trolleyId){} public record StorageRetrieve(@NotNull UUID lotId,@NotNull UUID trolleyId){} public record ActionRequest(String reason,String processNfcUid,List<String> machineNfcUids){} public record MachineReplacement(@NotBlank String oldMachineNfcUid,@NotBlank String newMachineNfcUid,@NotBlank String reason){} public record Correction(@NotBlank String targetStatus,@NotBlank String reason){} public record WrongStationOverride(@NotNull UUID actualStationId,@NotBlank String reason){}
}
