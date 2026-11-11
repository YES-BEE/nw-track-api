package com.yesbee.nwtrack.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;

@RestController @RequestMapping("/api/v1/modules")
public class ModuleController {
    private final JdbcTemplate jdbc;private final ModuleAccessService modules;private final AccessPolicy access;
    public ModuleController(JdbcTemplate jdbc,ModuleAccessService modules,AccessPolicy access){this.jdbc=jdbc;this.modules=modules;this.access=access;}
    @GetMapping public ApiPage<Map<String,Object>> list(){return Pages.query(jdbc,"select key,name,built_in,active from app_modules where active order by built_in desc,key");}
    @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody ModuleCommand r){
        String name=r.name().trim();if(name.isBlank())throw new IllegalArgumentException("Module name is required");
        String key="custom_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into app_modules(key,name) values (?,?)",key,name);
        jdbc.update("insert into audit_log(actor_id,action,object_type,outcome,detail) values (?,'CREATE_MODULE','MODULE','SUCCESS',jsonb_build_object('key',?::text))",access.actor(),key);
        return Map.of("key",key,"name",name,"built_in",false,"active",true);
    }
    @GetMapping("/{key}/content") public Map<String,Object> content(@PathVariable String key){
        modules.requireAny(key);var module=jdbc.queryForMap("select key,name,built_in from app_modules where key=? and active",key);
        if(Boolean.TRUE.equals(module.get("built_in")))throw new IllegalArgumentException("Use this built-in module's own API");
        return Map.of("key",key,"name",module.get("name"),"status","WORK_IN_PROGRESS","message","Work in Progress");
    }
    public record ModuleCommand(@NotBlank @Size(max=80) String name){}
}
