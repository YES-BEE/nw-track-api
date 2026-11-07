package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @Tag(name="Factory master data")
@PreAuthorize("isAuthenticated()")
public class MasterDataController {
    private final JdbcTemplate jdbc;private final NfcAssetService nfc;private final ActorResolver actors;private final ConfigurationService configurations;private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public MasterDataController(JdbcTemplate jdbc,NfcAssetService nfc,ActorResolver actors,ConfigurationService configurations,com.yesbee.nwtrack.auth.AccessPolicy access){this.jdbc=jdbc;this.nfc=nfc;this.actors=actors;this.configurations=configurations;this.access=access;}

    @PostMapping("/api/v1/stations") public Map<String,Object> createStation(@Valid @RequestBody StationCommand r,@AuthenticationPrincipal Jwt jwt){UUID id=UUID.randomUUID();jdbc.update("insert into stations(id,code,name,station_type,active,is_station_zero,is_qc) values (?,?,?,?,true,?,?)",id,r.code().trim(),r.name().trim(),r.stationType(),r.stationZero(),r.qc());if(r.nfcUid()!=null&&!r.nfcUid().isBlank())nfc.registerAndAssign(r.nfcUid(),"STATION",id,actors.id(jwt),"Station onboarding");return station(id);}
    @GetMapping("/api/v1/stations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> stations(){return jdbc.queryForList("select id,code,name,station_type,active,is_station_zero,is_qc,version from stations where "+access.stationPredicate("id")+" order by code");}
    @GetMapping("/api/v1/stations/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> station(@PathVariable UUID id){return one("select id,code,name,station_type,active,is_station_zero,is_qc,version from stations where id=?",id);}
    @PutMapping("/api/v1/stations/{id}") public Map<String,Object> updateStation(@PathVariable UUID id,@Valid @RequestBody StationCommand r){if(!jdbc.queryForObject("select code from stations where id=?",String.class,id).equals(r.code()))throw new IllegalArgumentException("Station reference cannot change");jdbc.update("update stations set code=?,name=?,station_type=?,is_station_zero=?,is_qc=?,version=version+1 where id=?",r.code().trim(),r.name().trim(),r.stationType(),r.stationZero(),r.qc(),id);return station(id);}
    @PatchMapping("/api/v1/stations/{id}/status") public Map<String,Object> stationStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update stations set active=?,version=version+1 where id=?",r.active(),id);return station(id);}

    @PostMapping("/api/v1/processes") public Map<String,Object> createProcess(@Valid @RequestBody ConfigurationService.ProcessCommand r,@AuthenticationPrincipal Jwt jwt){return process(configurations.saveProcess(null,r,actors.id(jwt)));}
    @GetMapping("/api/v1/processes") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> processes(){return jdbc.queryForList(PROCESS_SELECT+" where "+access.processPredicate("p.id")+" order by p.code").stream().map(this::processView).toList();}
    @GetMapping("/api/v1/processes/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> process(@PathVariable UUID id){return processView(one(PROCESS_SELECT+" where p.id=?",id));}
    @PutMapping("/api/v1/processes/{id}") public Map<String,Object> updateProcess(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.ProcessCommand r,@AuthenticationPrincipal Jwt jwt){return process(configurations.saveProcess(id,r,actors.id(jwt)));}
    @PatchMapping("/api/v1/processes/{id}/status") @org.springframework.transaction.annotation.Transactional public Map<String,Object> processStatus(@PathVariable UUID id,@RequestBody StatusCommand r,@AuthenticationPrincipal Jwt jwt){if(!r.active())return deleteProcess(id,jwt);jdbc.update("update processes set active=true,version=version+1 where id=?",id);return process(id);}

    @PostMapping("/api/v1/trolleys") public Map<String,Object> createTrolley(@Valid @RequestBody CodeCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into trolleys(id,code) values (?,?)",id,norm(r.code()));return trolley(id);}
    @GetMapping("/api/v1/trolleys") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> trolleys(){return jdbc.queryForList("select id,code,status,active,version from trolleys order by code");}
    @GetMapping("/api/v1/trolleys/available") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> availableTrolleys(){return jdbc.queryForList("select id,code,status,active,version from trolleys t where active and not exists(select 1 from lot_trolley_assignments a where a.trolley_id=t.id and a.released_at is null) order by code");}
    @GetMapping("/api/v1/trolleys/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> trolley(@PathVariable UUID id){return one("select id,code,status,active,version from trolleys where id=?",id);}
    @PatchMapping("/api/v1/trolleys/{id}/status") public Map<String,Object> trolleyStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update trolleys set active=?,version=version+1 where id=?",r.active(),id);return trolley(id);}

    @PostMapping("/api/v1/storage-locations") public Map<String,Object> createStorage(@Valid @RequestBody StorageCommand r){UUID areaStation=jdbc.queryForObject("select station_id from storage_areas where id=? and active",UUID.class,r.storageAreaId());if(!areaStation.equals(r.stationId()))throw new BusinessRuleException("STORAGE_AREA_STATION_MISMATCH","Storage Area and Unit must belong to the same Station");UUID id=UUID.randomUUID();jdbc.update("insert into storage_locations(id,station_id,storage_area_id,code,name) values (?,?,?,?,?)",id,r.stationId(),r.storageAreaId(),norm(r.code()),r.name().trim());return storage(id);}
    @GetMapping("/api/v1/storage-locations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> storageLocations(){return jdbc.queryForList(STORAGE_SELECT+" where "+access.stationPredicate("sl.station_id")+" order by s.code,sl.code");}
    @GetMapping("/api/v1/storage-locations/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> storage(@PathVariable UUID id){return one(STORAGE_SELECT+" where sl.id=?",id);}
    @GetMapping("/api/v1/storage-locations/{id}/occupancies") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> occupancies(@PathVariable UUID id){return jdbc.queryForList("select so.id,so.lot_id,l.lot_number,so.stored_at,so.stored_by from storage_occupancies so join lots l on l.id=so.lot_id where so.storage_location_id=? and so.retrieved_at is null order by so.stored_at",id);}
    @PatchMapping("/api/v1/storage-locations/{id}/status") public Map<String,Object> storageStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update storage_locations set active=?,version=version+1 where id=?",r.active(),id);return storage(id);}

    @PostMapping("/api/v1/nfc-tags") public Map<String,Object> createTag(@Valid @RequestBody TagCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into nfc_tags(id,uid,name,active) values (?,?,?,false)",id,r.uid().trim().toUpperCase(java.util.Locale.ROOT),r.name().trim());return tag(id);}
    @GetMapping("/api/v1/nfc-tags") public List<Map<String,Object>> tags(@RequestParam(required=false) String status,@RequestParam(required=false) Boolean assigned){
        String sql=TAG_SELECT+" where "+access.tagPredicate("nfc_tags");java.util.List<Object> args=new java.util.ArrayList<>();
        if(status!=null){sql+=" and status=?";args.add(status.toUpperCase(java.util.Locale.ROOT));}
        if(assigned!=null){sql+=" and active=?";args.add(assigned);}
        return jdbc.queryForList(sql+" order by uid",args.toArray());
    }
    @GetMapping("/api/v1/nfc-tags/statuses") public List<Map<String,Object>> tagStatuses(){return jdbc.queryForList("select code,description,assignable from nfc_tag_statuses order by code");}
    @PutMapping("/api/v1/nfc-tags/{id}") public Map<String,Object> updateTag(@PathVariable UUID id,@Valid @RequestBody TagCommand r){
        jdbc.queryForObject("select id from nfc_tags where id=?",UUID.class,id);
        if(jdbc.queryForObject("select count(*) from nfc_assignment_history where nfc_tag_id=?",Integer.class,id)>0 && !jdbc.queryForObject("select uid from nfc_tags where id=?",String.class,id).equals(r.uid().trim().toUpperCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("A tag with assignment history cannot change its physical UID");
        jdbc.update("update nfc_tags set name=?,uid=?,version=version+1 where id=?",r.name().trim(),r.uid().trim().toUpperCase(java.util.Locale.ROOT),id);return tag(id);
    }
    @DeleteMapping("/api/v1/nfc-tags/{id}") public Map<String,Object> deleteTag(@PathVariable UUID id){nfc.lifecycle(id,"DELETED");return tag(id);}
    @DeleteMapping("/api/v1/processes/{id}") @org.springframework.transaction.annotation.Transactional public Map<String,Object> deleteProcess(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
        if(jdbc.queryForObject("select count(*) from process_executions where process_id=? and status not in ('COMPLETED','SKIPPED')",Integer.class,id)>0)throw new IllegalArgumentException("Process has unfinished production");
        for(UUID tag:jdbc.queryForList("select id from nfc_tags where active and (process_id=? or machine_id in(select id from machines where process_id=?))",UUID.class,id,id))nfc.unassign(tag,actors.id(jwt),"Process deleted");
        jdbc.update("update machines set active=false,status='INACTIVE',version=version+1 where process_id=?",id);
        jdbc.update("update processes set active=false,version=version+1 where id=?",id);return process(id);
    }
    @GetMapping("/api/v1/nfc-tags/{id}") public Map<String,Object> tag(@PathVariable UUID id){return one(TAG_SELECT+" where id=?",id);}
    @PatchMapping("/api/v1/nfc-tags/{id}/status") public Map<String,Object> tagStatus(@PathVariable UUID id,@Valid @RequestBody TagStatusCommand r){nfc.lifecycle(id,r.status());return tag(id);}

    @PostMapping("/api/v1/transport-locations") public Map<String,Object> createTransportLocation(@Valid @RequestBody TransportLocationCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into transport_locations(id,station_id,code,location_type) values (?,?,?,?)",id,r.stationId(),norm(r.code()),r.locationType());return one("select id,station_id,code,location_type,active from transport_locations where id=?",id);}
    @GetMapping("/api/v1/transport-locations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> transportLocations(){return jdbc.queryForList("select id,station_id,code,location_type,active from transport_locations where "+access.stationPredicate("station_id")+" order by code");}

    private Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new BusinessRuleException("NOT_FOUND","Requested resource was not found");return rows.getFirst();}
    private Map<String,Object> processView(Map<String,Object> row){
        Map<String,Object> result=new java.util.LinkedHashMap<>(row);
        result.put("stationNumber",row.get("station_code"));result.put("nfcMode",row.get("nfc_mode"));result.put("nfcUid",row.get("nfc_uid"));result.put("deviceCount",row.get("device_count"));
        List<Map<String,Object>> devices=jdbc.queryForList("select m.id,m.process_id,m.name,m.description,m.machine_type,m.status,(select n.uid from nfc_tags n where n.machine_id=m.id and n.active) nfc_uid from machines m where m.process_id=? and m.active order by m.name,m.id",row.get("id"));
        for(Map<String,Object> device:devices){device.put("type",device.get("machine_type"));device.put("nfcUid",device.get("nfc_uid"));device.put("processId",device.get("process_id"));device.put("stationNumber",row.get("station_code"));}
        result.put("devices",devices);return result;
    }
    private static final String TAG_SELECT="select id,uid,name,status,active assigned,asset_type,trolley_id,station_id,process_id,machine_id,storage_area_id,storage_location_id,version from nfc_tags";
    private String norm(String value){return value.trim().toUpperCase();}
    private static final String PROCESS_SELECT="select p.id,p.code,p.name,p.station_id,s.code station_code,p.target_duration_seconds,p.description,p.nfc_mode,p.quality,p.active,p.version,(select count(*) from machines m where m.process_id=p.id and m.active) device_count,(select n.uid from nfc_tags n where n.process_id=p.id and n.active) nfc_uid from processes p join stations s on s.id=p.station_id";
    private static final String STORAGE_SELECT="select sl.id,sl.station_id,s.code station_code,sl.storage_area_id,sa.code storage_area_code,sl.code,sl.name,sl.active,sl.version from storage_locations sl join stations s on s.id=sl.station_id join storage_areas sa on sa.id=sl.storage_area_id";
    public record StationCommand(@NotBlank String code,@NotBlank String name,@NotBlank String stationType,boolean stationZero,boolean qc,String nfcUid){}
    public record StorageCommand(@NotNull UUID stationId,@NotNull UUID storageAreaId,@NotBlank String code,@NotBlank String name){}
    public record TagCommand(@NotBlank @jakarta.validation.constraints.Size(max=150) @com.fasterxml.jackson.annotation.JsonAlias("tagName") String name,@NotBlank @jakarta.validation.constraints.Size(max=128) @com.fasterxml.jackson.annotation.JsonAlias({"tagId","actual"}) String uid){}
    public record TagStatusCommand(@NotBlank @jakarta.validation.constraints.Pattern(regexp="ACTIVE|DELETED") String status){}
    public record TransportLocationCommand(UUID stationId,@NotBlank String code,@NotBlank String locationType){}
    public record CodeCommand(@NotBlank String code){} public record StatusCommand(boolean active){}
}
