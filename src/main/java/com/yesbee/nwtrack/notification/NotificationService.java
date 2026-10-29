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
    public void incoming(UUID stationId,Map<String,Object> payload){String body;try{body=json.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException(e);}jdbc.query("select usa.user_id,u.username from user_station_assignments usa join users u on u.id=usa.user_id where usa.station_id=? and usa.active_on_shift and u.enabled",rs->{UUID userId=(UUID)rs.getObject(1);String username=rs.getString(2);jdbc.update("insert into notifications(id,recipient_user_id,event_type,payload) values (?,?,'INCOMING_LOT',?::jsonb)",UUID.randomUUID(),userId,body);messaging.convertAndSendToUser(username,"/queue/incoming",(Object)payload);},stationId);messaging.convertAndSend("/topic/stations/"+stationId,(Object)payload);}
}
