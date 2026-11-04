package com.yesbee.nwtrack.users;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.yesbee.nwtrack.users.ProcessAssignmentService.Command;

@RestController @RequestMapping("/api/v1/users") @Tag(name="Users")
public class UserController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ProcessAssignmentService assignments;
    public UserController(JdbcTemplate jdbc,PasswordEncoder passwords,ProcessAssignmentService assignments){this.jdbc=jdbc;this.passwords=passwords;this.assignments=assignments;}

    @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody CreateUser r){
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,username,display_name,password_hash,enabled,must_change_password) values (?,?,?,?,true,true)",id,r.username().trim(),r.displayName().trim(),passwords.encode(r.initialPassword()));
        setRole(id,r.role());assignments.replace(id,r.processAssignments());return get(id);
    }
    @GetMapping public List<Map<String,Object>> list(){return jdbc.queryForList("select id from users order by username").stream().map(row->get((UUID)row.get("id"))).toList();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){
        Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,username,display_name,enabled,must_change_password,created_at from users where id=?",id));
        result.put("role",jdbc.queryForList("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,id).stream().findFirst().orElse(null));
        result.put("processAssignments",assignments.list(id));return result;
    }
    @PutMapping("/{id}") @Transactional public Map<String,Object> update(@PathVariable UUID id,@Valid @RequestBody UpdateUser r){
        jdbc.queryForObject("select id from users where id=? for update",UUID.class,id);
        jdbc.update("update users set username=?,display_name=?,updated_at=now() where id=?",r.username().trim(),r.displayName().trim(),id);
        setRole(id,r.role());assignments.replace(id,r.processAssignments());revoke(id);return get(id);
    }
    @DeleteMapping("/{id}") @Transactional public Map<String,Object> delete(@PathVariable UUID id){return status(id,new StatusRequest(false));}
    @PutMapping("/{id}/roles") @Transactional public Map<String,Object> role(@PathVariable UUID id,@Valid @RequestBody RoleCommand r){setRole(id,r.role());revoke(id);return get(id);}
    @PutMapping("/{id}/assignments") @Transactional public Map<String,Object> assignments(@PathVariable UUID id,@Valid @RequestBody Assignments r){assignments.replace(id,r.processAssignments());return get(id);}
    @PatchMapping("/{id}/status") @Transactional public Map<String,Object> status(@PathVariable UUID id,@RequestBody StatusRequest r){jdbc.update("update users set enabled=?,updated_at=now() where id=?",r.enabled(),id);if(!r.enabled())revoke(id);return get(id);}
    @PutMapping("/{id}/password") @Transactional public Map<String,Object> resetPassword(@PathVariable UUID id,@Valid @RequestBody PasswordReset r){jdbc.update("update users set password_hash=?,must_change_password=true,updated_at=now() where id=?",passwords.encode(r.temporaryPassword()),id);revoke(id);return get(id);}
    private void revoke(UUID id){jdbc.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null",id);}
    private void setRole(UUID id,String role){UUID roleId=jdbc.queryForObject("select id from roles where code=?",UUID.class,role);jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("insert into user_roles values (?,?)",id,roleId);}
    public record CreateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String initialPassword,@NotBlank String role,List<@Valid Command> processAssignments){}
    public record UpdateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String role,@NotNull List<@Valid Command> processAssignments){}
    public record RoleCommand(@NotBlank String role){}
    public record Assignments(@NotNull List<@Valid Command> processAssignments){}
    public record StatusRequest(boolean enabled){}
    public record PasswordReset(@NotBlank String temporaryPassword){}
}
