package com.yesbee.nwtrack.auth;

import com.yesbee.nwtrack.config.NwTrackProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final PasswordEncoder encoder; private final NwTrackProperties properties;
    public BootstrapAdminInitializer(JdbcTemplate jdbc, PasswordEncoder encoder, NwTrackProperties properties) { this.jdbc=jdbc; this.encoder=encoder; this.properties=properties; }
    @Override @Transactional public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("select count(*) from user_roles ur join roles r on r.id=ur.role_id where r.code='SUPER_ADMIN'", Integer.class);
        if (count != null && count > 0) return;
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users(id,username,display_name,password_hash,enabled,must_change_password) values (?,?,?,?,true,true)",
                userId, properties.security().bootstrapAdminUsername(), "Bootstrap Super Admin", encoder.encode(properties.security().bootstrapAdminPassword()));
        jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where code='SUPER_ADMIN'", userId);
    }
}
