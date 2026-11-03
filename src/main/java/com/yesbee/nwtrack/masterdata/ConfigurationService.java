package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ConfigurationService {
    private final JdbcTemplate jdbc; private final NfcAssetService nfc;
    public ConfigurationService(JdbcTemplate jdbc,NfcAssetService nfc){this.jdbc=jdbc;this.nfc=nfc;}
    public UUID station(String reference,UUID stationId){
        if(reference==null||reference.isBlank()){
            if(stationId==null)throw new IllegalArgumentException("stationReference is required");
            jdbc.queryForObject("select id from stations where id=? and active",UUID.class,stationId);return stationId;
        }
        String ref=reference.trim();if(ref.length()>50)throw new IllegalArgumentException("Station reference exceeds 50 characters");
        jdbc.update("insert into stations(code,name) values (?,?) on conflict (lower(code)) do nothing",ref,ref);
        UUID id=jdbc.queryForObject("select id from stations where lower(code)=lower(?) and active",UUID.class,ref);
        if(stationId!=null&&!id.equals(stationId))throw new IllegalArgumentException("stationId and stationReference do not match");return id;
    }
    @Transactional public UUID saveProcess(UUID existing,ProcessCommand r,UUID actor){
        UUID id=existing==null?UUID.randomUUID():existing;
        if(existing!=null){
            jdbc.queryForObject("select id from processes where id=? for update",UUID.class,id);
            if(jdbc.queryForObject("select count(*) from process_executions where process_id=? and status not in ('COMPLETED','SKIPPED')",Integer.class,id)>0)throw new BusinessRuleException("PROCESS_IN_USE","Cannot reconfigure a process referenced by unfinished production");
        }
        UUID station=station(r.stationReference(),r.stationId());
        List<DeviceCommand> devices=r.devices()==null?List.of():r.devices();
        if("PROCESS".equals(r.nfcMode())){
            if(r.nfcUid()==null||r.nfcUid().isBlank()||!devices.isEmpty())throw new IllegalArgumentException("PROCESS mode requires one process tag and no devices");
        }else if("DEVICES".equals(r.nfcMode())){
            if((r.nfcUid()!=null&&!r.nfcUid().isBlank())||devices.isEmpty())throw new IllegalArgumentException("DEVICES mode requires devices and no process tag");
        }else throw new IllegalArgumentException("nfcMode must be PROCESS or DEVICES");
        if(existing!=null){
            for(UUID tag:jdbc.queryForList("select id from nfc_tags where active and (process_id=? or machine_id in(select id from machines where process_id=?))",UUID.class,id,id))nfc.unassign(tag,actor,"Process configuration changed");
            jdbc.update("update machines set active=false,status='INACTIVE',version=version+1 where process_id=?",id);
            jdbc.update("update processes set code=?,name=?,description=?,station_id=?,target_duration_seconds=?,nfc_mode=?,quality=?,version=version+1 where id=?",r.code().trim(),r.name().trim(),r.description(),station,r.targetDurationSeconds(),r.nfcMode(),Boolean.TRUE.equals(r.quality()),id);
        }else jdbc.update("insert into processes(id,code,name,description,station_id,target_duration_seconds,nfc_mode,quality) values (?,?,?,?,?,?,?,?)",id,r.code().trim(),r.name().trim(),r.description(),station,r.targetDurationSeconds(),r.nfcMode(),Boolean.TRUE.equals(r.quality()));
        if("PROCESS".equals(r.nfcMode()))assignRegistered(r.nfcUid(),"PROCESS",id,actor);
        Set<UUID> ids=new HashSet<>();
        for(DeviceCommand d:devices){
            UUID device=d.id()==null?UUID.randomUUID():d.id();
            if(!ids.add(device))throw new IllegalArgumentException("Duplicate device");
            if(d.id()==null)jdbc.update("insert into machines(id,station_id,process_id,name,description,machine_type) values (?,?,?,?,?,?)",device,station,id,d.name().trim(),d.description(),d.type().trim());
            else if(jdbc.update("update machines set station_id=?,name=?,description=?,machine_type=?,active=true,status='AVAILABLE',version=version+1 where id=? and process_id=? and not exists(select 1 from process_machine_usage where machine_id=? and completed_at is null)",station,d.name().trim(),d.description(),d.type().trim(),device,id,device)!=1)throw new IllegalArgumentException("Device belongs to another process or is in use");
            assignRegistered(d.nfcUid(),"MACHINE",device,actor);
        }
        return id;
    }
    public void assignRegistered(String uid,String type,UUID asset,UUID actor){
        UUID tag=jdbc.queryForObject("select id from nfc_tags where uid=? and enabled",UUID.class,uid.trim().toUpperCase(java.util.Locale.ROOT));
        Map<String,Object> row=jdbc.queryForMap("select * from nfc_tags where id=? for update",tag);
        if(Boolean.TRUE.equals(row.get("active")))throw new BusinessRuleException("NFC_ALREADY_ASSIGNED","Unassign the NFC tag before assigning it to another asset");
        nfc.assign(tag,type,asset,actor,"Configuration saved");
    }
    public void storageNumber(UUID existing,String code){
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?::text,1))",code.trim().toUpperCase(Locale.ROOT));
        if(jdbc.queryForObject("select count(*) from storage_areas where active and upper(code)=upper(?) and id<>?",Integer.class,code.trim(),existing==null?new UUID(0,0):existing)>0)throw new BusinessRuleException("STORAGE_NUMBER_EXISTS","Storage number already exists");
    }
    public record ProcessCommand(@NotBlank @Size(max=50) String code,@NotBlank @Size(max=150) String name,@Size(max=2000) String description,String stationReference,UUID stationId,@Positive Integer targetDurationSeconds,@NotBlank String nfcMode,Boolean quality,String nfcUid,@Size(max=50) List<@Valid DeviceCommand> devices){}
    public record DeviceCommand(UUID id,@NotBlank String name,String description,@NotBlank String type,@NotBlank String nfcUid){}
}
