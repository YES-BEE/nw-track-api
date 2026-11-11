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
        if(modules.has("lots"))result.put("styles",com.yesbee.nwtrack.shared.Pages.query(jdbc,"select id,style_number,description,status from styles where status in ('RELEASED','IN_PRODUCTION') order by style_number,id"));
        if(modules.has("styles")||modules.has("newstyle")||modules.has("process")||modules.has("users"))result.put("processes",com.yesbee.nwtrack.shared.Pages.query(jdbc,"select p.id,p.name,p.station_id,s.code \"stationNumber\" from processes p join stations s on s.id=p.station_id where p.active and s.active and "+(creator?"true":access.processPredicate("p.id"))+" order by s.code,p.name"));
        if(modules.has("process")||modules.has("storage"))result.put("tags",com.yesbee.nwtrack.shared.Pages.query(jdbc,"select id,name,uid,status,active assigned from nfc_tags where status='ACTIVE' and (not active or "+access.tagPredicate("nfc_tags")+") order by name"));
        return result;
    }
}
