package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController @RequestMapping("/api/v1") @Tag(name="NFC asset administration")
@PreAuthorize("isAuthenticated()")
public class AssetAdministrationController {
    private final JdbcTemplate jdbc;private final NfcAssetService nfc;private final ActorResolver actors;private final ConfigurationService configurations;private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public AssetAdministrationController(JdbcTemplate jdbc,NfcAssetService nfc,ActorResolver actors,ConfigurationService configurations,com.yesbee.nwtrack.auth.AccessPolicy access){this.jdbc=jdbc;this.nfc=nfc;this.actors=actors;this.configurations=configurations;this.access=access;}

    @PostMapping("/machines") @Transactional public Map<String,Object> createMachine(@Valid @RequestBody MachineCommand r,@AuthenticationPrincipal Jwt jwt){UUID id=UUID.randomUUID();jdbc.update("insert into machines(id,station_id,name,machine_type,process_id,description) values (?,?,?,?,?,?)",id,r.stationId(),r.name().trim(),r.type().trim(),r.processId(),r.description());assignAll(r.nfcUids(),"MACHINE",id,actors.id(jwt),"Machine onboarding");return machine(id);}
    @GetMapping("/machines") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> machines(){return jdbc.queryForList(MACHINE_SELECT+" where "+access.stationPredicate("m.station_id")+" order by s.code,m.name");}
    @GetMapping("/machines/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> machine(@PathVariable UUID id){return one(MACHINE_SELECT+" where m.id=?",id);}
    @PutMapping("/machines/{id}") @Transactional public Map<String,Object> updateMachine(@PathVariable UUID id,@Valid @RequestBody MachineCommand r,@AuthenticationPrincipal Jwt jwt){if(jdbc.queryForObject("select count(*) from process_machine_usage where machine_id=? and completed_at is null",Integer.class,id)>0)throw new IllegalArgumentException("Device is in use");for(UUID tag:jdbc.queryForList("select id from nfc_tags where machine_id=? and active",UUID.class,id))nfc.unassign(tag,actors.id(jwt),"Device updated");jdbc.update("update machines set station_id=?,name=?,machine_type=?,process_id=?,description=?,version=version+1 where id=?",r.stationId(),r.name().trim(),r.type().trim(),r.processId(),r.description(),id);assignAll(r.nfcUids(),"MACHINE",id,actors.id(jwt),"Device updated");return machine(id);}
    @PatchMapping("/machines/{id}/status") public Map<String,Object> machineStatus(@PathVariable UUID id,@Valid @RequestBody MachineStatus r){jdbc.update("update machines set status=?,active=?<>'INACTIVE',version=version+1 where id=?",r.status(),r.status(),id);return machine(id);}

    @PostMapping("/storage-areas") @Transactional public Map<String,Object> createArea(@Valid @RequestBody AreaCommand r,@AuthenticationPrincipal Jwt jwt){configurations.storageNumber(null,r.code());UUID id=UUID.randomUUID();jdbc.update("insert into storage_areas(id,station_id,code,name,description) values (?,?,?,?,?)",id,configurations.station(r.stationReference(),r.stationId()),norm(r.code()),r.name().trim(),r.description());assignAll(r.nfcUids(),"STORAGE_AREA",id,actors.id(jwt),"Storage Area onboarding");return area(id);}
    @GetMapping("/storage-areas") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> areas(){return jdbc.queryForList(AREA_SELECT+" where "+access.stationPredicate("sa.station_id")+" order by s.code,sa.code");}
    @PutMapping("/storage-areas/{id}") @Transactional public Map<String,Object> updateArea(@PathVariable UUID id,@Valid @RequestBody AreaCommand r,@AuthenticationPrincipal Jwt jwt){configurations.storageNumber(id,r.code());for(UUID tag:jdbc.queryForList("select id from nfc_tags where storage_area_id=? and active",UUID.class,id))nfc.unassign(tag,actors.id(jwt),"Storage configuration changed");jdbc.update("update storage_areas set station_id=?,code=?,name=?,description=?,version=version+1 where id=?",configurations.station(r.stationReference(),r.stationId()),norm(r.code()),r.name().trim(),r.description(),id);assignAll(r.nfcUids(),"STORAGE_AREA",id,actors.id(jwt),"Storage updated");return area(id);}
    @PatchMapping("/storage-areas/{id}/status") public Map<String,Object> areaStatus(@PathVariable UUID id,@RequestBody ActiveStatus r){jdbc.update("update storage_areas set active=?,version=version+1 where id=?",r.active(),id);return area(id);}

    @PostMapping("/storage-areas/{areaId}/units") @Transactional public Map<String,Object> createUnit(@PathVariable UUID areaId,@Valid @RequestBody UnitCommand r,@AuthenticationPrincipal Jwt jwt){UUID station=jdbc.queryForObject("select station_id from storage_areas where id=? and active",UUID.class,areaId);UUID id=UUID.randomUUID();jdbc.update("insert into storage_locations(id,station_id,storage_area_id,code,name) values (?,?,?,?,?)",id,station,areaId,norm(r.code()),r.name().trim());assignAll(r.nfcUids(),"STORAGE_UNIT",id,actors.id(jwt),"Storage Unit onboarding");return unit(id);}
    @PutMapping("/storage-units/{id}") public Map<String,Object> updateUnit(@PathVariable UUID id,@Valid @RequestBody UnitCommand r){jdbc.update("update storage_locations set code=?,name=?,version=version+1 where id=?",norm(r.code()),r.name().trim(),id);return unit(id);}

    @PostMapping("/nfc-assignments") public Map<String,Object> register(@Valid @RequestBody NfcAssignment r,@AuthenticationPrincipal Jwt jwt){UUID id=nfc.registerAndAssign(r.uid(),r.assetType(),r.assetId(),actors.id(jwt),r.reason());return tag(id);}
    @PutMapping("/nfc-assignments/{tagId}") public Map<String,Object> reassign(@PathVariable UUID tagId,@Valid @RequestBody NfcReassignment r,@AuthenticationPrincipal Jwt jwt){nfc.assign(tagId,r.assetType(),r.assetId(),actors.id(jwt),r.reason());return tag(tagId);}
    @DeleteMapping("/nfc-assignments/{tagId}") public void deactivate(@PathVariable UUID tagId,@RequestBody @Valid Reason r,@AuthenticationPrincipal Jwt jwt){nfc.unassign(tagId,actors.id(jwt),r.reason());}
    @GetMapping("/nfc-assignments/{tagId}/history") public List<Map<String,Object>> history(@PathVariable UUID tagId){return jdbc.queryForList("select asset_type,asset_id,assigned_by,assigned_at,unassigned_by,unassigned_at,reason from nfc_assignment_history where nfc_tag_id=? order by assigned_at",tagId);}

    @GetMapping("/storage-areas/{areaId}/units") public List<Map<String,Object>> units(@PathVariable UUID areaId){return jdbc.queryForList("select id,station_id,storage_area_id,code,name,active,version from storage_locations where storage_area_id=? order by code",areaId);}
    @GetMapping("/storage-units/{id}") public Map<String,Object> getUnit(@PathVariable UUID id){return unit(id);}
    @GetMapping("/storage-areas/{id}") public Map<String,Object> getArea(@PathVariable UUID id){return area(id);}
    @DeleteMapping("/storage-areas/{id}") public Map<String,Object> deleteArea(@PathVariable UUID id){jdbc.update("update storage_areas set active=false,version=version+1 where id=?",id);return area(id);}
    @DeleteMapping("/machines/{id}") public Map<String,Object> deleteMachine(@PathVariable UUID id){if(jdbc.queryForObject("select count(*) from process_machine_usage where machine_id=? and completed_at is null",Integer.class,id)>0)throw new IllegalArgumentException("Device is in use");jdbc.update("update machines set active=false,status='INACTIVE',version=version+1 where id=?",id);return machine(id);}
    private void assignAll(List<String> uids,String type,UUID asset,UUID actor,String reason){if(uids!=null)for(String uid:uids)configurations.assignRegistered(uid,type,asset,actor);}
    private Map<String,Object> area(UUID id){return one(AREA_SELECT+" where sa.id=?",id);}private Map<String,Object> unit(UUID id){return one("select sl.id,sl.storage_area_id,sl.station_id,sl.code,sl.name,sl.active,sl.version from storage_locations sl where sl.id=?",id);}
    private Map<String,Object> tag(UUID id){return one("select id,uid,name,enabled,asset_type,trolley_id,station_id,process_id,machine_id,storage_area_id,storage_location_id,active,version from nfc_tags where id=?",id);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new com.yesbee.nwtrack.shared.BusinessRuleException("NOT_FOUND","Requested resource was not found");return rows.getFirst();}
    private String norm(String value){return value.trim().toUpperCase(Locale.ROOT);}
    private static final String MACHINE_SELECT="select m.id,m.station_id,s.code station_code,m.name,m.machine_type,m.status,m.active,m.process_id,m.description,m.version from machines m join stations s on s.id=m.station_id";
    private static final String AREA_SELECT="select sa.id,sa.station_id,s.code station_code,sa.code,sa.name,sa.description,sa.active,sa.version,(select n.uid from nfc_tags n where n.storage_area_id=sa.id and n.active order by n.uid limit 1) nfc_uid from storage_areas sa join stations s on s.id=sa.station_id";
    public record MachineCommand(@NotNull UUID stationId,@NotBlank String name,@NotBlank String type,@NotNull UUID processId,String description,@NotNull @Size(min=1,max=1) List<@NotBlank String> nfcUids){}
    public record MachineStatus(@Pattern(regexp="AVAILABLE|IN_USE|DOWN|MAINTENANCE|INACTIVE") String status){}
    public record AreaCommand(UUID stationId,String stationReference,@NotBlank String code,@NotBlank String name,String description,@NotNull @Size(min=1,max=1) List<@NotBlank String> nfcUids){}
    public record UnitCommand(@NotBlank String code,@NotBlank String name,List<@NotBlank String> nfcUids){}
    public record NfcAssignment(@NotBlank String uid,@NotBlank String assetType,@NotNull UUID assetId,@NotBlank String reason){}
    public record NfcReassignment(@NotBlank String assetType,@NotNull UUID assetId,@NotBlank String reason){}
    public record Reason(@NotBlank String reason){} public record ActiveStatus(boolean active){}
}
