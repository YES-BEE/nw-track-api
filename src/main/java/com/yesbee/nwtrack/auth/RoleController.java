package com.yesbee.nwtrack.auth;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;

@RestController @RequestMapping("/api/v1/access-roles")
public class RoleController {
    private final JdbcTemplate jdbc;private final AccessPolicy access;
    public RoleController(JdbcTemplate jdbc,AccessPolicy access){this.jdbc=jdbc;this.access=access;}
    private static final String ROLE_SELECT="select id,code,name,description,built_in,active,pc_access,pad_access from roles";
    @GetMapping public ApiPage<Map<String,Object>> list(){
        return Pages.query(jdbc,ROLE_SELECT+" where active order by built_in desc,name,id").map(this::view);
    }
    private Map<String,Object> view(Map<String,Object> row){
        UUID id=(UUID)row.remove("id");row.put("permissions",jdbc.queryForList("select permission from role_permissions where role_id=? order by permission",String.class,id));row.put("modules",jdbc.queryForList("select rm.module_key from role_modules rm join app_modules m on m.key=rm.module_key where rm.role_id=? and m.active order by rm.module_key",String.class,id));return row;
    }
    @GetMapping("/options") public ApiPage<Map<String,Object>> options(){return Pages.query(jdbc,"select code,name from roles where active order by name");}
    @GetMapping("/permissions") public Set<String> permissions(){return AccessPolicy.PERMISSIONS;}
    @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody CreateRole r){
        String name=r.name().trim();if(name.isBlank())throw new IllegalArgumentException("Role name is required");
        UUID id=UUID.randomUUID();String code="CUSTOM_"+id.toString().replace("-","");
        jdbc.update("insert into roles(id,code,name,description,built_in,pc_access,pad_access) values (?,?,?,?,false,?,?)",id,code,name,name,r.pcAccess()==null||r.pcAccess(),Boolean.TRUE.equals(r.padAccess()));
        save(id,code,r.modules(),r.permissions(),r.pcAccess()==null||r.pcAccess(),Boolean.TRUE.equals(r.padAccess()));audit("CREATE_ROLE",id);
        return view(jdbc.queryForMap(ROLE_SELECT+" where id=?",id));
    }
    @PutMapping("/{code}") @Transactional public ApiPage<Map<String,Object>> update(@PathVariable String code,@Valid @RequestBody RoleCommand r){
        var role=jdbc.queryForMap("select id,pc_access,pad_access from roles where code=? and active for update",code);UUID id=(UUID)role.get("id");
        if(r.modules()==null&&r.permissions()==null)throw new IllegalArgumentException("modules or permissions is required");
        save(id,code,r.modules(),r.permissions(),r.pcAccess()==null?(Boolean)role.get("pc_access"):r.pcAccess(),r.padAccess()==null?(Boolean)role.get("pad_access"):r.padAccess());audit("UPDATE_ROLE",id);return list();
    }
    @DeleteMapping("/{code}") @Transactional public Map<String,Object> delete(@PathVariable String code){
        var role=jdbc.queryForMap("select id,built_in from roles where code=? and active for update",code);UUID id=(UUID)role.get("id");
        if(Boolean.TRUE.equals(role.get("built_in")))throw new BusinessRuleException("BUILT_IN_ROLE","Built-in roles cannot be deleted");
        if(jdbc.queryForObject("select count(*) from user_roles where role_id=?",Integer.class,id)>0)throw new BusinessRuleException("ROLE_IN_USE","Reassign every user before deleting this role");
        jdbc.update("update roles set active=false where id=?",id);audit("DELETE_ROLE",id);return Map.of("code",code,"active",false);
    }
    private void save(UUID id,String code,Set<String> requestedModules,Set<String> permissions,boolean pc,boolean pad){
        if(permissions!=null&&!AccessPolicy.PERMISSIONS.containsAll(permissions))throw new IllegalArgumentException("Unknown permission");
        Set<String> modules=requestedModules==null?fromPermissions(permissions):new HashSet<>(requestedModules);
        if(code.equals("STYLE_CONFIGURATOR")){modules.add("newstyle");if(modules.contains("styles"))throw new IllegalArgumentException("Style Configurator cannot have Style Management; use another role for editing");}
        for(String key:modules)if(jdbc.queryForObject("select count(*) from app_modules where key=? and active",Integer.class,key)==0)throw new IllegalArgumentException("Unknown module: "+key);
        if(Set.of("NW","ADMIN_IT").contains(code)&&(!pc||!modules.containsAll(Set.of("roles","users"))||(permissions!=null&&!permissions.containsAll(Set.of("ROLES","USERS","READ")))))throw new IllegalArgumentException("NW and IT Admin must retain PC user and role administration access");
        jdbc.update("update roles set pc_access=?,pad_access=? where id=?",pc,pad,id);
        jdbc.update("delete from role_modules where role_id=?",id);for(String key:modules)jdbc.update("insert into role_modules values (?,?)",id,key);
        if(permissions!=null){jdbc.update("delete from role_permissions where role_id=?",id);for(String p:permissions)jdbc.update("insert into role_permissions values (?,?)",id,p);}
        else jdbc.update("insert into role_permissions values (?,'READ') on conflict do nothing",id);
    }
    private Set<String> fromPermissions(Set<String> permissions){
        Set<String> result=new HashSet<>();if(permissions==null)return result;
        if(permissions.contains("CONFIGURE"))result.addAll(Set.of("process","storage","tags"));
        if(permissions.contains("REPORTS"))result.addAll(Set.of("dashboard","reports"));
        if(permissions.contains("STYLES"))result.add("styles");if(permissions.contains("USERS"))result.add("users");if(permissions.contains("ROLES"))result.add("roles");return result;
    }
    private void audit(String action,UUID id){jdbc.update("insert into audit_log(actor_id,action,object_type,object_id,outcome) values (?,?,'ROLE',?,'SUCCESS')",access.actor(),action,id);}
    public record CreateRole(@NotBlank @Size(max=80) String name,@NotNull Set<String> modules,Boolean pcAccess,Boolean padAccess,Set<String> permissions){}
    public record RoleCommand(Boolean pcAccess,Boolean padAccess,Set<String> permissions,Set<String> modules){}
}
