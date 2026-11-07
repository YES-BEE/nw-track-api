package com.yesbee.nwtrack.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Form dependencies do not grant access to the corresponding management module. */
@RestController @RequestMapping("/api/v1/ui")
public class UiLookupController {
    private final JdbcTemplate jdbc;private final AccessPolicy access;private final ModuleAccessService modules;
    public UiLookupController(JdbcTemplate jdbc,AccessPolicy access,ModuleAccessService modules){this.jdbc=jdbc;this.access=access;this.modules=modules;}
    @GetMapping("/lookups") public Map<String,Object> lookups(){
        Map<String,Object> result=new LinkedHashMap<>();
        boolean creator=access.role().equals("STYLE_CONFIGURATOR");
        if(modules.has("styles")||modules.has("newstyle")||modules.has("process")||modules.has("users"))result.put("processes",jdbc.queryForList("select p.id,p.name,p.station_id,s.code \"stationNumber\" from processes p join stations s on s.id=p.station_id where p.active and s.active and "+(creator?"true":access.processPredicate("p.id"))+" order by s.code,p.name"));
        if(modules.has("process")||modules.has("storage"))result.put("tags",jdbc.queryForList("select id,name,uid,status,active assigned from nfc_tags where status='ACTIVE' and (not active or "+access.tagPredicate("nfc_tags")+") order by name"));
        return result;
    }
}
