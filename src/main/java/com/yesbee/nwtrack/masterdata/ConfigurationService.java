package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.yesbee.nwtrack.auth.AccessPolicy;
import java.util.Locale;

@Service
public class ConfigurationService {
    private final JdbcTemplate jdbc; private final NfcAssetService nfc;private final AccessPolicy access;
    public ConfigurationService(JdbcTemplate jdbc,NfcAssetService nfc,AccessPolicy access){this.jdbc=jdbc;this.nfc=nfc;this.access=access;}
    public UUID station(String reference,UUID stationId){
        if(reference==null||reference.isBlank()){
            if(stationId==null)throw new IllegalArgumentException("stationReference is required");
            jdbc.queryForObject("select id from stations where id=? and active",UUID.class,stationId);access.station(stationId);return stationId;
        }
        String ref=reference.trim();if(ref.length()>50)throw new IllegalArgumentException("Station reference exceeds 50 characters");
        jdbc.update("insert into stations(code,name) values (?,?) on conflict (lower(code)) do nothing",ref,ref);
        UUID id=jdbc.queryForObject("select id from stations where lower(code)=lower(?) and active",UUID.class,ref);
        if(stationId!=null&&!id.equals(stationId))throw new IllegalArgumentException("stationId and stationReference do not match");access.station(id);return id;
    }
    @Transactional public UUID saveProcess(UUID existing,ProcessCommand r,UUID actor){
        UUID id=existing==null?UUID.randomUUID():existing;
        if(existing!=null){
            jdbc.queryForObject("select id from processes where id=? for update",UUID.class,id);
            if(jdbc.queryForObject("select count(*) from process_executions where process_id=? and status not in ('COMPLETED','SKIPPED')",Integer.class,id)>0)throw new BusinessRuleException("PROCESS_IN_USE","Cannot reconfigure a process referenced by unfinished production");
        }
        UUID station=station(r.stationNumber(),r.stationId());
        String mode=r.nfcMode().trim().toUpperCase(Locale.ROOT);
        String code=r.code()==null||r.code().isBlank()?(existing==null?"P-"+id:jdbc.queryForObject("select code from processes where id=?",String.class,id)):r.code().trim();
        boolean quality=r.quality()!=null?r.quality():(existing!=null&&Boolean.TRUE.equals(jdbc.queryForObject("select quality from processes where id=?",Boolean.class,id)));
        List<DeviceCommand> devices=r.devices()==null?List.of():r.devices();
        if("PROCESS".equals(mode)){
            if((r.tagId()==null&&(r.nfcUid()==null||r.nfcUid().isBlank()))||!devices.isEmpty())throw new IllegalArgumentException("PROCESS mode requires one process tag and no devices");
        }else if("DEVICES".equals(mode)){
            if(r.tagId()!=null||(r.nfcUid()!=null&&!r.nfcUid().isBlank())||devices.isEmpty())throw new IllegalArgumentException("DEVICES mode requires devices and no process tag");
        }else throw new IllegalArgumentException("nfcMode must be PROCESS or DEVICES");
        if(r.deviceCount()!=null&&r.deviceCount()!=devices.size())throw new IllegalArgumentException("deviceCount must equal the number of supplied devices");
        if(existing!=null){
            for(UUID tag:jdbc.queryForList("select id from nfc_tags where active and (process_id=? or machine_id in(select id from machines where process_id=?))",UUID.class,id,id))nfc.unassign(tag,actor,"Process configuration changed");
            jdbc.update("update machines set active=false,status='INACTIVE',version=version+1 where process_id=?",id);
            jdbc.update("update processes set code=?,name=?,description=?,station_id=?,target_duration_seconds=?,nfc_mode=?,quality=?,version=version+1 where id=?",code,r.name().trim(),r.description(),station,r.targetDurationSeconds(),mode,quality,id);
        }else jdbc.update("insert into processes(id,code,name,description,station_id,target_duration_seconds,nfc_mode,quality) values (?,?,?,?,?,?,?,?)",id,code,r.name().trim(),r.description(),station,r.targetDurationSeconds(),mode,quality);
        if("PROCESS".equals(mode))assignRegistered(registeredUid(r.nfcUid(),r.tagId()),"PROCESS",id,actor);
        Set<UUID> ids=new HashSet<>();
        for(DeviceCommand d:devices){
            UUID device=d.id()==null?UUID.randomUUID():d.id();
            if(!ids.add(device))throw new IllegalArgumentException("Duplicate device");
            String type=d.type()==null||d.type().isBlank()?(d.id()==null?"GENERAL":jdbc.queryForObject("select machine_type from machines where id=? and process_id=?",String.class,device,id)):d.type().trim();
            if(d.id()==null)jdbc.update("insert into machines(id,station_id,process_id,name,description,machine_type) values (?,?,?,?,?,?)",device,station,id,d.name().trim(),d.description(),type);
            else if(jdbc.update("update machines set station_id=?,name=?,description=?,machine_type=?,active=true,status='AVAILABLE',version=version+1 where id=? and process_id=? and not exists(select 1 from process_machine_usage where machine_id=? and completed_at is null)",station,d.name().trim(),d.description(),type,device,id,device)!=1)throw new IllegalArgumentException("Device belongs to another process or is in use");
            assignRegistered(registeredUid(d.nfcUid(),d.tagId()),"MACHINE",device,actor);
        }
        // A scoped creator can continue managing the process it just created at an authorized station.
        if(existing==null&&!access.global())jdbc.update("insert into user_process_assignments(user_id,process_id,active_on_shift) values (?,?,false) on conflict do nothing",actor,id);
        return id;
    }
    public void assignRegistered(String uid,String type,UUID asset,UUID actor){
        UUID tag=jdbc.queryForObject("select id from nfc_tags where uid=? and status='ACTIVE'",UUID.class,uid.trim().toUpperCase(Locale.ROOT));
        Map<String,Object> row=jdbc.queryForMap("select * from nfc_tags where id=? for update",tag);
        if(Boolean.TRUE.equals(row.get("active")))throw new BusinessRuleException("NFC_ALREADY_ASSIGNED","Unassign the NFC tag before assigning it to another asset");
        nfc.assign(tag,type,asset,actor,"Configuration saved");
    }
    public void storageNumber(UUID existing,String code){
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?::text,1))",code.trim().toUpperCase(Locale.ROOT));
        if(jdbc.queryForObject("select count(*) from storage_areas where active and upper(code)=upper(?) and id<>?",Integer.class,code.trim(),existing==null?new UUID(0,0):existing)>0)throw new BusinessRuleException("STORAGE_NUMBER_EXISTS","Storage number already exists");
    }
    public String registeredUid(String uid,UUID tagId){
        if(tagId==null){if(uid==null||uid.isBlank())throw new IllegalArgumentException("Select a registered NFC tag");return uid.trim().toUpperCase(Locale.ROOT);}
        String registered=jdbc.queryForObject("select uid from nfc_tags where id=? and status='ACTIVE'",String.class,tagId);
        if(uid!=null&&!uid.isBlank()&&!registered.equalsIgnoreCase(uid.trim()))throw new IllegalArgumentException("Tag ID and physical NFC UID do not match");
        return registered;
    }
    public record ProcessCommand(
            @Size(max=50) String code,
            @NotBlank @Size(max=150) String name,
            @Size(max=2000) String description,
            @JsonAlias({"stationReference","station"}) String stationNumber,
            UUID stationId,@Positive Integer targetDurationSeconds,
            @NotBlank @JsonAlias("mode") String nfcMode,
            Boolean quality,
            @JsonAlias({"processTag","nfcTagId"}) String nfcUid,
            @Size(max=50) List<@Valid DeviceCommand> devices,
            @Min(0) @Max(50) Integer deviceCount,UUID tagId){}
    public record DeviceCommand(UUID id,@NotBlank String name,String description,String type,
            @JsonAlias({"tag","nfcTagId"}) String nfcUid,UUID tagId){}
}
