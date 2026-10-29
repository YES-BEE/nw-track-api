package com.yesbee.nwtrack.notification;

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
    private final JdbcTemplate jdbc;private final ActorResolver actors;
    public NotificationController(JdbcTemplate jdbc,ActorResolver actors){this.jdbc=jdbc;this.actors=actors;}
    @GetMapping public List<Map<String,Object>> mine(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="50") int limit){int bounded=Math.max(1,Math.min(limit,200));return jdbc.queryForList("select id,event_type,payload,created_at,delivered_at from notifications where recipient_user_id=? order by created_at desc limit ?",actors.id(jwt),bounded);}
}
