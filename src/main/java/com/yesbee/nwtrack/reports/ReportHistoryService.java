package com.yesbee.nwtrack.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesbee.nwtrack.auth.AccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
class ReportHistoryService {
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final ObjectMapper json;
    ReportHistoryService(JdbcTemplate jdbc,AccessPolicy access,ObjectMapper json){
        this.jdbc=jdbc;this.access=access;this.json=json;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    UUID requested(ReportModels.Prepared prepared){
        ReportModels.Request request=prepared.request();
        UUID id=UUID.randomUUID();
        Map<String,Object> filters=new LinkedHashMap<>();
        if(!request.styleIds().isEmpty())filters.put("styleIds",request.styleIds());
        if(!request.lotIds().isEmpty())filters.put("lotIds",request.lotIds());
        if(request.stationId()!=null)filters.put("stationId",request.stationId());
        if(request.processId()!=null)filters.put("processId",request.processId());
        if(request.operatorId()!=null)filters.put("operatorId",request.operatorId());
        if(request.status()!=null)filters.put("status",request.status());
        try{
            jdbc.update("""
                insert into report_export_history(id,report_category_id,requested_by,format,from_date,to_date,filters,status)
                values (?,?,?,?,?,?,?::jsonb,'REQUESTED')
                """,id,prepared.category().id(),access.actor(),request.format().name(),request.from(),request.to(),
                json.writeValueAsString(filters));
        }catch(JsonProcessingException ex){throw new IllegalStateException("Unable to record report filters",ex);}
        return id;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void completed(UUID id){
        jdbc.update("update report_export_history set status='COMPLETED',completed_at=now() where id=?",id);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void failed(UUID id,Exception failure){
        jdbc.update("""
            update report_export_history set status='FAILED',completed_at=now(),failure_message=left(?,1000)
            where id=?
            """,Objects.requireNonNullElse(failure.getMessage(),failure.getClass().getSimpleName()),id);
    }
}
