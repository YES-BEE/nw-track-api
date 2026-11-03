package com.yesbee.nwtrack.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/access-roles")
public class RoleController {
    private final JdbcTemplate jdbc; private final AccessPolicy access;
    public RoleController(JdbcTemplate jdbc,AccessPolicy access){this.jdbc=jdbc;this.access=access;}
    @GetMapping public List<Map<String,Object>> list(){
        List<Map<String,Object>> result=jdbc.queryForList("select id,code,description,pc_access,pad_access from roles order by code");
        for(Map<String,Object> row:result){UUID id=(UUID)row.remove("id");row.put("permissions",jdbc.queryForList("select permission from role_permissions where role_id=? order by permission",String.class,id));}return result;
    }
    @GetMapping("/permissions") public Set<String> permissions(){return AccessPolicy.PERMISSIONS;}
    @PutMapping("/{code}") @Transactional public List<Map<String,Object>> update(@PathVariable String code,@Valid @RequestBody RoleCommand r){
        if(!AccessPolicy.PERMISSIONS.containsAll(r.permissions()))throw new IllegalArgumentException("Unknown permission");
        if(Set.of("NW","ADMIN_IT").contains(code) && (!r.pcAccess()||!r.permissions().containsAll(Set.of("ROLES","USERS","READ"))))throw new IllegalArgumentException("NW and Admin (IT) must retain PC administration access to prevent lockout");
        UUID id=jdbc.queryForObject("select id from roles where code=? for update",UUID.class,code);
        jdbc.update("update roles set pc_access=?,pad_access=? where id=?",r.pcAccess(),r.padAccess(),id);
        jdbc.update("delete from role_permissions where role_id=?",id);
        for(String p:r.permissions())jdbc.update("insert into role_permissions values (?,?)",id,p);
        jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome) values (?,'UPDATE_ROLE','ROLE',?,'SUCCESS')",access.actor(),id);
        return list();
    }
    public record RoleCommand(boolean pcAccess,boolean padAccess,@NotNull Set<String> permissions){}
}
