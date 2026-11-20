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
    public String accountIdForUsername(String username){return jdbc.queryForObject("select id::text from users where lower(username)=lower(?)",String.class,username);}
    public String role() { return role(actor()); }
    public String role(UUID user) { return jdbc.queryForObject("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,user); }
    public boolean global(UUID user) { return Set.of("NW","ADMIN_IT","PRODUCTION_MANAGER","TOP_MANAGER").contains(role(user)); }
    public boolean global() { return global(actor()); }
    public boolean styleConfigurator(){return role().equals("STYLE_CONFIGURATOR");}
    public boolean pcStyleConfigurator(){return "PC".equals(clientType())&&styleConfigurator();}
    public boolean analyticsGlobal(){return global()||role().equals("SUPERVISOR");}
    public void channel(String username,String channel) {
        if (!Set.of("PC","PAD").contains(channel)) throw new IllegalArgumentException("clientType must be PC or PAD");
        Integer count=jdbc.queryForObject("select count(*) from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where lower(u.username)=lower(?) and u.enabled and (u.initial_password_expires_at is null or u.initial_password_expires_at>now()) and r.active and case when ?='PC' then r.pc_access else r.pad_access end",Integer.class,username,channel);
        if (count==null||count==0) throw new AccessDeniedException("Role does not allow this application channel");
    }
    public void require(String permission) {
        session();
        Jwt jwt=token();
        String client=Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC");
        channel(jwt.getSubject(),client);
        // Old role claims cannot retain access after the migration.
        if (jwt.getClaimAsStringList("roles")==null || !jwt.getClaimAsStringList("roles").contains(role())) throw new AccessDeniedException("Role changed; log in again");
        if(!channelAllows(role(),client,permission))throw new AccessDeniedException("This action is available on the NW Pad application only");
        Integer count=jdbc.queryForObject("select count(*) from role_permissions rp join user_roles ur on ur.role_id=rp.role_id where ur.user_id=? and rp.permission=?",Integer.class,actor(),permission);
        if(count==null||count==0) throw new AccessDeniedException("Missing permission: "+permission);
    }
    public String clientType(){return Objects.requireNonNullElse(token().getClaimAsString("clientType"),"PC");}
    public List<Map<String,Object>> modulesForUsername(String username){
        return jdbc.queryForList("select m.key,m.name,m.built_in from app_modules m join role_modules rm on rm.module_key=m.key join user_roles ur on ur.role_id=rm.role_id join roles r on r.id=ur.role_id join users u on u.id=ur.user_id where lower(u.username)=lower(?) and u.enabled and r.active and m.active order by m.built_in desc,m.key",username);
    }
    public String tagPredicate(String alias){
        if(pcStyleConfigurator()&&hasModule("tags"))return "true";
        if(global())return "true";
        return "(not "+alias+".active or "+alias+".process_id in(select p.id from processes p where "+processPredicate("p.id")+")"
            +" or "+alias+".machine_id in(select m.id from machines m where "+processPredicate("m.process_id")+")"
            +" or "+alias+".station_id in(select s.id from stations s where "+stationPredicate("s.id")+")"
            +" or "+alias+".storage_area_id in(select sa.id from storage_areas sa where "+stationPredicate("sa.station_id")+")"
            +" or "+alias+".storage_location_id in(select sl.id from storage_locations sl where "+stationPredicate("sl.station_id")+"))";
    }
    public void tag(UUID id){
        if(pcStyleConfigurator()&&hasModule("tags"))return;
        if(jdbc.queryForObject("select count(*) from nfc_tags n where n.id=? and "+tagPredicate("n"),Integer.class,id)==0)throw new AccessDeniedException("Tag is outside the user's assignments");
    }
    public void asset(String type,UUID id){
        switch(type.toUpperCase(Locale.ROOT)){
            case "PROCESS" -> readProcess(id);
            case "MACHINE" -> readProcess(jdbc.queryForObject("select process_id from machines where id=?",UUID.class,id));
            case "STATION" -> station(id);
            case "STORAGE_AREA" -> station(jdbc.queryForObject("select station_id from storage_areas where id=?",UUID.class,id));
            case "STORAGE_UNIT" -> station(jdbc.queryForObject("select station_id from storage_locations where id=?",UUID.class,id));
            default -> {if(!global())throw new AccessDeniedException("Asset requires global access");}
        }
    }
    public void session(){
        channel(token().getSubject(),clientType());
        String accountId=token().getClaimAsString("accountId");
        if(accountId!=null&&!accountId.equals(actor().toString()))throw new AccessDeniedException("Account changed; log in again");
        if(token().getClaimAsStringList("roles")==null||!token().getClaimAsStringList("roles").contains(role()))throw new AccessDeniedException("Role changed; log in again");
    }
    public boolean hasModule(String key){return modulesForUsername(token().getSubject()).stream().anyMatch(m->key.equals(m.get("key")));}
    public boolean ownStylesOnly(){
        if(role().equals("STYLE_CONFIGURATOR"))return true;
        return jdbc.queryForObject("select count(*) from role_modules rm join user_roles ur on ur.role_id=rm.role_id where ur.user_id=? and rm.module_key='newstyle'",Integer.class,actor())>0
            && jdbc.queryForObject("select count(*) from role_modules rm join user_roles ur on ur.role_id=rm.role_id where ur.user_id=? and rm.module_key='styles'",Integer.class,actor())==0;
    }
    public String stylePredicate(String alias){
        if(ownStylesOnly())return alias+".created_by='"+actor()+"'::uuid";
        if(global())return "true";
        return "("+alias+".created_by='"+actor()+"'::uuid or exists(select 1 from style_processes scope_sp join processes scope_p on scope_p.id=scope_sp.process_id where scope_sp.style_id="+alias+".id and "+processPredicate("scope_p.id")+"))";
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
        if(actor.equals(this.actor())&&pcStyleConfigurator()&&(hasModule("process")||hasModule("storage")))return;
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
        if(global(actor)) return;
        if(actor.equals(this.actor())&&pcStyleConfigurator()&&hasModule("process"))return;
        UUID station=jdbc.queryForObject("select station_id from processes where id=?",UUID.class,process);
        station(actor,station,false);
        Integer count=jdbc.queryForObject("select count(*) from user_process_assignments where user_id=? and process_id=?",Integer.class,actor,process);
        if(count==null||count==0) throw new AccessDeniedException("Process is outside the user's assignments");
    }
    public void execution(UUID actor,UUID execution) {
        UUID process=jdbc.queryForObject("select process_id from process_executions where id=?",UUID.class,execution);
        process(actor,process);
    }
    public String stationPredicate(String column) {
        if(pcStyleConfigurator()&&(hasModule("process")||hasModule("storage")))return "true";
        return global()?"true":column+" in (select p.station_id from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where p.active and s.active and upa.user_id='"+actor()+"'::uuid)";
    }
    public String processPredicate(String column) {
        if(pcStyleConfigurator()&&hasModule("process"))return "true";
        return global()?"true":column+" in (select upa.process_id from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where p.active and s.active and upa.user_id='"+actor()+"'::uuid)";
    }
    public String lotPredicate(String column) {
        if(ownStylesOnly())return "exists(select 1 from lots owner_l join styles owner_s on owner_s.id=owner_l.style_id where owner_l.id="+column+" and owner_s.created_by='"+actor()+"'::uuid)";
        if("PC".equals(clientType())&&hasModule("lots"))return "true";
        return global()?"true":"exists(select 1 from station_visits scope_sv where scope_sv.lot_id="+column+" and "+stationPredicate("scope_sv.station_id")+")";
    }
    public String analyticsLotPredicate(String column){return analyticsGlobal()?"true":lotPredicate(column);}
    public String analyticsStationPredicate(String column){return analyticsGlobal()?"true":stationPredicate(column);}
    public String analyticsProcessPredicate(String column){return analyticsGlobal()?"true":processPredicate(column);}
    public String analyticsStylePredicate(String alias){return analyticsGlobal()?"true":stylePredicate(alias);}
    public void analyticsLot(UUID id){
        if(jdbc.queryForObject("select count(*) from lots l where l.id=? and "+analyticsLotPredicate("l.id"),Integer.class,id)==0)
            throw new AccessDeniedException("Lot is outside the user's analytics access");
    }
    public void analyticsStation(UUID id){
        if(analyticsGlobal())return;
        station(id);
    }
    public void lot(UUID id) {
        if(jdbc.queryForObject("select count(*) from lots l where l.id=? and "+lotPredicate("l.id"),Integer.class,id)==0) throw new AccessDeniedException("Lot is outside the user's assignments");
    }
    public void style(UUID id) {
        if(jdbc.queryForObject("select count(*) from styles s where s.id=? and "+stylePredicate("s"),Integer.class,id)==0)throw new AccessDeniedException("Style is outside the user's access");
    }
}
