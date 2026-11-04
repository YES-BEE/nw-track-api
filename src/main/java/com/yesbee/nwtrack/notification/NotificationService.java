package com.yesbee.nwtrack.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final SimpMessagingTemplate messaging;
    public NotificationService(JdbcTemplate jdbc,ObjectMapper json,SimpMessagingTemplate messaging){this.jdbc=jdbc;this.json=json;this.messaging=messaging;}
    public void incoming(UUID stationId,Map<String,Object> payload){stationEvent(stationId,"INCOMING_LOT",payload);}
    public void stationEvent(UUID stationId,String eventType,Map<String,Object> payload){String body;try{body=json.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException(e);}jdbc.query("select distinct upa.user_id,u.username from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id join users u on u.id=upa.user_id where p.station_id=? and upa.active_on_shift and p.active and s.active and u.enabled",rs->{UUID userId=(UUID)rs.getObject(1);String username=rs.getString(2);jdbc.update("insert into notifications(id,recipient_user_id,event_type,payload) values (?,?,?,?::jsonb)",UUID.randomUUID(),userId,eventType,body);messaging.convertAndSendToUser(username,"/queue/events",(Object)payload);},stationId);messaging.convertAndSend("/topic/stations/"+stationId,(Object)payload);}
}
