package com.yesbee.nwtrack.transport;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TransportRequestService {
    private final JdbcTemplate jdbc;private final TransportProvider provider;
    public TransportRequestService(JdbcTemplate jdbc,TransportProvider provider){this.jdbc=jdbc;this.provider=provider;}
    @Transactional public UUID create(UUID lotId,UUID trolleyId,UUID sourceId,UUID destinationId,int priority){String source=location(sourceId),destination=location(destinationId);UUID id=UUID.randomUUID(),correlation=UUID.randomUUID();jdbc.update("insert into transport_requests(id,lot_id,trolley_id,source_location_id,destination_location_id,provider_code,status,priority,correlation_id) values (?,?,?,?,?,?,'REQUESTED',?,?)",id,lotId,trolleyId,sourceId,destinationId,provider.code(),priority,correlation);TransportProvider.MissionResult result=provider.createMission(new TransportProvider.MissionCommand(id,lotId,trolleyId,source,destination,priority));update(id,result,null);return id;}
    @Transactional public void cancel(UUID id,String reason){RequestRow row=getForUpdate(id);TransportProvider.MissionResult result=provider.cancelMission(row.providerMissionId(),reason);update(id,result,null);}
    @Transactional public void retry(UUID id){RequestRow row=getForUpdate(id);if(!Set.of("FAILED","REJECTED","CANCELLED").contains(row.status()))throw new BusinessRuleException("TRANSPORT_RETRY_INVALID","Only failed, rejected or cancelled requests can be retried");String source=location(row.sourceId()),destination=location(row.destinationId());TransportProvider.MissionResult result=provider.createMission(new TransportProvider.MissionCommand(id,row.lotId(),row.trolleyId(),source,destination,row.priority()));update(id,result,null);}
    @Transactional public void manualTakeover(UUID id,String reason){getForUpdate(id);jdbc.update("update transport_requests set provider_code='manual',status='ACCEPTED',updated_at=now(),version=version+1 where id=?",id);event(id,"ACCEPTED",null,Map.of("manualTakeover",true,"reason",reason));}
    @Transactional public void reconcile(UUID id){RequestRow row=getForUpdate(id);if(row.providerMissionId()==null)return;update(id,provider.getMission(row.providerMissionId()),null);}
    @Transactional public void providerEvent(UUID id,String eventId,String status,Map<String,Object> detail){Integer duplicate=jdbc.queryForObject("select count(*) from transport_request_events where provider_event_id=?",Integer.class,eventId);if(duplicate!=null&&duplicate>0)return;jdbc.update("update transport_requests set status=?,updated_at=now(),version=version+1 where id=?",status,id);event(id,status,eventId,detail);}
    private void update(UUID id,TransportProvider.MissionResult result,String eventId){jdbc.update("update transport_requests set provider_mission_id=?,status=?,updated_at=now(),version=version+1 where id=?",result.providerMissionId(),result.status().name(),id);event(id,result.status().name(),eventId,Map.of("detail",result.detail()==null?"":result.detail()));}
    private void event(UUID id,String status,String providerEventId,Map<String,Object> detail){jdbc.update("insert into transport_request_events(transport_request_id,status,provider_event_id,detail) values (?,?,?,?::jsonb)",id,status,providerEventId,toJson(detail));}
    private String location(UUID id){return jdbc.queryForObject("select code from transport_locations where id=? and active",String.class,id);}
    private RequestRow getForUpdate(UUID id){RequestRow row=jdbc.queryForObject("select id,lot_id,trolley_id,source_location_id,destination_location_id,provider_mission_id,status,priority from transport_requests where id=? for update",(rs,n)->new RequestRow((UUID)rs.getObject(1),(UUID)rs.getObject(2),(UUID)rs.getObject(3),(UUID)rs.getObject(4),(UUID)rs.getObject(5),rs.getString(6),rs.getString(7),rs.getInt(8)),id);if(row==null)throw new BusinessRuleException("TRANSPORT_NOT_FOUND","Transport request not found");return row;}
    private String toJson(Map<String,Object> value){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private record RequestRow(UUID id,UUID lotId,UUID trolleyId,UUID sourceId,UUID destinationId,String providerMissionId,String status,int priority){}
}
