package com.yesbee.nwtrack.users;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/users") @Tag(name="Users")
public class UserController {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    public UserController(JdbcTemplate jdbc,PasswordEncoder passwords){this.jdbc=jdbc;this.passwords=passwords;}
    @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody CreateUser r){
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,username,display_name,password_hash,enabled,must_change_password) values (?,?,?,?,true,true)",id,r.username().trim(),r.displayName().trim(),passwords.encode(r.initialPassword()));
        setRole(id,r.role());saveAssignments(id,r.assignments());return get(id);
    }
    @GetMapping public List<Map<String,Object>> list(){return jdbc.queryForList("select id from users order by username").stream().map(row->get((UUID)row.get("id"))).toList();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){
        Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,username,display_name,enabled,must_change_password,created_at from users where id=?",id));
        result.put("role",jdbc.queryForList("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,id).stream().findFirst().orElse(null));
        result.put("stations",jdbc.queryForList("select usa.station_id,s.code station_reference,usa.active_on_shift from user_station_assignments usa join stations s on s.id=usa.station_id where usa.user_id=?",id));
        result.put("processIds",jdbc.queryForList("select process_id from user_process_assignments where user_id=?",UUID.class,id));return result;
    }
    @PutMapping("/{id}/roles") @Transactional public Map<String,Object> role(@PathVariable UUID id,@Valid @RequestBody RoleCommand r){setRole(id,r.role());revoke(id);return get(id);}
    @PutMapping("/{id}/assignments") @Transactional public Map<String,Object> assignments(@PathVariable UUID id,@Valid @RequestBody Assignments r){saveAssignments(id,r);return get(id);}
    @PatchMapping("/{id}/status") public Map<String,Object> status(@PathVariable UUID id,@RequestBody StatusRequest r){jdbc.update("update users set enabled=?,updated_at=now() where id=?",r.enabled(),id);if(!r.enabled())revoke(id);return get(id);}
    @PutMapping("/{id}/password") @Transactional public Map<String,Object> resetPassword(@PathVariable UUID id,@Valid @RequestBody PasswordReset r){jdbc.update("update users set password_hash=?,must_change_password=true,updated_at=now() where id=?",passwords.encode(r.temporaryPassword()),id);revoke(id);return get(id);}
    private void revoke(UUID id){jdbc.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null",id);}
    private void setRole(UUID id,String role){UUID roleId=jdbc.queryForObject("select id from roles where code=?",UUID.class,role);jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("insert into user_roles values (?,?)",id,roleId);}
    private void saveAssignments(UUID id,Assignments r){
        if(r==null)r=new Assignments(List.of(),Set.of());
        Set<UUID> stations=new HashSet<>();for(StationAssignment s:r.stations()){if(!stations.add(s.stationId()))throw new IllegalArgumentException("Duplicate station assignment");}
        for(UUID process:r.processIds()){
            UUID station=jdbc.queryForObject("select station_id from processes where id=? and active",UUID.class,process);
            if(!stations.contains(station))throw new IllegalArgumentException("Every assigned process must belong to an assigned station");
        }
        jdbc.update("delete from user_process_assignments where user_id=?",id);jdbc.update("delete from user_station_assignments where user_id=?",id);
        for(StationAssignment s:r.stations())jdbc.update("insert into user_station_assignments(user_id,station_id,active_on_shift) values (?,?,?)",id,s.stationId(),s.activeOnShift());
        for(UUID process:r.processIds())jdbc.update("insert into user_process_assignments values (?,?)",id,process);
    }
    public record CreateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String initialPassword,@NotBlank String role,@Valid Assignments assignments){}
    public record RoleCommand(@NotBlank String role){}
    public record Assignments(@NotNull List<@Valid StationAssignment> stations,@NotNull Set<UUID> processIds){}
    public record StationAssignment(@NotNull UUID stationId,boolean activeOnShift){}
    public record StatusRequest(boolean enabled){}
    public record PasswordReset(@NotBlank String temporaryPassword){}
}
