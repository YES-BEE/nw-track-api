package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @Tag(name="Factory master data")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class MasterDataController {
    private final JdbcTemplate jdbc;
    public MasterDataController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @PostMapping("/api/v1/stations") public Map<String,Object> createStation(@Valid @RequestBody StationCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into stations(id,code,name,station_type,active,is_station_zero,is_qc) values (?,?,?,?,true,?,?)",id,norm(r.code()),r.name().trim(),r.stationType(),r.stationZero(),r.qc());return station(id);}
    @GetMapping("/api/v1/stations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> stations(){return jdbc.queryForList("select id,code,name,station_type,active,is_station_zero,is_qc,version from stations order by code");}
    @GetMapping("/api/v1/stations/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> station(@PathVariable UUID id){return one("select id,code,name,station_type,active,is_station_zero,is_qc,version from stations where id=?",id);}
    @PutMapping("/api/v1/stations/{id}") public Map<String,Object> updateStation(@PathVariable UUID id,@Valid @RequestBody StationCommand r){jdbc.update("update stations set code=?,name=?,station_type=?,is_station_zero=?,is_qc=?,version=version+1 where id=?",norm(r.code()),r.name().trim(),r.stationType(),r.stationZero(),r.qc(),id);return station(id);}
    @PatchMapping("/api/v1/stations/{id}/status") public Map<String,Object> stationStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update stations set active=?,version=version+1 where id=?",r.active(),id);return station(id);}

    @PostMapping("/api/v1/processes") public Map<String,Object> createProcess(@Valid @RequestBody ProcessCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into processes(id,code,name,station_id,target_duration_seconds,active) values (?,?,?,?,?,true)",id,norm(r.code()),r.name().trim(),r.stationId(),r.targetDurationSeconds());return process(id);}
    @GetMapping("/api/v1/processes") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> processes(){return jdbc.queryForList(PROCESS_SELECT+" order by p.code");}
    @GetMapping("/api/v1/processes/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> process(@PathVariable UUID id){return one(PROCESS_SELECT+" where p.id=?",id);}
    @PutMapping("/api/v1/processes/{id}") public Map<String,Object> updateProcess(@PathVariable UUID id,@Valid @RequestBody ProcessCommand r){jdbc.update("update processes set code=?,name=?,station_id=?,target_duration_seconds=?,version=version+1 where id=?",norm(r.code()),r.name().trim(),r.stationId(),r.targetDurationSeconds(),id);return process(id);}
    @PatchMapping("/api/v1/processes/{id}/status") public Map<String,Object> processStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update processes set active=?,version=version+1 where id=?",r.active(),id);return process(id);}

    @PostMapping("/api/v1/trolleys") public Map<String,Object> createTrolley(@Valid @RequestBody CodeCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into trolleys(id,code) values (?,?)",id,norm(r.code()));return trolley(id);}
    @GetMapping("/api/v1/trolleys") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> trolleys(){return jdbc.queryForList("select id,code,status,active,version from trolleys order by code");}
    @GetMapping("/api/v1/trolleys/available") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> availableTrolleys(){return jdbc.queryForList("select id,code,status,active,version from trolleys t where active and not exists(select 1 from lot_trolley_assignments a where a.trolley_id=t.id and a.released_at is null) order by code");}
    @GetMapping("/api/v1/trolleys/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> trolley(@PathVariable UUID id){return one("select id,code,status,active,version from trolleys where id=?",id);}
    @PatchMapping("/api/v1/trolleys/{id}/status") public Map<String,Object> trolleyStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update trolleys set active=?,version=version+1 where id=?",r.active(),id);return trolley(id);}

    @PostMapping("/api/v1/storage-locations") public Map<String,Object> createStorage(@Valid @RequestBody StorageCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into storage_locations(id,station_id,code,name) values (?,?,?,?)",id,r.stationId(),norm(r.code()),r.name().trim());return storage(id);}
    @GetMapping("/api/v1/storage-locations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> storageLocations(){return jdbc.queryForList(STORAGE_SELECT+" order by s.code,sl.code");}
    @GetMapping("/api/v1/storage-locations/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> storage(@PathVariable UUID id){return one(STORAGE_SELECT+" where sl.id=?",id);}
    @GetMapping("/api/v1/storage-locations/{id}/occupancies") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> occupancies(@PathVariable UUID id){return jdbc.queryForList("select so.id,so.lot_id,l.lot_number,so.token_number,so.stored_at,so.stored_by from storage_occupancies so join lots l on l.id=so.lot_id where so.storage_location_id=? and so.retrieved_at is null order by so.stored_at",id);}
    @PatchMapping("/api/v1/storage-locations/{id}/status") public Map<String,Object> storageStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update storage_locations set active=?,version=version+1 where id=?",r.active(),id);return storage(id);}

    @PostMapping("/api/v1/nfc-tags") public Map<String,Object> createTag(@Valid @RequestBody NfcCommand r){validateTag(r);UUID id=UUID.randomUUID();jdbc.update("insert into nfc_tags(id,uid,asset_type,trolley_id,storage_location_id) values (?,?,?,?,?)",id,r.uid().trim(),r.assetType(),r.trolleyId(),r.storageLocationId());return tag(id);}
    @GetMapping("/api/v1/nfc-tags") public List<Map<String,Object>> tags(){return jdbc.queryForList("select id,uid,asset_type,trolley_id,storage_location_id,active,version from nfc_tags order by uid");}
    @GetMapping("/api/v1/nfc-tags/{id}") public Map<String,Object> tag(@PathVariable UUID id){return one("select id,uid,asset_type,trolley_id,storage_location_id,active,version from nfc_tags where id=?",id);}
    @PatchMapping("/api/v1/nfc-tags/{id}/status") public Map<String,Object> tagStatus(@PathVariable UUID id,@RequestBody StatusCommand r){jdbc.update("update nfc_tags set active=?,version=version+1 where id=?",r.active(),id);return tag(id);}

    @PostMapping("/api/v1/transport-locations") public Map<String,Object> createTransportLocation(@Valid @RequestBody TransportLocationCommand r){UUID id=UUID.randomUUID();jdbc.update("insert into transport_locations(id,station_id,code,location_type) values (?,?,?,?)",id,r.stationId(),norm(r.code()),r.locationType());return one("select id,station_id,code,location_type,active from transport_locations where id=?",id);}
    @GetMapping("/api/v1/transport-locations") @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> transportLocations(){return jdbc.queryForList("select id,station_id,code,location_type,active from transport_locations order by code");}

    private void validateTag(NfcCommand r){boolean valid="TROLLEY".equals(r.assetType())?r.trolleyId()!=null&&r.storageLocationId()==null:"STORAGE_LOCATION".equals(r.assetType())&&r.storageLocationId()!=null&&r.trolleyId()==null;if(!valid)throw new BusinessRuleException("INVALID_NFC_ASSET","NFC asset reference does not match asset type");}
    private Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new BusinessRuleException("NOT_FOUND","Requested resource was not found");return rows.getFirst();}
    private String norm(String value){return value.trim().toUpperCase();}
    private static final String PROCESS_SELECT="select p.id,p.code,p.name,p.station_id,s.code station_code,p.target_duration_seconds,p.active,p.version from processes p join stations s on s.id=p.station_id";
    private static final String STORAGE_SELECT="select sl.id,sl.station_id,s.code station_code,sl.code,sl.name,sl.active,sl.version from storage_locations sl join stations s on s.id=sl.station_id";
    public record StationCommand(@NotBlank String code,@NotBlank String name,@NotBlank String stationType,boolean stationZero,boolean qc){}
    public record ProcessCommand(@NotBlank String code,@NotBlank String name,@NotNull UUID stationId,@Positive Integer targetDurationSeconds){}
    public record StorageCommand(@NotNull UUID stationId,@NotBlank String code,@NotBlank String name){}
    public record NfcCommand(@NotBlank String uid,@NotBlank String assetType,UUID trolleyId,UUID storageLocationId){}
    public record TransportLocationCommand(UUID stationId,@NotBlank String code,@NotBlank String locationType){}
    public record CodeCommand(@NotBlank String code){} public record StatusCommand(boolean active){}
}
