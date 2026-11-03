package com.yesbee.nwtrack.auth;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Configuration
public class ApiAccessConfiguration implements WebMvcConfigurer {
    private final AccessPolicy access; private final JdbcTemplate jdbc;
    public ApiAccessConfiguration(AccessPolicy access,JdbcTemplate jdbc){this.access=access;this.jdbc=jdbc;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String path=org.springframework.web.util.UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
                if(path.equals("/api/v1/auth/login")||path.equals("/api/v1/auth/refresh")) return true;
                access.require(permission(path,request.getMethod()));
                String[] parts=path.substring("/api/v1/".length()).split("/");
                int index=parts[0].equals("displays")?2:1;
                String resource=parts[0].equals("displays")&&parts.length>1?parts[1]:parts[0];
                if(parts.length>index) {
                    UUID id=null;try{id=UUID.fromString(parts[index]);}catch(IllegalArgumentException ignored){}
                    if(id!=null) switch(resource) {
                        case "stations" -> access.station(id);
                        case "processes" -> access.station(jdbc.queryForObject("select station_id from processes where id=?",UUID.class,id));
                        case "machines" -> access.station(jdbc.queryForObject("select station_id from machines where id=?",UUID.class,id));
                        case "storage-areas" -> access.station(jdbc.queryForObject("select station_id from storage_areas where id=?",UUID.class,id));
                        case "storage-units","storage-locations" -> access.station(jdbc.queryForObject("select station_id from storage_locations where id=?",UUID.class,id));
                        case "station-visits" -> access.station(jdbc.queryForObject("select station_id from station_visits where id=?",UUID.class,id));
                        case "process-executions" -> access.execution(access.actor(),id);
                        case "lots" -> access.lot(id);
                        case "styles" -> access.style(id);
                        case "transport-requests" -> access.lot(jdbc.queryForObject("select lot_id from transport_requests where id=?",UUID.class,id));
                    }
                }
                if(request.getParameter("lotId")!=null) access.lot(UUID.fromString(request.getParameter("lotId")));
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
    static String permission(String path,String method) {
        boolean read=method.equals("GET");
        if(path.startsWith("/api/v1/auth/")||path.startsWith("/api/v1/notifications")||path.equals("/api/v1/factory-config")) return "READ";
        if(path.startsWith("/api/v1/access-roles"))return "ROLES";
        if(path.startsWith("/api/v1/users"))return "USERS";
        if(path.startsWith("/api/v1/nfc-tags")||path.startsWith("/api/v1/nfc-assignments"))return "CONFIGURE";
        if(path.contains("/provider-events"))return "CONFIGURE";
        if(path.startsWith("/api/v1/transport-locations"))return read?"READ":"CONFIGURE";
        if(path.startsWith("/api/v1/transport"))return read?"READ":"TRANSPORT";
        if(path.contains("/correction")||path.contains("wrong-station-override"))return "OVERRIDE";
        if(path.endsWith("/qc"))return read?"READ":"QC";
        if(path.startsWith("/api/v1/reports")||path.startsWith("/api/v1/dashboard")||path.startsWith("/api/v1/displays")||path.endsWith("/progress"))return "REPORTS";
        if(path.startsWith("/api/v1/styles"))return read?"READ":"STYLES";
        if(path.endsWith("/cancel"))return "STYLES";
        if(path.startsWith("/api/v1/sync")||path.contains("/process-executions/")||path.contains("/station-visits/")||path.endsWith("/trolley-link")||path.endsWith("/dispatch")||path.endsWith("/retrieve")||path.endsWith("/nfc/resolve"))return read?"READ":"OPERATE";
        return read?"READ":"CONFIGURE";
    }
}
