package com.yesbee.nwtrack.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component
@ConditionalOnProperty(name="nwtrack.outbox.enabled",havingValue="true",matchIfMissing=true)
public class OutboxPublisher {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final NotificationService notifications;
    public OutboxPublisher(JdbcTemplate jdbc,ObjectMapper json,NotificationService notifications){this.jdbc=jdbc;this.json=json;this.notifications=notifications;}
    @Scheduled(fixedDelayString="${nwtrack.outbox-delay:1000}") @Transactional
    public void publish(){List<Event> events=jdbc.query("select id,event_type,payload::text from outbox_events where published_at is null order by created_at limit 50 for update skip locked",(rs,n)->new Event((UUID)rs.getObject(1),rs.getString(2),rs.getString(3)));for(Event event:events){try{Map<String,Object> payload=json.readValue(event.payload(),new TypeReference<>(){});if(payload.get("destinationStationId")!=null)notifications.stationEvent(UUID.fromString(payload.get("destinationStationId").toString()),event.type(),payload);jdbc.update("update outbox_events set published_at=now() where id=?",event.id());}catch(Exception ex){jdbc.update("insert into audit_log(action,object_type,object_id,outcome,reason) values ('OUTBOX_PUBLISH','OUTBOX_EVENT',?,'FAILED',?)",event.id(),ex.getMessage());}}}
    private record Event(UUID id,String type,String payload){}
}
