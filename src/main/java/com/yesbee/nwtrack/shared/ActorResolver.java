package com.yesbee.nwtrack.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ActorResolver {
    private final JdbcTemplate jdbc;
    public ActorResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public UUID id(Jwt jwt) {
        UUID id = jdbc.queryForObject("select id from users where lower(username)=lower(?)", UUID.class, jwt.getSubject());
        if (id == null) throw new BusinessRuleException("ACTOR_NOT_FOUND", "Authenticated user no longer exists");
        return id;
    }
}

