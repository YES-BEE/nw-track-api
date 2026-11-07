package com.yesbee.nwtrack.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Desktop module grants, checked from the database on every request. */
@Service
public class ModuleAccessService {
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    public ModuleAccessService(JdbcTemplate jdbc,AccessPolicy access){this.jdbc=jdbc;this.access=access;}
    public List<Map<String,Object>> forUsername(String username){
        return access.modulesForUsername(username);
    }
    public boolean has(String key){return forUsername(access.token().getSubject()).stream().anyMatch(m->key.equals(m.get("key")));}
    public void requireAny(String... keys){
        access.session();
        if(Arrays.stream(keys).noneMatch(this::has))throw new AccessDeniedException("Missing module access: "+String.join(", ",keys));
    }
    public void authorize(String path,String method){
        access.session();
        if(path.startsWith("/api/v1/styles")&&access.ownStylesOnly()
            &&!(method.equals("POST")&&path.equals("/api/v1/styles"))
            &&!(method.equals("GET")&&(path.equals("/api/v1/styles")||path.matches("/api/v1/styles/[^/]+(/derived-route)?"))))
            throw new AccessDeniedException("New Style grants create and view own styles only");
        if(!"PC".equals(access.clientType())){access.require(ApiAccessConfiguration.permission(path,method));return;}
        if(path.startsWith("/api/v1/auth/"))return;
        if(path.equals("/api/v1/ui/lookups")){requireAny("styles","newstyle","process","storage","users");return;}
        if(path.equals("/api/v1/access-roles/options")){requireAny("users","roles");return;}
        if(path.startsWith("/api/v1/access-roles")||path.equals("/api/v1/modules")){requireAny("roles");return;}
        if(path.startsWith("/api/v1/modules/"))return; // controller requires the specific module
        if(path.startsWith("/api/v1/users")){requireAny("users");return;}
        if(path.startsWith("/api/v1/dashboard")||path.startsWith("/api/v1/displays")){requireAny("dashboard");return;}
        if(path.startsWith("/api/v1/reports")||path.endsWith("/progress")){requireAny("reports");return;}
        if(path.startsWith("/api/v1/nfc-tags")||path.startsWith("/api/v1/nfc-assignments")){requireAny("tags");return;}
        if(path.startsWith("/api/v1/processes")||path.startsWith("/api/v1/machines")){requireAny("process");return;}
        if(path.startsWith("/api/v1/storage-areas")||path.startsWith("/api/v1/storage-units")||path.startsWith("/api/v1/storage-locations")){requireAny("storage");return;}
        if(path.startsWith("/api/v1/styles")){
            boolean creator=access.role().equals("STYLE_CONFIGURATOR")||(!has("styles")&&has("newstyle"));
            if(creator){
                requireAny("newstyle");
                if(!(method.equals("POST")&&path.equals("/api/v1/styles"))&&!(method.equals("GET")&&(path.equals("/api/v1/styles")||path.matches("/api/v1/styles/[^/]+(/derived-route)?"))))
                    throw new AccessDeniedException("New Style grants create and view own styles only");
            }else requireAny("styles");
            return;
        }
        // Operational and unmodelled endpoints keep their PAD/action guards.
        // A module checkbox must not accidentally unlock operational APIs on PC.
        if(!Set.of("NW","ADMIN_IT").contains(access.role()))throw new AccessDeniedException("This endpoint is outside the granted desktop modules");
        access.require(ApiAccessConfiguration.permission(path,method));
    }
}
