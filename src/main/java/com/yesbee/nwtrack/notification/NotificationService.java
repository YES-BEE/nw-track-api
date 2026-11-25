package com.yesbee.nwtrack.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import com.yesbee.nwtrack.workflow.LotProgressSql;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final SimpMessagingTemplate messaging;
    public NotificationService(JdbcTemplate jdbc,ObjectMapper json,SimpMessagingTemplate messaging){this.jdbc=jdbc;this.json=json;this.messaging=messaging;}
    public static final String LIVE_JOIN=" left join lots l on l.id::text=n.payload->>'lotId' "+LotProgressSql.JOIN+" left join lateral (select sv.id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence limit 1) current_visit on true left join lateral (select t.id,t.code from lot_trolley_assignments a join trolleys t on t.id=a.trolley_id where a.lot_id=l.id and a.released_at is null limit 1) current_trolley on true ";
    public static final String LIVE_COLUMNS=" left join lateral (select "+LotProgressSql.columns(true)+") live on true ";
    public static final String LIVE_PAYLOAD="case when l.id is null then n.payload else n.payload || jsonb_build_object('lotStatus',l.status,'quantity',l.quantity,'routeSequence',l.current_route_sequence,'currentLocationType',l.current_location_type,'currentLocationId',l.current_location_id,'completedAt',l.completed_at,'version',l.version,'destinationStationId',route_progress.current_step_station_id,'destinationStation',route_progress.current_step_station_code,'destinationStationName',route_progress.current_step_station_name,'visitId',current_visit.id,'trolleyId',current_trolley.id,'trolleyCode',current_trolley.code) || to_jsonb(live) end";
    public Map<String,Object> currentPayload(Map<String,Object> payload){
        try {
            String body=json.writeValueAsString(payload);
            String current=jdbc.queryForObject("select ("+LIVE_PAYLOAD+")::text from (select ?::jsonb payload) n "+LIVE_JOIN+LIVE_COLUMNS,String.class,body);
            return json.readValue(current,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        } catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public void lotUpdated(UUID lotId){
        jdbc.query("select u.username,("+LIVE_PAYLOAD+")::text from notifications n join users u on u.id=n.recipient_user_id "+LIVE_JOIN+LIVE_COLUMNS+" where n.event_type='INCOMING_LOT' and n.payload->>'lotId'=?",rs->{
            try { Map<String,Object> payload=json.readValue(rs.getString(2),new com.fasterxml.jackson.core.type.TypeReference<>(){}); payload.put("eventType","LOT_UPDATED"); messaging.convertAndSendToUser(rs.getString(1),"/queue/events",payload); }
            catch(JsonProcessingException e){throw new IllegalStateException(e);}
        },lotId.toString());
        jdbc.query("select distinct n.payload->>'destinationStationId' station, ("+LIVE_PAYLOAD+")::text body from outbox_events n "+LIVE_JOIN+LIVE_COLUMNS+" where n.event_type='INCOMING_LOT' and n.payload->>'lotId'=? and n.payload->>'destinationStationId' is not null",rs->{
            try { Map<String,Object> payload=json.readValue(rs.getString(2),new com.fasterxml.jackson.core.type.TypeReference<>(){}); payload.put("eventType","LOT_UPDATED"); messaging.convertAndSend("/topic/stations/"+rs.getString(1),(Object)payload); }
            catch(JsonProcessingException e){throw new IllegalStateException(e);}
        },lotId.toString());
    }
    public void incoming(UUID stationId,Map<String,Object> payload){stationEvent(stationId,"INCOMING_LOT",payload);}
    public void stationEvent(UUID stationId,String eventType,Map<String,Object> payload){String body;try{body=json.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException(e);}jdbc.query("select distinct upa.user_id,u.username from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id join users u on u.id=upa.user_id where p.station_id=? and upa.active_on_shift and p.active and s.active and u.enabled",rs->{UUID userId=(UUID)rs.getObject(1);String username=rs.getString(2);jdbc.update("insert into notifications(id,recipient_user_id,event_type,payload) values (?,?,?,?::jsonb)",UUID.randomUUID(),userId,eventType,body);messaging.convertAndSendToUser(username,"/queue/events",(Object)payload);},stationId);messaging.convertAndSend("/topic/stations/"+stationId,(Object)payload);}
}
