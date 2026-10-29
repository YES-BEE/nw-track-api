package com.yesbee.nwtrack.users;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/users") @Tag(name="Users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserController {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    public UserController(JdbcTemplate jdbc, PasswordEncoder passwords) { this.jdbc=jdbc; this.passwords=passwords; }
    @PostMapping public UserView create(@Valid @RequestBody CreateUser r) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,username,display_name,password_hash,enabled,must_change_password) values (?,?,?,?,true,true)",id,r.username().trim(),r.displayName().trim(),passwords.encode(r.initialPassword()));
        replaceRoles(id,r.roles());
        if(r.stationId()!=null) jdbc.update("insert into user_station_assignments(user_id,station_id) values (?,?)",id,r.stationId());
        return get(id);
    }
    @GetMapping public List<UserView> list(){return jdbc.query(USER_SELECT+" group by u.id,usa.station_id,usa.active_on_shift order by u.username",this::map);}
    @GetMapping("/{id}") public UserView get(@PathVariable UUID id){return jdbc.queryForObject(USER_SELECT+" where u.id=? group by u.id,usa.station_id,usa.active_on_shift",this::map,id);}
    @PutMapping("/{id}/roles") public UserView roles(@PathVariable UUID id,@RequestBody @NotEmpty Set<String> roles){replaceRoles(id,roles);return get(id);}
    @PutMapping("/{id}/station-assignment") public UserView station(@PathVariable UUID id,@RequestBody StationAssignment r){jdbc.update("delete from user_station_assignments where user_id=?",id);if(r.stationId()!=null)jdbc.update("insert into user_station_assignments(user_id,station_id,active_on_shift) values (?,?,?)",id,r.stationId(),r.activeOnShift());return get(id);}
    @PatchMapping("/{id}/status") public UserView status(@PathVariable UUID id,@RequestBody StatusRequest r){jdbc.update("update users set enabled=?,updated_at=now() where id=?",r.enabled(),id);return get(id);}
    @PutMapping("/{id}/password") public UserView resetPassword(@PathVariable UUID id,@Valid @RequestBody PasswordReset r){if(jdbc.update("update users set password_hash=?,must_change_password=true,updated_at=now() where id=?",passwords.encode(r.temporaryPassword()),id)!=1)throw new IllegalArgumentException("Unknown user");jdbc.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null",id);return get(id);}
    private void replaceRoles(UUID id,Set<String> roles){jdbc.update("delete from user_roles where user_id=?",id);for(String role:roles){if(jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where code=?",id,role)!=1)throw new IllegalArgumentException("Unknown role: "+role);}}
    private UserView map(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new UserView((UUID)rs.getObject("id"),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("enabled"),rs.getBoolean("must_change_password"),rs.getString("roles"),rs.getObject("station_id",UUID.class),rs.getBoolean("active_on_shift"),rs.getObject("created_at",OffsetDateTime.class));}
    private static final String USER_SELECT="select u.id,u.username,u.display_name,u.enabled,u.must_change_password,u.created_at,string_agg(r.code,',' order by r.code) roles,usa.station_id,coalesce(usa.active_on_shift,false) active_on_shift from users u left join user_roles ur on ur.user_id=u.id left join roles r on r.id=ur.role_id left join user_station_assignments usa on usa.user_id=u.id";
    public record CreateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String initialPassword,@NotEmpty Set<String> roles,UUID stationId){}
    public record StationAssignment(UUID stationId,boolean activeOnShift){} public record StatusRequest(boolean enabled){}
    public record PasswordReset(@NotBlank String temporaryPassword){}
    public record UserView(UUID id,String username,String displayName,boolean enabled,boolean mustChangePassword,String roles,UUID stationId,boolean activeOnShift,OffsetDateTime createdAt){}
}
