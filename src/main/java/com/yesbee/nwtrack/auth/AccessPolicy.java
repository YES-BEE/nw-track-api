package com.yesbee.nwtrack.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AccessPolicy {
    public static final Set<String> PERMISSIONS = Set.of("CONFIGURE","USERS","ROLES","STYLES","OPERATE","QC","REPORTS","OVERRIDE","TRANSPORT","READ");
    private final JdbcTemplate jdbc;
    public AccessPolicy(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Jwt token() { return (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); }
    public UUID actor() { return jdbc.queryForObject("select id from users where lower(username)=lower(?) and enabled",UUID.class,token().getSubject()); }
    public String role() { return role(actor()); }
    public String role(UUID user) { return jdbc.queryForObject("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,user); }
    public boolean global(UUID user) { return Set.of("NW","ADMIN_IT","PRODUCTION_MANAGER","TOP_MANAGER").contains(role(user)); }
    public boolean global() { return global(actor()); }
    public void channel(String username,String channel) {
        if (!Set.of("PC","PAD").contains(channel)) throw new IllegalArgumentException("clientType must be PC or PAD");
        Integer count=jdbc.queryForObject("select count(*) from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where lower(u.username)=lower(?) and u.enabled and case when ?='PC' then r.pc_access else r.pad_access end",Integer.class,username,channel);
        if (count==null||count==0) throw new AccessDeniedException("Role does not allow this application channel");
    }
    public void require(String permission) {
        Jwt jwt=token();
        String client=Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC");
        channel(jwt.getSubject(),client);
        // Old role claims cannot retain access after the migration.
        if (jwt.getClaimAsStringList("roles")==null || !jwt.getClaimAsStringList("roles").contains(role())) throw new AccessDeniedException("Role changed; log in again");
        if(!channelAllows(role(),client,permission))throw new AccessDeniedException("This action is available on the NW Pad application only");
        Integer count=jdbc.queryForObject("select count(*) from role_permissions rp join user_roles ur on ur.role_id=rp.role_id where ur.user_id=? and rp.permission=?",Integer.class,actor(),permission);
        if(count==null||count==0) throw new AccessDeniedException("Missing permission: "+permission);
    }
    private boolean channelAllows(String role,String client,String permission){
        return !client.equals("PC")||Set.of("NW","ADMIN_IT").contains(role)||!Set.of("OPERATE","QC","OVERRIDE","TRANSPORT").contains(permission);
    }
    public List<String> effectivePermissions(UUID actor,String client){
        String role=role(actor);
        return jdbc.queryForList("select rp.permission from role_permissions rp join user_roles ur on ur.role_id=rp.role_id where ur.user_id=? order by rp.permission",String.class,actor).stream().filter(p->channelAllows(role,client,p)).toList();
    }
    public void station(UUID station) { station(actor(),station,false); }
    public void station(UUID actor,UUID station,boolean operating) {
        if(global(actor)) return;
        Integer count=jdbc.queryForObject("select count(*) from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where upa.user_id=? and p.station_id=? and p.active and s.active"+(operating?" and upa.active_on_shift":""),Integer.class,actor,station);
        if(count==null||count==0) throw new AccessDeniedException("Station is outside the user's assignments");
    }
    public void process(UUID actor,UUID process) {
        readProcess(actor,process);
        if(global(actor)) return;
        Integer count=jdbc.queryForObject("select count(*) from user_process_assignments where user_id=? and process_id=? and active_on_shift",Integer.class,actor,process);
        if(count==null||count==0) throw new AccessDeniedException("User is not on shift for this process");
    }
    public void readProcess(UUID process){readProcess(actor(),process);}
    public void readProcess(UUID actor,UUID process) {
        UUID station=jdbc.queryForObject("select station_id from processes where id=?",UUID.class,process);
        station(actor,station,false);
        if(global(actor)) return;
        Integer count=jdbc.queryForObject("select count(*) from user_process_assignments where user_id=? and process_id=?",Integer.class,actor,process);
        if(count==null||count==0) throw new AccessDeniedException("Process is outside the user's assignments");
    }
    public void execution(UUID actor,UUID execution) {
        UUID process=jdbc.queryForObject("select process_id from process_executions where id=?",UUID.class,execution);
        process(actor,process);
    }
    public String stationPredicate(String column) {
        return global()?"true":column+" in (select p.station_id from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where p.active and s.active and upa.user_id='"+actor()+"'::uuid)";
    }
    public String processPredicate(String column) {
        return global()?"true":column+" in (select upa.process_id from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where p.active and s.active and upa.user_id='"+actor()+"'::uuid)";
    }
    public String lotPredicate(String column) {
        return global()?"true":"exists(select 1 from station_visits scope_sv where scope_sv.lot_id="+column+" and "+stationPredicate("scope_sv.station_id")+")";
    }
    public void lot(UUID id) {
        if(jdbc.queryForObject("select count(*) from lots l where l.id=? and "+lotPredicate("l.id"),Integer.class,id)==0) throw new AccessDeniedException("Lot is outside the user's assignments");
    }
    public void style(UUID id) {
        if(global() || Set.of("NW","ADMIN_IT","PRODUCTION_MANAGER","STATION_0").contains(role())) return;
        if(jdbc.queryForObject("select count(*) from style_processes sp join processes p on p.id=sp.process_id where sp.style_id=? and "+stationPredicate("p.station_id"),Integer.class,id)==0) throw new AccessDeniedException("Style is outside the user's assignments");
    }
}
