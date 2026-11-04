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

    @PostMapping("/machines") @Transactional public Map<String,Object> createMachine(@Valid @RequestBody MachineCommand r,@AuthenticationPrincipal Jwt jwt){return saveMachine(null,r,actors.id(jwt));}
    @GetMapping("/machines") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> machines(){return jdbc.queryForList(MACHINE_SELECT+" where "+access.processPredicate("m.process_id")+" order by s.code,m.name").stream().map(this::machineView).toList();}
    @GetMapping("/machines/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> machine(@PathVariable UUID id){return machineView(one(MACHINE_SELECT+" where m.id=?",id));}
    @PutMapping("/machines/{id}") @Transactional public Map<String,Object> updateMachine(@PathVariable UUID id,@Valid @RequestBody MachineCommand r,@AuthenticationPrincipal Jwt jwt){return saveMachine(id,r,actors.id(jwt));}
    @PatchMapping("/machines/{id}/status") public Map<String,Object> machineStatus(@PathVariable UUID id,@Valid @RequestBody MachineStatus r){jdbc.update("update machines set status=?,active=?<>'INACTIVE',version=version+1 where id=?",r.status(),r.status(),id);return machine(id);}

    @PostMapping("/storage-areas") @Transactional public Map<String,Object> createArea(@Valid @RequestBody AreaCommand r,@AuthenticationPrincipal Jwt jwt){return saveArea(null,r,actors.id(jwt));}
    @GetMapping("/storage-areas") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> areas(){return jdbc.queryForList(AREA_SELECT+" where "+access.stationPredicate("sa.station_id")+" order by s.code,sa.code").stream().map(this::areaView).toList();}
    @PutMapping("/storage-areas/{id}") @Transactional public Map<String,Object> updateArea(@PathVariable UUID id,@Valid @RequestBody AreaCommand r,@AuthenticationPrincipal Jwt jwt){return saveArea(id,r,actors.id(jwt));}
    @PatchMapping("/storage-areas/{id}/status") @Transactional public Map<String,Object> areaStatus(@PathVariable UUID id,@RequestBody ActiveStatus r,@AuthenticationPrincipal Jwt jwt){if(!r.active())return deleteArea(id,jwt);jdbc.update("update storage_areas set active=true,version=version+1 where id=?",id);return area(id);}

    @PostMapping("/storage-areas/{areaId}/units") @Transactional public Map<String,Object> createUnit(@PathVariable UUID areaId,@Valid @RequestBody UnitCommand r,@AuthenticationPrincipal Jwt jwt){UUID station=jdbc.queryForObject("select station_id from storage_areas where id=? and active",UUID.class,areaId);UUID id=UUID.randomUUID();jdbc.update("insert into storage_locations(id,station_id,storage_area_id,code,name) values (?,?,?,?,?)",id,station,areaId,norm(r.code()),r.name().trim());assignAll(r.nfcUids(),"STORAGE_UNIT",id,actors.id(jwt),"Storage Unit onboarding");return unit(id);}
    @PutMapping("/storage-units/{id}") public Map<String,Object> updateUnit(@PathVariable UUID id,@Valid @RequestBody UnitCommand r){jdbc.update("update storage_locations set code=?,name=?,version=version+1 where id=?",norm(r.code()),r.name().trim(),id);return unit(id);}

    @PostMapping("/nfc-assignments") public Map<String,Object> register(@Valid @RequestBody NfcAssignment r,@AuthenticationPrincipal Jwt jwt){UUID id=nfc.registerAndAssign(r.uid(),r.assetType(),r.assetId(),actors.id(jwt),r.reason());return tag(id);}
    @PutMapping("/nfc-assignments/{tagId}") public Map<String,Object> reassign(@PathVariable UUID tagId,@Valid @RequestBody NfcReassignment r,@AuthenticationPrincipal Jwt jwt){nfc.assign(tagId,r.assetType(),r.assetId(),actors.id(jwt),r.reason());return tag(tagId);}
    @DeleteMapping("/nfc-assignments/{tagId}") public void deactivate(@PathVariable UUID tagId,@RequestBody @Valid Reason r,@AuthenticationPrincipal Jwt jwt){nfc.unassign(tagId,actors.id(jwt),r.reason());}
    @GetMapping("/nfc-assignments/{tagId}/history") public List<Map<String,Object>> history(@PathVariable UUID tagId){return jdbc.queryForList("select asset_type,asset_id,assigned_by,assigned_at,unassigned_by,unassigned_at,reason from nfc_assignment_history where nfc_tag_id=? order by assigned_at",tagId);}

    @GetMapping("/storage-areas/{areaId}/units") public List<Map<String,Object>> units(@PathVariable UUID areaId){return jdbc.queryForList("select id,station_id,storage_area_id,code,name,active,version from storage_locations where storage_area_id=? order by code",areaId);}
    @GetMapping("/storage-units/{id}") public Map<String,Object> getUnit(@PathVariable UUID id){return unit(id);}
    @GetMapping("/storage-areas/{id}") public Map<String,Object> getArea(@PathVariable UUID id){return area(id);}
    @DeleteMapping("/storage-areas/{id}") @Transactional public Map<String,Object> deleteArea(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
        jdbc.queryForObject("select id from storage_areas where id=? for update",UUID.class,id);
        if(jdbc.queryForObject("select count(*) from storage_occupancies so join storage_locations sl on sl.id=so.storage_location_id where sl.storage_area_id=? and so.retrieved_at is null",Integer.class,id)>0)throw new com.yesbee.nwtrack.shared.BusinessRuleException("STORAGE_AREA_IN_USE","Retrieve all lots before deleting the storage area");
        for(UUID tag:jdbc.queryForList("select id from nfc_tags where active and (storage_area_id=? or storage_location_id in(select id from storage_locations where storage_area_id=?))",UUID.class,id,id))nfc.unassign(tag,actors.id(jwt),"Storage area deleted");
        jdbc.update("update storage_locations set active=false,version=version+1 where storage_area_id=?",id);
        jdbc.update("update storage_areas set active=false,version=version+1 where id=?",id);return area(id);
    }
    @DeleteMapping("/machines/{id}") @Transactional public Map<String,Object> deleteMachine(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
        jdbc.queryForObject("select id from machines where id=? for update",UUID.class,id);
        if(jdbc.queryForObject("select count(*) from process_machine_usage where machine_id=? and completed_at is null",Integer.class,id)>0)throw new IllegalArgumentException("Device is in use");
        for(UUID tag:jdbc.queryForList("select id from nfc_tags where machine_id=? and active",UUID.class,id))nfc.unassign(tag,actors.id(jwt),"Device deleted");
        jdbc.update("update machines set active=false,status='INACTIVE',version=version+1 where id=?",id);return machine(id);
    }
    private String selectedUid(String uid,List<String> legacy,UUID tagId){
        if(legacy!=null){if(legacy.size()!=1)throw new IllegalArgumentException("Select exactly one registered NFC tag");if(uid!=null&&!uid.equalsIgnoreCase(legacy.getFirst()))throw new IllegalArgumentException("Conflicting NFC selections");uid=legacy.getFirst();}
        return configurations.registeredUid(uid,tagId);
    }
    private Map<String,Object> saveMachine(UUID existing,MachineCommand r,UUID actor){
        UUID station=jdbc.queryForObject("select station_id from processes where id=? and active and nfc_mode='DEVICES'",UUID.class,r.processId());
        if(r.stationId()!=null&&!station.equals(r.stationId()))throw new IllegalArgumentException("Device station is derived from its owning process");
        UUID id=existing==null?UUID.randomUUID():existing;
        String type=r.type()==null||r.type().isBlank()?(existing==null?"GENERAL":jdbc.queryForObject("select machine_type from machines where id=?",String.class,id)):r.type().trim();
        String uid=selectedUid(r.nfcUid(),r.nfcUids(),r.tagId());
        if(existing==null)jdbc.update("insert into machines(id,station_id,process_id,name,description,machine_type) values (?,?,?,?,?,?)",id,station,r.processId(),r.name().trim(),r.description(),type);
        else {
            jdbc.queryForObject("select id from machines where id=? for update",UUID.class,id);
            if(!r.processId().equals(jdbc.queryForObject("select process_id from machines where id=?",UUID.class,id)))throw new IllegalArgumentException("A device cannot change its owning process");
            if(jdbc.queryForObject("select count(*) from process_machine_usage where machine_id=? and completed_at is null",Integer.class,id)>0)throw new IllegalArgumentException("Device is in use");
            for(UUID tag:jdbc.queryForList("select id from nfc_tags where machine_id=? and active",UUID.class,id))nfc.unassign(tag,actor,"Device updated");
            jdbc.update("update machines set name=?,description=?,machine_type=?,version=version+1 where id=?",r.name().trim(),r.description(),type,id);
        }
        configurations.assignRegistered(uid,"MACHINE",id,actor);return machine(id);
    }
    private Map<String,Object> saveArea(UUID existing,AreaCommand r,UUID actor){
        UUID station=configurations.station(r.stationNumber(),r.stationId()),id=existing==null?UUID.randomUUID():existing;
        configurations.storageNumber(existing,r.storageNumber());
        String uid=selectedUid(r.nfcUid(),r.nfcUids(),r.tagId()),name=r.name()==null||r.name().isBlank()?r.storageNumber().trim():r.name().trim();
        if(existing==null)jdbc.update("insert into storage_areas(id,station_id,code,name,description) values (?,?,?,?,?)",id,station,norm(r.storageNumber()),name,r.description());
        else {
            UUID oldStation=jdbc.queryForObject("select station_id from storage_areas where id=? for update",UUID.class,id);
            if(!station.equals(oldStation)&&jdbc.queryForObject("select count(*) from storage_locations where storage_area_id=?",Integer.class,id)>0)throw new IllegalArgumentException("An area with storage units cannot change its station");
            for(UUID tag:jdbc.queryForList("select id from nfc_tags where storage_area_id=? and active",UUID.class,id))nfc.unassign(tag,actor,"Storage updated");
            jdbc.update("update storage_areas set station_id=?,code=?,name=?,description=?,version=version+1 where id=?",station,norm(r.storageNumber()),name,r.description(),id);
        }
        configurations.assignRegistered(uid,"STORAGE_AREA",id,actor);return area(id);
    }
    private void assignAll(List<String> uids,String type,UUID asset,UUID actor,String reason){if(uids!=null)for(String uid:uids)configurations.assignRegistered(uid,type,asset,actor);}
    private Map<String,Object> area(UUID id){return areaView(one(AREA_SELECT+" where sa.id=?",id));}private Map<String,Object> unit(UUID id){return one("select sl.id,sl.storage_area_id,sl.station_id,sl.code,sl.name,sl.active,sl.version from storage_locations sl where sl.id=?",id);}
    private Map<String,Object> tag(UUID id){return one("select id,uid,name,status,active assigned,asset_type,trolley_id,station_id,process_id,machine_id,storage_area_id,storage_location_id,version from nfc_tags where id=?",id);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new com.yesbee.nwtrack.shared.BusinessRuleException("NOT_FOUND","Requested resource was not found");return rows.getFirst();}
    private Map<String,Object> machineView(Map<String,Object> row){row.put("stationNumber",row.get("station_code"));row.put("processId",row.get("process_id"));row.put("nfcUid",row.get("nfc_uid"));row.put("type",row.get("machine_type"));return row;}
    private Map<String,Object> areaView(Map<String,Object> row){row.put("storageNumber",row.get("code"));row.put("stationNumber",row.get("station_code"));row.put("nfcUid",row.get("nfc_uid"));return row;}
    private String norm(String value){return value.trim().toUpperCase(Locale.ROOT);}
    private static final String MACHINE_SELECT="select m.id,m.station_id,s.code station_code,m.name,m.machine_type,m.status,m.active,m.process_id,m.description,m.version,s.code station_number,(select n.uid from nfc_tags n where n.machine_id=m.id and n.active) nfc_uid from machines m join stations s on s.id=m.station_id";
    private static final String AREA_SELECT="select sa.id,sa.station_id,s.code station_code,sa.code,sa.name,sa.description,sa.active,sa.version,sa.code storage_number,s.code station_number,(select n.uid from nfc_tags n where n.storage_area_id=sa.id and n.active order by n.uid limit 1) nfc_uid from storage_areas sa join stations s on s.id=sa.station_id";
    public record MachineCommand(UUID stationId,@NotBlank String name,String type,@NotNull UUID processId,String description,@Size(max=1) List<@NotBlank String> nfcUids,@com.fasterxml.jackson.annotation.JsonAlias({"tag","nfcTagId"}) String nfcUid,UUID tagId){}
    public record MachineStatus(@Pattern(regexp="AVAILABLE|IN_USE|DOWN|MAINTENANCE|INACTIVE") String status){}
    public record AreaCommand(UUID stationId,@com.fasterxml.jackson.annotation.JsonAlias({"stationReference","station"}) String stationNumber,@NotBlank @Size(max=80) @com.fasterxml.jackson.annotation.JsonAlias({"code","number"}) String storageNumber,String name,String description,@Size(max=1) List<@NotBlank String> nfcUids,@com.fasterxml.jackson.annotation.JsonAlias({"tag","nfcTagId"}) String nfcUid,UUID tagId){}
    public record UnitCommand(@NotBlank String code,@NotBlank String name,List<@NotBlank String> nfcUids){}
    public record NfcAssignment(@NotBlank String uid,@NotBlank String assetType,@NotNull UUID assetId,@NotBlank String reason){}
    public record NfcReassignment(@NotBlank String assetType,@NotNull UUID assetId,@NotBlank String reason){}
    public record Reason(@NotBlank String reason){} public record ActiveStatus(boolean active){}
}
