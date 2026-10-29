package com.yesbee.nwtrack.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public DatabaseUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public UserDetails loadUserByUsername(String username) {
        List<AccountRow> accounts = jdbc.query("select id, username, password_hash, enabled from users where lower(username)=lower(?)",
                (rs, n) -> new AccountRow(rs.getObject("id").toString(), rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("enabled")), username.trim());
        if (accounts.isEmpty()) throw new UsernameNotFoundException("Invalid username or password");
        AccountRow account = accounts.getFirst();
        List<SimpleGrantedAuthority> roles = jdbc.query("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?::uuid",
                (rs, n) -> new SimpleGrantedAuthority("ROLE_" + rs.getString(1)), account.id());
        return User.withUsername(account.username()).password(account.passwordHash()).disabled(!account.enabled()).authorities(roles).build();
    }
    private record AccountRow(String id, String username, String passwordHash, boolean enabled) {}
}
