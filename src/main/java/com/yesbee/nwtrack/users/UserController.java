package com.yesbee.nwtrack.users;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;
import com.yesbee.nwtrack.users.ProcessAssignmentService.Command;

@RestController @RequestMapping("/api/v1/users") @Tag(name="Users")
public class UserController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ProcessAssignmentService assignments;
    private final InitialAccountExpiryService expiry;
    public UserController(JdbcTemplate jdbc,PasswordEncoder passwords,ProcessAssignmentService assignments,InitialAccountExpiryService expiry){this.jdbc=jdbc;this.passwords=passwords;this.assignments=assignments;this.expiry=expiry;}

    @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody CreateUser r){
        expiry.deleteExpired();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,username,display_name,password_hash,enabled,must_change_password,initial_password_expires_at) values (?,?,?,?,?,true,now()+interval '7 days')",id,r.username().trim(),r.displayName().trim(),passwords.encode(r.initialPassword()),r.status()==null||r.status()==UserStatus.ACTIVE);
        setRole(id,r.role());assignments.replace(id,r.processAssignments());return get(id);
    }
    @GetMapping public ApiPage<Map<String,Object>> list(){return Pages.query(jdbc,"select u.id,u.username,u.display_name,u.enabled,case when u.enabled then 'ACTIVE' else 'INACTIVE' end status,r.code role from users u left join user_roles ur on ur.user_id=u.id left join roles r on r.id=ur.role_id order by u.username,u.id").map(row->get((UUID)row.get("id")));}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){
        Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,username,display_name,enabled,must_change_password,created_at,initial_password_expires_at from users where id=?",id));
        result.put("status",Boolean.TRUE.equals(result.get("enabled"))?"ACTIVE":"INACTIVE");
        result.put("role",jdbc.queryForList("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,id).stream().findFirst().orElse(null));
        result.put("processAssignments",assignments.list(id));return result;
    }
    @PutMapping("/{id}") @Transactional public Map<String,Object> update(@PathVariable UUID id,@Valid @RequestBody UpdateUser r){
        jdbc.queryForObject("select id from users where id=? for update",UUID.class,id);
        jdbc.update("update users set username=?,display_name=?,enabled=coalesce(?,enabled),updated_at=now() where id=?",r.username().trim(),r.displayName().trim(),r.status()==null?null:r.status()==UserStatus.ACTIVE,id);
        setRole(id,r.role());assignments.replace(id,r.processAssignments());revoke(id);return get(id);
    }
    @DeleteMapping("/{id}") @Transactional public Map<String,Object> delete(@PathVariable UUID id){jdbc.update("update users set enabled=false,updated_at=now() where id=?",id);revoke(id);return get(id);}
    @PutMapping("/{id}/roles") @Transactional public Map<String,Object> role(@PathVariable UUID id,@Valid @RequestBody RoleCommand r){setRole(id,r.role());revoke(id);return get(id);}
    @PutMapping("/{id}/assignments") @Transactional public Map<String,Object> assignments(@PathVariable UUID id,@Valid @RequestBody Assignments r){assignments.replace(id,r.processAssignments());return get(id);}
    @PutMapping("/{id}/password") @Transactional public Map<String,Object> resetPassword(@PathVariable UUID id,@Valid @RequestBody PasswordReset r){jdbc.update("update users set password_hash=?,must_change_password=true,updated_at=now() where id=?",passwords.encode(r.temporaryPassword()),id);revoke(id);return get(id);}
    private void revoke(UUID id){jdbc.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null",id);}
    private void setRole(UUID id,String role){UUID roleId=jdbc.queryForObject("select id from roles where code=? and active for update",UUID.class,role);jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("insert into user_roles values (?,?)",id,roleId);}
    public record CreateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String initialPassword,@NotBlank String role,List<@Valid Command> processAssignments,UserStatus status){}
    public record UpdateUser(@NotBlank String username,@NotBlank String displayName,@NotBlank String role,@NotNull List<@Valid Command> processAssignments,UserStatus status){}
    public record RoleCommand(@NotBlank String role){}
    public record Assignments(@NotNull List<@Valid Command> processAssignments){}
    public enum UserStatus { ACTIVE, INACTIVE }
    public record PasswordReset(@NotBlank String temporaryPassword){}
}
