package com.yesbee.nwtrack.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class InitialAccountExpiryService {
    private final JdbcTemplate jdbc;
    public InitialAccountExpiryService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Scheduled(fixedDelayString="${nwtrack.initial-account-cleanup-ms:60000}")
    @Transactional
    public void deleteExpired(){
        // Lock against password change and admin updates. Pending accounts cannot
        // create operational records; their only references are access/session data.
        var ids=jdbc.queryForList("select id from users where initial_password_expires_at<=now() for update",UUID.class);
        for(UUID id:ids){
            jdbc.update("delete from user_process_assignments where user_id=?",id);
            jdbc.update("delete from user_roles where user_id=?",id);
            jdbc.update("delete from users where id=?",id);
        }
    }
}
