package com.yesbee.nwtrack.notification;

import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;

import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController @RequestMapping("/api/v1/notifications") @Tag(name="Notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;private final ActorResolver actors;private final com.fasterxml.jackson.databind.ObjectMapper json;
    public NotificationController(JdbcTemplate jdbc,ActorResolver actors,com.fasterxml.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.actors=actors;this.json=json;}
    @GetMapping public ApiPage<Map<String,Object>> mine(@AuthenticationPrincipal Jwt jwt){return Pages.query(jdbc,"select n.id,n.event_type,"+NotificationService.LIVE_PAYLOAD+" payload,n.created_at,n.delivered_at from notifications n "+NotificationService.LIVE_JOIN+NotificationService.LIVE_COLUMNS+" where n.recipient_user_id=? order by n.created_at desc,n.id",actors.id(jwt)).map(row->{try{row.put("payload",json.readValue(row.get("payload").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));return row;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}});}
}
