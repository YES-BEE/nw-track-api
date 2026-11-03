package com.yesbee.nwtrack.masterdata;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class NfcAssetService {
    private static final Map<String,String> COLUMNS=Map.of(
            "TROLLEY","trolley_id","STATION","station_id","PROCESS","process_id",
            "MACHINE","machine_id","STORAGE_AREA","storage_area_id","STORAGE_UNIT","storage_location_id");
    private final JdbcTemplate jdbc;
    public NfcAssetService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional
    public UUID registerAndAssign(String uid,String assetType,UUID assetId,UUID actor,String reason){
        String type=normalizeType(assetType); verifyAsset(type,assetId);
        List<UUID> existing=jdbc.query("select id from nfc_tags where uid=?",(rs,n)->(UUID)rs.getObject(1),uid.trim().toUpperCase(java.util.Locale.ROOT));
        if(!existing.isEmpty()){
            UUID tag=existing.getFirst();
            Integer active=jdbc.queryForObject("select count(*) from nfc_tags where id=? and active",Integer.class,tag);
            if(active!=null&&active>0)throw new BusinessRuleException("NFC_ALREADY_ASSIGNED","NFC UID is already active; use the reassignment operation");
            assign(tag,type,assetId,actor,reason);return tag;
        }
        UUID id=UUID.randomUUID();
        verifyAreaTag(type,assetId,id);
        jdbc.update("insert into nfc_tags(id,uid,name,asset_type,"+COLUMNS.get(type)+") values (?,?,?,?,?)",id,uid.trim().toUpperCase(java.util.Locale.ROOT),uid.trim().toUpperCase(java.util.Locale.ROOT),type,assetId);
        history(id,type,assetId,actor,reason);return id;
    }

    @Transactional
    public void assign(UUID tagId,String assetType,UUID assetId,UUID actor,String reason){
        String type=normalizeType(assetType);verifyAsset(type,assetId);
        verifyAreaTag(type,assetId,tagId);
        Map<String,Object> old=jdbc.queryForMap("select * from nfc_tags where id=? for update",tagId);
        if(!Boolean.TRUE.equals(old.get("enabled")))throw new BusinessRuleException("NFC_DISABLED","Tag is disabled");
        UUID oldAsset=assetId(old);String oldType=Objects.toString(old.get("asset_type"),null);
        jdbc.update("update nfc_assignment_history set unassigned_at=now(),unassigned_by=?,reason=coalesce(?,reason) where nfc_tag_id=? and unassigned_at is null",actor,reason,tagId);
        jdbc.update("update nfc_tags set asset_type=?,trolley_id=case when ?='TROLLEY' then ?::uuid end,station_id=case when ?='STATION' then ?::uuid end,process_id=case when ?='PROCESS' then ?::uuid end,machine_id=case when ?='MACHINE' then ?::uuid end,storage_area_id=case when ?='STORAGE_AREA' then ?::uuid end,storage_location_id=case when ?='STORAGE_UNIT' then ?::uuid end,active=true,version=version+1 where id=?",type,type,assetId,type,assetId,type,assetId,type,assetId,type,assetId,type,assetId,tagId);
        history(tagId,type,assetId,actor,reason);
        jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome,reason,detail) values (?,'NFC_REASSIGN','NFC_TAG',?,'SUCCESS',?,jsonb_build_object('oldType',?::text,'oldAssetId',?::uuid,'newType',?::text,'newAssetId',?::uuid))",actor,tagId,reason,oldType,oldAsset,type,assetId);
    }

    @Transactional
    public void deactivate(UUID tagId,UUID actor,String reason){
        jdbc.update("update nfc_tags set active=false,version=version+1 where id=?",tagId);
        jdbc.update("update nfc_assignment_history set unassigned_at=now(),unassigned_by=?,reason=? where nfc_tag_id=? and unassigned_at is null",actor,reason,tagId);
    }

    @Transactional public void unassign(UUID tagId,UUID actor,String reason){
        jdbc.queryForObject("select id from nfc_tags where id=? for update",UUID.class,tagId);
        deactivate(tagId,actor,reason);
        jdbc.update("update nfc_tags set asset_type=null,trolley_id=null,station_id=null,process_id=null,machine_id=null,storage_area_id=null,storage_location_id=null where id=?",tagId);
    }

    private void history(UUID tag,String type,UUID asset,UUID actor,String reason){jdbc.update("insert into nfc_assignment_history(nfc_tag_id,asset_type,asset_id,assigned_by,reason) values (?,?,?,?,?)",tag,type,asset,actor,reason);}
    private void verifyAreaTag(String type,UUID asset,UUID tag){
        if(!"STORAGE_AREA".equals(type))return;
        jdbc.queryForObject("select id from storage_areas where id=? and active for update",UUID.class,asset);
        if(jdbc.queryForObject("select count(*) from nfc_tags where storage_area_id=? and active and id<>?",Integer.class,asset,tag)>0)throw new BusinessRuleException("STORAGE_NFC_EXISTS","Storage area already has an NFC tag");
    }
    private String normalizeType(String value){String type=value.trim().toUpperCase(Locale.ROOT);if(!COLUMNS.containsKey(type))throw new BusinessRuleException("INVALID_NFC_ASSET","Unsupported NFC asset type");return type;}
    private void verifyAsset(String type,UUID id){String table=switch(type){case"TROLLEY"->"trolleys";case"STATION"->"stations";case"PROCESS"->"processes";case"MACHINE"->"machines";case"STORAGE_AREA"->"storage_areas";case"STORAGE_UNIT"->"storage_locations";default->throw new IllegalStateException();};Integer count=jdbc.queryForObject("select count(*) from "+table+" where id=?",Integer.class,id);if(count==null||count==0)throw new BusinessRuleException("NFC_ASSET_NOT_FOUND","NFC target asset was not found");}
    private UUID assetId(Map<String,Object> row){for(String column:COLUMNS.values())if(row.get(column)!=null)return (UUID)row.get(column);return null;}
}
