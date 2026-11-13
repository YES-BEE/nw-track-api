package com.yesbee.nwtrack;

import com.yesbee.nwtrack.production.ProductionService;
import com.yesbee.nwtrack.workflow.OperationsService;
import com.yesbee.nwtrack.workflow.PlacementService;
import com.yesbee.nwtrack.qc.QcService;
import com.yesbee.nwtrack.masterdata.NfcAssetService;
import com.yesbee.nwtrack.production.StyleRevisionService;
import com.yesbee.nwtrack.workflow.ProcessMachineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PostgresWorkflowIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);registry.add("spring.datasource.password",postgres::getPassword);registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");registry.add("spring.flyway.enabled",()->"true");registry.add("nwtrack.outbox.enabled",()->"false");}
    @Autowired JdbcTemplate jdbc; @Autowired ProductionService production; @Autowired OperationsService operations; @Autowired PlacementService placements; @Autowired QcService qc; @Autowired NfcAssetService nfc; @Autowired ProcessMachineService machines; @Autowired StyleRevisionService revisions;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.yesbee.nwtrack.notification.NotificationService notifications;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    private record Reply(int status,com.fasterxml.jackson.databind.JsonNode body){}
    private Reply call(String method,String path,Object body,String token)throws Exception{
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var reply=java.net.http.HttpClient.newHttpClient().send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(),reply.body().isBlank()?json.nullNode():json.readTree(reply.body()));
    }
    private String login(String user,String channel)throws Exception{
        String hash=jdbc.queryForObject("select password_hash from users where username=?",String.class,user);
        String password=passwords.matches("ChangeMe123!",hash)?"ChangeMe123!":"ReadyForWork123!";
        Reply result=call("POST","/api/v1/auth/login",java.util.Map.of("username",user,"password",password,"clientType",channel),null);
        assertThat(result.status()).as(result.body().toString()).isEqualTo(200);String token=result.body().get("accessToken").asText();
        if(jdbc.queryForObject("select initial_password_expires_at is not null from users where username=?",Boolean.class,user))assertThat(call("POST","/api/v1/auth/change-password",java.util.Map.of("currentPassword",password,"newPassword","ReadyForWork123!"),token).status()).isEqualTo(200);
        return token;
    }

    @Test void perStyleNumbersArePaddedAndUniqueAcrossConcurrentStyles()throws Exception{
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        String suffix=UUID.randomUUID().toString();UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"NUM-S-"+suffix,"Number station");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",process,"NUM-P-"+suffix,"Number process",station);
        UUID a=production.createConfiguredStyle("NUM-A-"+suffix,null,actor,List.of(process)),b=production.createConfiguredStyle("NUM-B-"+suffix,null,actor,List.of(process));
        production.release(a);production.release(b);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->production.createLots(a,3,null,actor));
            var second=executor.submit(()->production.createLots(b,3,null,actor));
            assertThat(first.get()).hasSize(3);assertThat(second.get()).hasSize(3);
        }
        for(UUID style:List.of(a,b)){
            var numbers=jdbc.queryForList("select lot_number from lots where style_id=? order by lot_sequence",String.class,style);
            String prefix=jdbc.queryForObject("select style_number from styles where id=?",String.class,style).replaceAll("[^A-Za-z0-9]","");
            assertThat(numbers.get(0)).isEqualTo("LOT-"+prefix+"-001");
            assertThat(numbers.get(1)).endsWith("-002");assertThat(numbers.get(2)).endsWith("-003");
        }
        assertThat(jdbc.queryForObject("select count(distinct lot_number) from lots where style_id in (?,?)",Integer.class,a,b)).isEqualTo(6);
        UUID cancelled=jdbc.queryForObject("select id from lots where style_id=? and lot_sequence=3",UUID.class,a);
        production.cancelLot(cancelled,"Do not reuse the number",actor);
        UUID next=production.createLot(a,null,actor);
        assertThat(jdbc.queryForObject("select lot_number from lots where id=?",String.class,next)).endsWith("-004");
        String existing=jdbc.queryForObject("select lot_number from lots where id=?",String.class,next);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("insert into lots(id,style_id,lot_number,status) values (?,?,?,'READY')",UUID.randomUUID(),b,existing)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("insert into lots(id,style_id,lot_number,lot_sequence,status) values (?,?,?,1,'READY')",UUID.randomUUID(),a,"DUP-"+suffix)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void styleBasedNumbersSanitizeAndContinueWithoutRenamingExistingLots()throws Exception{
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        String suffix=UUID.randomUUID().toString().replace("-","");
        UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,'Prefix station')",station,"PREFIX-S-"+suffix);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,'Prefix process',?)",process,"PREFIX-P-"+suffix,station);
        UUID style=production.createConfiguredStyle("AB / 12-"+suffix,null,actor,List.of(process));production.release(style);
        UUID old=production.createLot(style,null,actor);
        jdbc.update("update lots set lot_number='LOT-1700000000000-149',lot_sequence=149 where id=?",old);
        jdbc.update("update styles set last_lot_sequence=149 where id=?",style);
        UUID next=production.createLot(style,null,actor);
        assertThat(jdbc.queryForObject("select lot_number from lots where id=?",String.class,next)).isEqualTo("LOT-AB12"+suffix+"-150");
        assertThat(jdbc.queryForObject("select lot_number from lots where id=?",String.class,old)).isEqualTo("LOT-1700000000000-149");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->production.createStyle("AB12"+suffix,null,actor))
            .isInstanceOf(com.yesbee.nwtrack.shared.BusinessRuleException.class).hasMessageContaining("same lot prefix");
        assertThat(jdbc.queryForObject("select count(*) from styles where style_number=?",Integer.class,"AB12"+suffix)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->production.createStyle(" / - ",null,actor))
            .isInstanceOf(com.yesbee.nwtrack.shared.BusinessRuleException.class).hasMessageContaining("letter or digit");
        // Different styles that sanitize identically cannot both reserve a prefix concurrently.
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->{try{production.createStyle("RACE-"+suffix,null,actor);return true;}catch(com.yesbee.nwtrack.shared.BusinessRuleException ex){return false;}});
            var b=executor.submit(()->{try{production.createStyle("RACE /"+suffix,null,actor);return true;}catch(com.yesbee.nwtrack.shared.BusinessRuleException ex){return false;}});
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
    }

    @Test void dashboardAndReportFiltersCombineAndOfferScopedSearchableOptions()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString();
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        UUID s1=UUID.randomUUID(),s2=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,'First filter station'),(?,?,'Second filter station')",s1,"FILTER-S1-"+suffix,s2,"FILTER-S2-"+suffix);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,'First filter process',?),(?,?,'Second filter process',?)",p1,"FILTER-P1-"+suffix,s1,p2,"FILTER-P2-"+suffix,s2);
        UUID style=production.createConfiguredStyle("FILTER-"+suffix,null,actor,List.of(p1,p2));production.release(style);
        var ids=production.createLots(style,2,null,actor);
        String filters="styleId="+style;
        for(String path:List.of("/api/v1/dashboard/lots","/api/v1/displays/lots","/api/v1/reports/lot-progress")){
            var page=call("GET",path+"?"+filters+"&size=1",null,admin);
            assertThat(page.status()).as(page.body().toString()).isEqualTo(200);
            assertThat(page.body().get("totalElements").asInt()).isEqualTo(2);
            assertThat(page.body().get("items").size()).isEqualTo(1);
            assertThat(call("GET",path+"?"+filters+"&stationId="+s2,null,admin).body().get("totalElements").asInt()).isZero();
            var any=call("GET",path+"?"+filters+"&stationId="+s2+"&processId="+p2+"&routeScope=ANY",null,admin);
            assertThat(any.status()).as(any.body().toString()).isEqualTo(200);
            assertThat(any.body().get("totalElements").asInt()).isEqualTo(2);
            assertThat(call("GET",path+"?"+filters+"&stationId="+s1+"&processId="+p2+"&routeScope=ANY",null,admin).body().get("totalElements").asInt()).isZero();
            assertThat(call("GET",path+"?"+filters+"&stationId="+s1+"&processId="+p1+"&lotId="+ids.get(0),null,admin).body().get("totalElements").asInt()).isEqualTo(1);
            assertThat(call("GET",path+"?"+filters+"&status=COMPLETED",null,admin).body().get("totalElements").asInt()).isZero();
            assertThat(call("GET",path+"?"+filters+"&routeScope=INVALID",null,admin).status()).isEqualTo(400);
        }
        for(String module:List.of("dashboard","reports")){
            String base="/api/v1/"+module+"/filter-options/";
            assertThat(call("GET",base+"lots?"+filters+"&size=1",null,admin).body().get("totalElements").asInt()).isEqualTo(2);
            assertThat(call("GET",base+"styles?"+filters,null,admin).body().get("items").get(0).get("id").asText()).isEqualTo(style.toString());
            assertThat(call("GET",base+"stations?"+filters,null,admin).body().get("totalElements").asInt()).isEqualTo(1);
            assertThat(call("GET",base+"stations?"+filters+"&routeScope=ANY",null,admin).body().get("totalElements").asInt()).isEqualTo(2);
            assertThat(call("GET",base+"processes?"+filters+"&routeScope=ANY",null,admin).body().get("totalElements").asInt()).isEqualTo(2);
            assertThat(call("GET",base+"processes?"+filters+"&routeScope=ANY&search=Second",null,admin).body().get("totalElements").asInt()).isEqualTo(1);
            assertThat(call("GET",base+"processes?"+filters+"&routeScope=ANY&stationId="+s2,null,admin).body().get("items").get(0).get("id").asText()).isEqualTo(p2.toString());
            assertThat(call("GET",base+"processes?"+filters+"&routeScope=ANY&stationId="+s2,null,admin).body().get("totalElements").asInt()).isEqualTo(1);
            assertThat(call("GET",base+"stations?"+filters+"&routeScope=ANY&processId="+p1,null,admin).body().get("totalElements").asInt()).isEqualTo(1);
            assertThat(call("GET",base+"invalid",null,admin).status()).isEqualTo(400);
        }
        String name="filter-reader-"+suffix;
        assertThat(call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role","SUPERVISOR"),admin).status()).isEqualTo(200);
        String token=login(name,"PC");
        for(String module:List.of("dashboard","reports"))for(String type:List.of("lots","styles","stations","processes")){
            var page=call("GET","/api/v1/"+module+"/filter-options/"+type+"?routeScope=ANY",null,token);
            assertThat(page.status()).as(page.body().toString()).isEqualTo(200);
            assertThat(page.body().get("totalElements").asInt()).isZero();
        }
        var before=call("GET","/api/v1/dashboard",null,admin).body();
        var after=call("GET","/api/v1/dashboard?"+filters+"&stationId="+s2,null,admin).body();
        assertThat(after.get("metrics")).isEqualTo(before.get("metrics"));
    }

    @Test void numberMigrationPreservesHistoricalNumbersAndContinuesEachStyle(){
        String schema="number_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("8").load().migrate();
        var legacy=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword()));
        UUID style=UUID.randomUUID(),other=UUID.randomUUID(),lot=UUID.randomUUID();
        legacy.update("insert into styles(id,style_number,status) values (?,'OLD','RELEASED'),(?,'NEW','DRAFT')",style,other);
        legacy.update("insert into lots(id,style_id,lot_number,quantity,status) values (?,?,?,5,'READY')",lot,style,"LOT-1700000000000-47");
        legacy.update("insert into styles(id,style_number,status) values (?,'O L D','DRAFT')",UUID.randomUUID());
        UUID stale=UUID.randomUUID(),inactive=UUID.randomUUID(),occupied=UUID.randomUUID();
        legacy.update("insert into trolleys(id,code,status,active) values (?,'STALE','IN_USE',true),(?,'INACTIVE','IN_USE',false),(?,'OCCUPIED','IN_USE',true)",stale,inactive,occupied);
        legacy.update("insert into lot_trolley_assignments(id,lot_id,trolley_id) values (?,?,?)",UUID.randomUUID(),lot,occupied);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(legacy.queryForObject("select status from trolleys where id=?",String.class,stale)).isEqualTo("AVAILABLE");
        assertThat(legacy.queryForObject("select status from trolleys where id=?",String.class,inactive)).isEqualTo("INACTIVE");
        assertThat(legacy.queryForObject("select status from trolleys where id=?",String.class,occupied)).isEqualTo("IN_USE");
        assertThat(legacy.queryForObject("select lot_number from lots where id=?",String.class,lot)).isEqualTo("LOT-1700000000000-47");
        assertThat(legacy.queryForObject("select style_id from style_lot_number_prefixes where prefix='OLD'",UUID.class)).isEqualTo(style);
        assertThat(legacy.queryForObject("select last_lot_sequence from styles where id=?",Long.class,style)).isEqualTo(47L);
        assertThat(legacy.queryForObject("update styles set last_lot_sequence=last_lot_sequence+1 where id=? returning last_lot_sequence",Long.class,style)).isEqualTo(48L);
        assertThat(legacy.queryForObject("select last_lot_sequence from styles where id=?",Long.class,other)).isZero();
    }

    @Test void dashboardAndFactoryDisplaysProvideTheScreenMetricsAndReadableLotRows()throws Exception{
        String token=login("admin","PC"),suffix=UUID.randomUUID().toString(),styleNumber="DASH-"+suffix;
        Reply baseline=call("GET","/api/v1/dashboard",null,token);assertThat(baseline.status()).isEqualTo(200);
        var before=baseline.body().get("metrics");UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"DASH-S-"+suffix,"Readable station");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",process,"DASH-P-"+suffix,"Readable process",station);
        UUID style=production.createConfiguredStyle(styleNumber,null,actor,List.of(process),7,12);production.release(style);
        var ids=jdbc.queryForList("select id from lots where style_id=? order by lot_sequence",UUID.class,style);
        String[] states={"READY","COMPLETED","CANCELLED","DISPATCHED","IN_PROCESS","IN_STORAGE","AT_STATION"};
        for(int i=0;i<states.length;i++){
            jdbc.update("update lots set status=? where id=?",states[i],ids.get(i));
            if(i>0)jdbc.update("update station_visits set status='RECEIVED' where lot_id=?",ids.get(i));
        }
        jdbc.update("update lots set completed_at=now(),current_location_type='COMPLETED' where id=?",ids.get(1));
        jdbc.update("update process_executions set status='PAUSED' where station_visit_id in(select id from station_visits where lot_id=?)",ids.get(4));
        jdbc.update("update process_executions set status='BLOCKED' where station_visit_id in(select id from station_visits where lot_id=?)",ids.get(6));
        jdbc.update("update lots set current_location_type='PROCESS_AREA',current_location_id=? where id=?",station,ids.get(4));
        UUID area=UUID.randomUUID(),unit=UUID.randomUUID(),trolley=UUID.randomUUID(),freeTrolley=UUID.randomUUID();
        jdbc.update("insert into storage_areas(id,station_id,code,name) values (?,?,?,'Display store')",area,station,"DASH-A-"+suffix);
        jdbc.update("insert into storage_locations(id,station_id,storage_area_id,code,name) values (?,?,?,?,'Readable storage')",unit,station,area,"DASH-U-"+suffix);
        jdbc.update("insert into storage_occupancies(id,lot_id,station_id,storage_location_id,stored_at) values (?,?,?,?,now())",UUID.randomUUID(),ids.get(5),station,unit);
        jdbc.update("update lots set current_location_type='STORAGE',current_location_id=? where id=?",unit,ids.get(5));
        jdbc.update("insert into trolleys(id,code,status) values (?,?,'IN_USE'),(?,?,'AVAILABLE')",trolley,"DASH-T-"+suffix,freeTrolley,"DASH-F-"+suffix);
        jdbc.update("insert into lot_trolley_assignments(id,lot_id,trolley_id) values (?,?,?)",UUID.randomUUID(),ids.get(3),trolley);
        jdbc.update("update lots set current_location_type='TROLLEY',current_location_id=? where id=?",trolley,ids.get(3));
        Reply dashboard=call("GET","/api/v1/dashboard?size=1&search=DASH-S-"+suffix,null,token);
        assertThat(dashboard.status()).as(dashboard.body().toString()).isEqualTo(200);
        var metrics=dashboard.body().get("metrics");
        assertThat(metrics.get("totalLots").asLong()).isEqualTo(before.get("totalLots").asLong()+7);
        assertThat(metrics.get("openLots").asLong()).isEqualTo(before.get("openLots").asLong()+5);
        assertThat(metrics.get("completedLots").asLong()).isEqualTo(before.get("completedLots").asLong()+1);
        assertThat(metrics.get("stylesWithLots").asLong()).isEqualTo(before.get("stylesWithLots").asLong()+1);
        assertThat(metrics.get("storedLots").asLong()).isEqualTo(before.get("storedLots").asLong()+1);
        assertThat(metrics.get("availableTrolleys").asLong()).isEqualTo(before.get("availableTrolleys").asLong()+1);
        assertThat(metrics.get("inTransitLots").asLong()).isEqualTo(before.get("inTransitLots").asLong()+1);
        assertThat(metrics.get("readyToStartLots").asLong()).isEqualTo(before.get("readyToStartLots").asLong()+1);
        assertThat(metrics.get("totalStationLotVisits").asLong()).isEqualTo(before.get("totalStationLotVisits").asLong()+4);
        assertThat(dashboard.body().get("refreshIntervalSeconds").asInt()).isEqualTo(5);
        java.time.OffsetDateTime.parse(dashboard.body().get("snapshotAt").asText());
        var row=dashboard.body().get("stations").get("items").get(0);assertThat(row.get("wip").asInt()).isEqualTo(4);
        double expected=Math.round(40000.0/metrics.get("totalStationLotVisits").asLong())/100.0;
        assertThat(row.get("share_percent").asDouble()).isEqualTo(expected);
        assertThat(call("GET","/api/v1/displays/factory-summary",null,token).body().get("metrics")).isEqualTo(metrics);
        Reply displayLots=call("GET","/api/v1/displays/lots?size=2&search="+styleNumber,null,token);
        assertThat(displayLots.status()).as(displayLots.body().toString()).isEqualTo(200);
        assertThat(displayLots.body().get("totalElements").asInt()).isEqualTo(7);assertThat(displayLots.body().get("items").size()).isEqualTo(2);
        Reply paused=call("GET","/api/v1/dashboard/lots/"+ids.get(4),null,token);
        assertThat(paused.body().get("current_process").asText()).isEqualTo("Readable process");
        assertThat(paused.body().get("current_process_status").asText()).isEqualTo("PAUSED");
        assertThat(paused.body().get("location_label").asText()).isEqualTo("Readable station");
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(6),null,token).body().get("current_process_status").asText()).isEqualTo("BLOCKED");
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(0),null,token).body().get("current_process_status").asText()).isEqualTo("PENDING");
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(5),null,token).body().get("location_label").asText()).isEqualTo("Readable storage");
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(3),null,token).body().get("location_label").asText()).isEqualTo("DASH-T-"+suffix);
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(1),null,token).body().get("completed_at").isNull()).isFalse();
        String viewer="dashboard-viewer-"+suffix;
        call("POST","/api/v1/users",java.util.Map.of("username",viewer,"displayName",viewer,"initialPassword","ChangeMe123!","role","TOP_MANAGER"),token);
        assertThat(call("GET","/api/v1/dashboard/lots/"+ids.get(0),null,login(viewer,"PC")).status()).isEqualTo(200);
        String scopedName="scoped-display-"+suffix;
        assertThat(call("POST","/api/v1/users",java.util.Map.of("username",scopedName,"displayName",scopedName,"initialPassword","ChangeMe123!","role","SUPERVISOR"),token).status()).isEqualTo(200);
        String scopedToken=login(scopedName,"PC");
        assertThat(call("GET","/api/v1/displays/lots",null,scopedToken).body().get("totalElements").asInt()).isZero();
        assertThat(call("GET","/api/v1/dashboard/lots/"+ids.get(0),null,scopedToken).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/displays/lots/"+ids.get(0),null,scopedToken).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/displays/factory-summary",null,scopedToken).body().get("metrics").get("totalLots").asInt()).isZero();
    }

    @Test void cancellationReleasesTrolleyForReuseAndCannotReleaseItsNextAssignment()throws Exception{
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        String suffix=UUID.randomUUID().toString();UUID station=UUID.randomUUID(),process=UUID.randomUUID(),trolley=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,'Cancel station')",station,"CANCEL-S-"+suffix);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,'Cancel process',?)",process,"CANCEL-P-"+suffix,station);
        UUID style=production.createConfiguredStyle("CANCEL-"+suffix,null,actor,List.of(process));production.release(style);
        UUID first=production.createLot(style,null,actor),next=production.createLot(style,null,actor);
        jdbc.update("insert into trolleys(id,code) values (?,?)",trolley,"CANCEL-T-"+suffix);
        operations.linkTrolley(first,trolley,actor);production.cancelLot(first,"Cancelled",actor);
        assertThat(jdbc.queryForObject("select status from trolleys where id=?",String.class,trolley)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from lot_trolley_assignments where lot_id=? and released_at is null",Integer.class,first)).isZero();
        operations.linkTrolley(next,trolley,actor);
        production.cancelLot(first,"Repeated request",actor);
        assertThat(jdbc.queryForObject("select status from trolleys where id=?",String.class,trolley)).isEqualTo("IN_USE");
        assertThat(jdbc.queryForObject("select lot_id from lot_trolley_assignments where trolley_id=? and released_at is null",UUID.class,trolley)).isEqualTo(next);
        jdbc.update("update trolleys set active=false where id=?",trolley);
        production.cancelLot(next,"Inactive trolley",actor);
        assertThat(jdbc.queryForObject("select status from trolleys where id=?",String.class,trolley)).isEqualTo("INACTIVE");
    }

    @Test void progressReportsArePagedAndDashboardProgressUsesOnlyEffectiveProcesses()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString();
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class),station=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,'Progress station')",station,"PROG-S-"+suffix);
        List<UUID> processes=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        for(int i=0;i<processes.size();i++)jdbc.update("insert into processes(id,code,name,station_id) values (?,?,'Progress process',?)",processes.get(i),"PROG-P-"+i+suffix,station);
        UUID style=production.createConfiguredStyle("PROG-"+suffix,null,actor,processes);production.release(style);
        UUID lot=production.createLot(style,null,actor),untouched=production.createLot(style,null,actor);
        jdbc.update("update process_executions set status='COMPLETED' where process_id=? and station_visit_id in(select id from station_visits where lot_id=?)",processes.get(0),lot);
        jdbc.update("update process_executions set status='SKIPPED',reason='Intentional skip' where process_id=? and station_visit_id in(select id from station_visits where lot_id=?)",processes.get(1),lot);
        jdbc.update("update process_executions set status='SKIPPED',reason='Superseded by Manager route revision: test' where process_id=? and station_visit_id in(select id from station_visits where lot_id=?)",processes.get(2),lot);
        var detail=call("GET","/api/v1/dashboard/lots/"+lot,null,admin);
        assertThat(detail.status()).as(detail.body().toString()).isEqualTo(200);
        assertThat(detail.body().get("progress_percent").asDouble()).isEqualTo(66.67);
        assertThat(detail.body().get("completed_processes").asInt()).isEqualTo(2);
        assertThat(detail.body().get("total_processes").asInt()).isEqualTo(3);
        var report=call("GET","/api/v1/reports/lot-progress?search=PROG-"+suffix+"&size=1",null,admin);
        assertThat(report.status()).as(report.body().toString()).isEqualTo(200);
        assertThat(report.body().get("totalElements").asInt()).isEqualTo(2);
        assertThat(report.body().get("items").size()).isEqualTo(1);
        var page2=call("GET","/api/v1/reports/lot-progress?search=PROG-"+suffix+"&size=1&page=1",null,admin);
        assertThat(page2.body().get("items").get(0).get("id")).isNotEqualTo(report.body().get("items").get(0).get("id"));
        var styles=call("GET","/api/v1/reports/style-progress?search=PROG-"+suffix,null,admin);
        assertThat(styles.status()).as(styles.body().toString()).isEqualTo(200);
        assertThat(styles.body().get("totalElements").asInt()).isEqualTo(1);
        assertThat(styles.body().get("items").get(0).get("progress_percent").asDouble()).isEqualTo(33.34);
        production.createConfiguredStyle("EMPTY-"+suffix,null,actor,processes);
        var empty=call("GET","/api/v1/reports/style-progress?search=EMPTY-"+suffix,null,admin).body().get("items").get(0);
        assertThat(empty.get("lot_count").asInt()).isZero();
        assertThat(empty.get("progress_percent").asInt()).isZero();
        assertThat(call("GET","/api/v1/displays/lots/"+untouched,null,admin).body().get("progress_percent").asInt()).isZero();
        jdbc.update("update lots set status='COMPLETED',completed_at=now() where id=?",untouched);
        assertThat(call("GET","/api/v1/displays/lots/"+untouched,null,admin).body().get("progress_percent").asInt()).isEqualTo(100);
        String scopedName="progress-reader-"+suffix;
        assertThat(call("POST","/api/v1/users",java.util.Map.of("username",scopedName,"displayName",scopedName,"initialPassword","ChangeMe123!","role","SUPERVISOR"),admin).status()).isEqualTo(200);
        String scoped=login(scopedName,"PC");
        for(String path:List.of("/api/v1/reports/style-progress","/api/v1/reports/lot-progress")){
            var restricted=call("GET",path,null,scoped);
            assertThat(restricted.status()).as(restricted.body().toString()).isEqualTo(200);
            assertThat(restricted.body().get("totalElements").asInt()).isZero();
        }
    }

    @Test void productionTrendUsesFactoryDayAndHistoricalWorkInProgress()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString();
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class),station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,'Trend station')",station,"TREND-S-"+suffix);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,'Trend process',?)",process,"TREND-P-"+suffix,station);
        UUID style=production.createConfiguredStyle("TREND-"+suffix,null,actor,List.of(process));production.release(style);
        var ids=production.createLots(style,4,null,actor);
        var now=jdbc.queryForObject("select now()",java.time.OffsetDateTime.class);
        var start=now.atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
        // Three lots entered yesterday; one stays open, one completes and one cancels today.
        for(UUID lot:ids.subList(0,3))jdbc.update("update station_visits set dispatched_at=? where lot_id=?",start.minusHours(1),lot);
        jdbc.update("update lots set status='IN_PROCESS' where id=?",ids.get(0));
        var terminal=start.plusHours(1).plusSeconds(1).isBefore(now)?start.plusHours(1).plusSeconds(1):start.plusSeconds(1);
        jdbc.update("update lots set status='COMPLETED',completed_at=? where id=?",terminal,ids.get(1));
        jdbc.update("update lots set status='CANCELLED',cancelled_at=? where id=?",terminal,ids.get(2));
        String name="trend-reader-"+suffix;
        assertThat(call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role","SUPERVISOR","processAssignments",List.of(java.util.Map.of("processId",process,"activeOnShift",true))),admin).status()).isEqualTo(200);
        String token=login(name,"PC");
        var response=call("GET","/api/v1/dashboard?size=1&search=nonexistent",null,token);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(200);
        var trend=response.body().get("productionTrend");
        assertThat(trend.get("timezone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(trend.get("date").asText()).isEqualTo(start.toLocalDate().toString());
        var points=trend.get("points");assertThat(points.size()).isBetween(1,24);
        int totalCompleted=0;
        for(var point:points){
            totalCompleted+=point.get("completed").asInt();
            var end=java.time.OffsetDateTime.parse(point.get("bucket_end").asText());
            assertThat(point.get("in_progress").asInt()).isEqualTo(end.isBefore(terminal)?3:1);
        }
        assertThat(totalCompleted).isEqualTo(1);
        int completedHour=(int)java.time.Duration.between(start,terminal).toHours();
        assertThat(points.get(completedHour).get("completed").asInt()).isEqualTo(1);
        assertThat(java.time.OffsetDateTime.parse(points.get(0).get("bucket_start").asText()).toInstant()).isEqualTo(start.toInstant());
        var display=call("GET","/api/v1/displays/factory-summary",null,token).body().get("productionTrend");
        assertThat(display.get("points").size()).isEqualTo(points.size());
        assertThat(display.get("points").get(completedHour).get("completed").asInt()).isEqualTo(1);
    }

    @Test void userStatusIsManagedThroughCreateAndUpdateOnly()throws Exception{
        String admin=login("admin","PC"),name="status-"+UUID.randomUUID();
        Reply created=call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName","Status test","initialPassword","ChangeMe123!","role","PRODUCTION_MANAGER"),admin);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("status").asText()).isEqualTo("ACTIVE");
        String path="/api/v1/users/"+created.body().get("id").asText();
        var update=new java.util.HashMap<String,Object>(java.util.Map.of("username",name,"displayName","Status test","role","PRODUCTION_MANAGER","processAssignments",List.of(),"status","INACTIVE"));
        Reply inactive=call("PUT",path,update,admin);
        assertThat(inactive.status()).isEqualTo(200);
        assertThat(inactive.body().get("status").asText()).isEqualTo("INACTIVE");
        assertThat(inactive.body().get("enabled").asBoolean()).isFalse();
        update.remove("status");
        assertThat(call("PUT",path,update,admin).body().get("status").asText()).isEqualTo("INACTIVE");
        update.put("status","ACTIVE");
        assertThat(call("PUT",path,update,admin).body().get("status").asText()).isEqualTo("ACTIVE");
        update.put("status","UNKNOWN");
        assertThat(call("PUT",path,update,admin).status()).isEqualTo(400);
        assertThat(call("PATCH",path+"/status",java.util.Map.of("enabled",false),admin).status()).isEqualTo(404);
    }

    @Test void pendingAccountsExpireAndUsernameCanBeRecreatedWithoutReusingOldTokens()throws Exception{
        String admin=login("admin","PC"),name="expiry-"+UUID.randomUUID();
        var command=new java.util.HashMap<String,Object>(java.util.Map.of("username",name,"displayName","Expiry test","initialPassword","ChangeMe123!","role","PRODUCTION_MANAGER","status","INACTIVE"));
        Reply created=call("POST","/api/v1/users",command,admin);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        UUID id=UUID.fromString(created.body().get("id").asText());
        assertThat(created.body().get("enabled").asBoolean()).isFalse();
        assertThat(created.body().get("status").asText()).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("select initial_password_expires_at-created_at=interval '7 days' from users where id=?",Boolean.class,id)).isTrue();
        assertThat(call("POST","/api/v1/auth/login",java.util.Map.of("username",name,"password","ChangeMe123!","clientType","PC"),null).status()).isEqualTo(401);
        assertThat(call("PUT","/api/v1/users/"+id,java.util.Map.of("username",name,"displayName","Active now","role","PRODUCTION_MANAGER","processAssignments",List.of(),"status","ACTIVE"),admin).body().get("enabled").asBoolean()).isTrue();
        Reply logged=call("POST","/api/v1/auth/login",java.util.Map.of("username",name,"password","ChangeMe123!","clientType","PC"),null);
        String oldToken=logged.body().get("accessToken").asText();
        assertThat(call("GET","/api/v1/trolleys",null,oldToken).status()).isEqualTo(403);
        var deadline=jdbc.queryForObject("select initial_password_expires_at from users where id=?",java.time.OffsetDateTime.class,id);
        assertThat(call("PUT","/api/v1/users/"+id+"/password",java.util.Map.of("temporaryPassword","AnotherTemp123!"),admin).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select initial_password_expires_at from users where id=?",java.time.OffsetDateTime.class,id)).isEqualTo(deadline);
        jdbc.update("update users set initial_password_expires_at=now()-interval '1 second' where id=?",id);
        assertThat(call("POST","/api/v1/auth/login",java.util.Map.of("username",name,"password","AnotherTemp123!","clientType","PC"),null).status()).isEqualTo(401);
        assertThat(call("POST","/api/v1/auth/change-password",java.util.Map.of("currentPassword","AnotherTemp123!","newPassword","ReadyForWork123!"),oldToken).status()).isEqualTo(403);
        command.put("status","ACTIVE");
        Reply recreated=call("POST","/api/v1/users",command,admin);
        assertThat(recreated.status()).as(recreated.body().toString()).isEqualTo(200);
        assertThat(recreated.body().get("id").asText()).isNotEqualTo(id.toString());
        assertThat(call("GET","/api/v1/auth/me",null,oldToken).status()).isEqualTo(403);
        String token=login(name,"PC");
        assertThat(call("PUT","/api/v1/users/"+recreated.body().get("id").asText()+"/password",java.util.Map.of("temporaryPassword","LaterReset123!"),admin).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select initial_password_expires_at is null from users where username=?",Boolean.class,name)).isTrue();
        assertThat(call("GET","/api/v1/trolleys",null,token).status()).isEqualTo(200);
    }

    @Test void postgresPagingCountsFilteredRowsAndTreatsSearchAsLiteral(){
        String sql="select * from (values (1,'Alpha'),(2,'aLPha two'),(3,'100%_literal'),(4,'Beta')) sample(id,name) order by id";
        var first=com.yesbee.nwtrack.shared.Pages.query(jdbc,new com.yesbee.nwtrack.shared.Pages.Options(0,1,"alpha"),sql);
        var second=com.yesbee.nwtrack.shared.Pages.query(jdbc,new com.yesbee.nwtrack.shared.Pages.Options(1,1,"alpha"),sql);
        assertThat(first.totalElements()).isEqualTo(2);assertThat(first.items().getFirst().get("id")).isEqualTo(1);
        assertThat(second.items().getFirst().get("id")).isEqualTo(2);
        assertThat(first.items().getFirst()).doesNotContainKey("__page_order");
        var literal=com.yesbee.nwtrack.shared.Pages.query(jdbc,new com.yesbee.nwtrack.shared.Pages.Options(0,20,"%_"),sql);
        assertThat(literal.totalElements()).isEqualTo(1);assertThat(literal.items().getFirst().get("id")).isEqualTo(3);
        assertThat(com.yesbee.nwtrack.shared.Pages.query(jdbc,new com.yesbee.nwtrack.shared.Pages.Options(0,20,"' OR true --"),sql).items()).isEmpty();
        assertThat(com.yesbee.nwtrack.shared.Pages.query(jdbc,new com.yesbee.nwtrack.shared.Pages.Options(10,20,"alpha"),sql).totalElements()).isEqualTo(2);
    }

    @Test void plannedLotsAreGeneratedAtReleaseAndConfiguratorOwnsAllLotsOfTheirStyles()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString(),owner="lot-owner-"+suffix,other="lot-other-"+suffix;
        for(String name:List.of(owner,other))assertThat(call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role","STYLE_CONFIGURATOR"),admin).status()).isEqualTo(200);
        String creator=login(owner,"PC"),outsider=login(other,"PC");
        UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"LM-S-"+suffix,"Lot station");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",process,"LM-P-"+suffix,"Lot process",station);
        Reply style=call("POST","/api/v1/styles",java.util.Map.of("styleNumber","LM-"+suffix,"processIds",List.of(process.toString()),"lotCount",3,"quantity",10),creator);
        assertThat(style.status()).as(style.body().toString()).isEqualTo(200);String styleId=style.body().get("id").asText();
        String styleLots="/api/v1/styles/"+styleId+"/lots";
        assertThat(call("GET",styleLots,null,creator).body().get("totalElements").asInt()).isZero();
        assertThat(call("POST",styleLots,java.util.Map.of("lotCount",2),creator).status()).isEqualTo(409);
        assertThat(call("POST","/api/v1/styles/"+styleId+"/release",null,outsider).status()).isEqualTo(403);
        assertThat(call("POST","/api/v1/styles/"+styleId+"/release",null,creator).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/styles/"+styleId+"/release",null,admin).status()).isEqualTo(409);
        Reply initial=call("GET",styleLots,null,creator);
        assertThat(initial.body().get("totalElements").asInt()).isEqualTo(3);
        var numbers=new java.util.HashSet<String>();
        for(var lot:initial.body().get("items")){
            assertThat(lot.get("quantity").asInt()).isEqualTo(10);
            assertThat(lot.get("lot_number").asText()).matches("LOT-[A-Za-z0-9]+-[0-9]{3,}");numbers.add(lot.get("lot_number").asText());
            UUID.fromString(lot.get("id").asText());
        }
        assertThat(numbers).hasSize(3);
        // Admin-created lots of the configurator's style are visible to its owner too.
        Reply additional=call("POST",styleLots,java.util.Map.of("lotCount",2),admin);
        assertThat(additional.status()).as(additional.body().toString()).isEqualTo(200);
        assertThat(additional.body().get("createdCount").asInt()).isEqualTo(2);
        assertThat(additional.body().get("items").get(0).get("quantity").isNull()).isTrue();
        assertThat(call("GET",styleLots,null,creator).body().get("totalElements").asInt()).isEqualTo(5);
        assertThat(call("GET","/api/v1/lots",null,creator).body().get("totalElements").asInt()).isEqualTo(5);
        assertThat(call("GET","/api/v1/lots",null,outsider).body().get("totalElements").asInt()).isZero();
        assertThat(call("GET",styleLots,null,outsider).status()).isEqualTo(403);
        assertThat(call("POST",styleLots,java.util.Map.of("lotCount",1),outsider).status()).isEqualTo(403);
        String lotId=initial.body().get("items").get(0).get("id").asText(),lotPath="/api/v1/lots/"+lotId;
        for(String path:List.of(lotPath,lotPath+"/timeline"))assertThat(call("GET",path,null,outsider).status()).isEqualTo(403);
        assertThat(call("POST",lotPath+"/cancel",java.util.Map.of("reason","Not my lot"),outsider).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/ui/lookups",null,creator).body().get("styles").get("totalElements").asInt()).isEqualTo(1);
        assertThat(call("GET","/api/v1/ui/lookups",null,outsider).body().get("styles").get("totalElements").asInt()).isZero();
        assertThat(call("POST",styleLots,java.util.Map.of("lotCount",0),creator).status()).isEqualTo(400);
        assertThat(call("POST",styleLots,java.util.Map.of("lotCount",1,"quantity",0),creator).status()).isEqualTo(400);
        assertThat(call("GET",styleLots,null,creator).body().get("totalElements").asInt()).isEqualTo(5);
        // Lot Management allows choosing and releasing a trolley without general configuration access.
        Reply trolley=call("POST","/api/v1/trolleys",java.util.Map.of("code","LM-T-"+suffix),admin);
        String trolleyId=trolley.body().get("id").asText();
        assertThat(call("GET","/api/v1/trolleys/available",null,creator).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/trolleys",null,creator).status()).isEqualTo(403);
        assertThat(call("PUT",lotPath+"/trolley",java.util.Map.of("trolleyId",trolleyId),outsider).status()).isEqualTo(403);
        Reply mapped=call("PUT",lotPath+"/trolley",java.util.Map.of("trolleyId",trolleyId),creator);
        assertThat(mapped.status()).as(mapped.body().toString()).isEqualTo(200);
        assertThat(mapped.body().get("trolley_id").asText()).isEqualTo(trolleyId);
        String second=additional.body().get("items").get(0).get("id").asText();
        assertThat(call("PUT","/api/v1/lots/"+second+"/trolley",java.util.Map.of("trolleyId",trolleyId),creator).status()).isEqualTo(409);
        assertThat(call("DELETE",lotPath+"/trolley",null,outsider).status()).isEqualTo(403);
        assertThat(call("DELETE",lotPath+"/trolley",null,creator).body().get("trolley_id").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select status from trolleys where id=?::uuid",String.class,trolleyId)).isEqualTo("AVAILABLE");
        assertThat(call("DELETE",lotPath+"/trolley",null,creator).status()).isEqualTo(200);
        jdbc.update("update trolleys set active=false where id=?::uuid",trolleyId);
        assertThat(call("PUT",lotPath+"/trolley",java.util.Map.of("trolleyId",trolleyId),creator).status()).isEqualTo(409);
        jdbc.update("update trolleys set active=true where id=?::uuid",trolleyId);
        assertThat(call("PUT",lotPath+"/trolley",java.util.Map.of("trolleyId",trolleyId),creator).status()).isEqualTo(200);
        jdbc.update("update lots set status='DISPATCHED' where id=?::uuid",lotId);
        assertThat(call("DELETE",lotPath+"/trolley",null,creator).status()).isEqualTo(409);
        assertThat(call("POST",lotPath+"/dispatch",null,creator).status()).isEqualTo(403);
    }

    @Test void lotQuantityEditingEnforcesOwnershipStagesAndAuditHistory()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString();
        var tokens=new java.util.LinkedHashMap<String,String>();
        UUID owner=null;
        for(String role:List.of("STYLE_CONFIGURATOR","NW","ADMIN_IT","PRODUCTION_MANAGER")){
            String name=role+"-edit-"+suffix;
            Reply user=call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role",role),admin);
            assertThat(user.status()).isEqualTo(200);
            if(role.equals("STYLE_CONFIGURATOR"))owner=UUID.fromString(user.body().get("id").asText());
            tokens.put(role,login(name,"PC"));
        }
        UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"EDIT-S-"+suffix,"Edit station");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",process,"EDIT-P-"+suffix,"Edit process",station);
        UUID style=production.createConfiguredStyle("EDIT-"+suffix,"Editable",owner,List.of(process),1,10);
        assertThat(call("POST","/api/v1/styles/"+style+"/release",null,tokens.get("STYLE_CONFIGURATOR")).status()).isEqualTo(200);
        UUID lot=jdbc.queryForObject("select id from lots where style_id=?",UUID.class,style);
        String path="/api/v1/lots/"+lot,number=jdbc.queryForObject("select lot_number from lots where id=?",String.class,lot);
        int quantity=10;
        for(var entry:tokens.entrySet()){
            Reply edited=call("PUT",path,java.util.Map.of("quantity",++quantity,"reason","Count corrected by "+entry.getKey()),entry.getValue());
            assertThat(edited.status()).as(edited.body().toString()).isEqualTo(200);
            assertThat(edited.body().get("quantity").asInt()).isEqualTo(quantity);
            assertThat(edited.body().get("lot_number").asText()).isEqualTo(number);
            assertThat(edited.body().get("style_id").asText()).isEqualTo(style.toString());
        }
        assertThat(jdbc.queryForObject("select original_quantity from lots where id=?",Integer.class,lot)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from lot_quantity_changes where lot_id=?",Integer.class,lot)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where object_id=? and action='UPDATE_QUANTITY'",Integer.class,lot)).isEqualTo(4);
        for(String role:List.of("STYLE_CONFIGURATOR","SUPERVISOR")){
            String name="outside-edit-"+role+suffix;
            assertThat(call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role",role),admin).status()).isEqualTo(200);
            String token=login(name,"PC");
            assertThat(call("PUT",path,java.util.Map.of("quantity",100,"reason","Not permitted"),token).status()).isEqualTo(403);
        }
        for(String state:List.of("READY","AT_STATION","IN_STORAGE","IN_PROCESS","READY_FOR_DISPATCH","AT_QC")){
            jdbc.update("update lots set status=? where id=?",state,lot);
            Reply edited=call("PUT",path,java.util.Map.of("quantity",++quantity,"reason","Recount during "+state),tokens.get("STYLE_CONFIGURATOR"));
            assertThat(edited.status()).as(state+": "+edited.body()).isEqualTo(200);
            assertThat(edited.body().get("status").asText()).isEqualTo(state);
        }
        for(String state:List.of("DISPATCHED","COMPLETED","CANCELLED")){
            jdbc.update("update lots set status=? where id=?",state,lot);
            assertThat(call("PUT",path,java.util.Map.of("quantity",100,"reason","Blocked state"),admin).status()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select quantity from lots where id=?",Integer.class,lot)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("select count(*) from lot_quantity_changes where lot_id=?",Integer.class,lot)).isEqualTo(10);
        jdbc.update("update lots set status='READY' where id=?",lot);
        for(var invalid:List.of(java.util.Map.of("reason","Missing quantity"),java.util.Map.of("quantity",0,"reason","Zero"),java.util.Map.of("quantity",3,"reason"," "),java.util.Map.of("quantity",3)))
            assertThat(call("PUT",path,invalid,admin).status()).isEqualTo(400);
        UUID unset=production.createLot(style,null,owner);
        String unsetPath="/api/v1/lots/"+unset;
        assertThat(call("PUT",unsetPath,java.util.Map.of("quantity",25,"reason","First count"),tokens.get("STYLE_CONFIGURATOR")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select original_quantity is null from lots where id=?",Boolean.class,unset)).isTrue();
        assertThat(jdbc.queryForObject("select old_quantity is null and new_quantity=25 and actor_id=? and reason='First count' from lot_quantity_changes where lot_id=?",Boolean.class,owner,unset)).isTrue();
        assertThat(call("PUT",unsetPath,java.util.Map.of("quantity",25,"reason","No change"),admin).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from lot_quantity_changes where lot_id=?",Integer.class,unset)).isEqualTo(1);
        UUID managerStyle=production.createConfiguredStyle("MGR-"+suffix,null,owner,List.of(process),1,null);
        assertThat(call("POST","/api/v1/styles/"+managerStyle+"/release",null,tokens.get("PRODUCTION_MANAGER")).status()).isEqualTo(200);
    }

    @Test void releaseWithUnspecifiedQuantityAndConcurrentLotBatchesUseGeneratedNumbers()throws Exception{
        String suffix=UUID.randomUUID().toString();UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class),station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"GEN-S-"+suffix,"Generated station");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",process,"GEN-P-"+suffix,"Generated process",station);
        UUID style=production.createConfiguredStyle("GEN-"+suffix,"Optional quantity",actor,List.of(process),2,null);
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=?",Integer.class,style)).isZero();
        production.release(style);
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=? and quantity is null and original_quantity is null",Integer.class,style)).isEqualTo(2);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->production.createLots(style,3,7,actor));
            var b=executor.submit(()->production.createLots(style,3,9,actor));
            assertThat(a.get()).hasSize(3);assertThat(b.get()).hasSize(3);
        }
        assertThat(jdbc.queryForObject("select count(distinct lot_number) from lots where style_id=?",Integer.class,style)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=? and quantity=7",Integer.class,style)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=? and quantity=9",Integer.class,style)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from station_visits where lot_id in(select id from lots where style_id=?)",Integer.class,style)).isEqualTo(8);
        revisions.revise(style,"Quantity now specified",List.of(process),11,"Record quantities",StyleRevisionService.Scope.UNSTARTED,actor,"NW");
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=? and quantity=11",Integer.class,style)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from lot_quantity_changes where lot_id in(select id from lots where style_id=?) and old_quantity is null",Integer.class,style)).isEqualTo(2);
        UUID invalid=production.createConfiguredStyle("INVALID-"+suffix,null,actor,List.of(),2,null);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->production.release(invalid)).isInstanceOf(com.yesbee.nwtrack.shared.BusinessRuleException.class);
        assertThat(jdbc.queryForObject("select status from styles where id=?",String.class,invalid)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from lots where style_id=?",Integer.class,invalid)).isZero();
    }
    @Test void v21ModuleIsolationDynamicRolesOwnershipAndScopedLookups()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString().substring(0,8);
        assertThat(call("GET","/api/v1/modules",null,admin).body().get("items").size()).isGreaterThanOrEqualTo(9);
        assertThat(call("DELETE","/api/v1/access-roles/NW",null,admin).status()).isEqualTo(409);
        assertThat(call("PUT","/api/v1/access-roles/NW",java.util.Map.of("modules",List.of("dashboard")),admin).status()).isEqualTo(400);
        Reply module=call("POST","/api/v1/modules",java.util.Map.of("name","Custom "+suffix),admin);
        assertThat(module.status()).as(module.body().toString()).isEqualTo(200);String moduleKey=module.body().get("key").asText();
        assertThat(call("POST","/api/v1/modules",java.util.Map.of("name","CUSTOM "+suffix),admin).status()).isEqualTo(409);
        Reply role=call("POST","/api/v1/access-roles",java.util.Map.of("name","Scoped "+suffix,"modules",List.of("process",moduleKey)),admin);
        assertThat(role.status()).as(role.body().toString()).isEqualTo(200);String code=role.body().get("code").asText();
        assertThat(call("POST","/api/v1/access-roles",java.util.Map.of("name","SCOPED "+suffix,"modules",List.of()),admin).status()).isEqualTo(409);
        UUID station=UUID.randomUUID(),process=UUID.randomUUID(),outsideStation=UUID.randomUUID(),outside=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?),(?,?,?)",station,"SC-"+suffix,"Scoped",outsideStation,"OUT-"+suffix,"Outside");
        jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?),(?,?,?,?)",process,"SC-P-"+suffix,"Scoped process",station,outside,"OUT-P-"+suffix,"Other process",outsideStation);
        String username="custom-"+suffix;
        Reply user=call("POST","/api/v1/users",java.util.Map.of("username",username,"displayName","Custom","initialPassword","ChangeMe123!","role",code,"processAssignments",List.of(java.util.Map.of("processId",process.toString(),"activeOnShift",true))),admin);
        assertThat(user.status()).isEqualTo(200);String userId=user.body().get("id").asText(),token=login(username,"PC");
        Reply me=call("GET","/api/v1/auth/me",null,token);assertThat(me.status()).isEqualTo(200);assertThat(me.body().get("modules").size()).isEqualTo(2);
        assertThat(call("GET","/api/v1/processes",null,token).body().get("items").size()).isEqualTo(1);
        assertThat(call("GET","/api/v1/processes/"+outside,null,token).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/ui/lookups",null,token).body().get("processes").get("items").size()).isEqualTo(1);
        String newUid="NEW-P-"+suffix;
        assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name",newUid,"uid",newUid),admin).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/processes",java.util.Map.of("name","Forbidden","stationNumber","OUT-"+suffix,"nfcMode","PROCESS","nfcUid",newUid),token).status()).isEqualTo(403);
        Reply createdProcess=call("POST","/api/v1/processes",java.util.Map.of("name","New scoped process","stationNumber","SC-"+suffix,"nfcMode","PROCESS","nfcUid",newUid),token);
        assertThat(createdProcess.status()).as(createdProcess.body().toString()).isEqualTo(200);
        assertThat(call("GET","/api/v1/processes/"+createdProcess.body().get("id").asText(),null,token).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select active_on_shift from user_process_assignments where user_id=?::uuid and process_id=?::uuid",Boolean.class,userId,createdProcess.body().get("id").asText())).isFalse();
        for(String path:List.of("/api/v1/storage-areas","/api/v1/nfc-tags","/api/v1/users","/api/v1/access-roles","/api/v1/dashboard","/api/v1/reports/lot-progress","/api/v1/reports/style-progress"))assertThat(call("GET",path,null,token).status()).as(path).isEqualTo(403);
        assertThat(call("GET","/api/v1/modules/"+moduleKey+"/content",null,token).body().get("status").asText()).isEqualTo("WORK_IN_PROGRESS");
        // Role changes affect existing JWTs immediately; Dashboard and Reports stay independent.
        assertThat(call("PUT","/api/v1/access-roles/"+code,java.util.Map.of("modules",List.of("dashboard")),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/dashboard",null,token).status()).isEqualTo(200);
        for(String type:List.of("lots","styles","stations","processes")){
            assertThat(call("GET","/api/v1/dashboard/filter-options/"+type,null,token).status()).isEqualTo(200);
            assertThat(call("GET","/api/v1/reports/filter-options/"+type,null,token).status()).isEqualTo(403);
        }
        assertThat(call("GET","/api/v1/reports/lot-progress",null,token).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/reports/style-progress",null,token).status()).isEqualTo(403);
        assertThat(call("PUT","/api/v1/access-roles/"+code,java.util.Map.of("modules",List.of("reports")),admin).status()).isEqualTo(200);
        for(String type:List.of("lots","styles","stations","processes")){
            assertThat(call("GET","/api/v1/dashboard/filter-options/"+type,null,token).status()).isEqualTo(403);
            assertThat(call("GET","/api/v1/reports/filter-options/"+type,null,token).status()).isEqualTo(200);
        }
        assertThat(call("GET","/api/v1/processes",null,token).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/modules/"+moduleKey+"/content",null,token).status()).isEqualTo(403);
        assertThat(call("DELETE","/api/v1/access-roles/"+code,null,admin).status()).isEqualTo(409);
        // Storage form lookups work without access to Process or Tag Management.
        assertThat(call("PUT","/api/v1/access-roles/"+code,java.util.Map.of("modules",List.of("storage")),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/storage-areas",null,token).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/nfc-tags",null,token).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/ui/lookups",null,token).body().has("tags")).isTrue();
        String uid="ROLE-STORE-"+suffix;
        assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name",uid,"uid",uid),admin).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/storage-areas",java.util.Map.of("storageNumber","DENIED-"+suffix,"stationNumber","OUT-"+suffix,"nfcUid",uid),token).status()).isEqualTo(403);
        assertThat(call("POST","/api/v1/storage-areas",java.util.Map.of("storageNumber","ALLOW-"+suffix,"stationNumber","SC-"+suffix,"nfcUid",uid),token).status()).isEqualTo(200);
        // USERS lookup supplies dynamic role choices without the ROLES administration module.
        assertThat(call("PUT","/api/v1/access-roles/"+code,java.util.Map.of("modules",List.of("users")),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/access-roles/options",null,token).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/access-roles",null,token).status()).isEqualTo(403);
        // A module with no grants still permits login/me, but no application data.
        assertThat(call("PUT","/api/v1/access-roles/"+code,java.util.Map.of("modules",List.of()),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/auth/me",null,token).body().get("modules").size()).isZero();
        assertThat(call("GET","/api/v1/ui/lookups",null,token).status()).isEqualTo(403);
        // Style Configurator creates atomically and sees only its own records, including drafts.
        String author="author-"+suffix,otherAuthor="other-author-"+suffix;
        for(String name:List.of(author,otherAuthor))assertThat(call("POST","/api/v1/users",java.util.Map.of("username",name,"displayName",name,"initialPassword","ChangeMe123!","role","STYLE_CONFIGURATOR"),admin).status()).isEqualTo(200);
        String authorToken=login(author,"PC"),otherToken=login(otherAuthor,"PC");
        assertThat(call("GET","/api/v1/ui/lookups",null,authorToken).body().get("processes").get("items").size()).isGreaterThanOrEqualTo(2);
        assertThat(call("GET","/api/v1/processes",null,authorToken).status()).isEqualTo(403);
        Reply style=call("POST","/api/v1/styles",java.util.Map.of("styleNumber","OWN-"+suffix,"description","Own","processIds",List.of(process.toString())),authorToken);
        assertThat(style.status()).as(style.body().toString()).isEqualTo(200);String styleId=style.body().get("id").asText();
        assertThat(call("GET","/api/v1/styles",null,authorToken).body().get("items").size()).isEqualTo(1);
        assertThat(call("GET","/api/v1/styles/"+styleId+"/derived-route",null,authorToken).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/styles",null,otherToken).body().get("items").size()).isZero();
        assertThat(call("GET","/api/v1/styles/"+styleId,null,otherToken).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/styles/"+styleId+"/derived-route",null,otherToken).status()).isEqualTo(403);
        assertThat(call("PUT","/api/v1/styles/"+styleId+"/processes",List.of(outside.toString()),authorToken).status()).isEqualTo(403);
        assertThat(call("POST","/api/v1/styles/"+styleId+"/release",null,otherToken).status()).isEqualTo(403);
        assertThat(call("POST","/api/v1/styles/"+styleId+"/release",null,authorToken).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/styles",java.util.Map.of("styleNumber","EMPTY-"+suffix),authorToken).status()).isEqualTo(400);
        // Failed route validation rolls back the whole create.
        UUID sameStationProcess=UUID.randomUUID();jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?)",sameStationProcess,"SECOND-"+suffix,"Second",station);
        assertThat(call("POST","/api/v1/styles",java.util.Map.of("styleNumber","BAD-"+suffix,"processIds",List.of(process.toString(),outside.toString(),sameStationProcess.toString())),authorToken).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from styles where style_number=?",Integer.class,"BAD-"+suffix)).isZero();
        assertThat(call("PUT","/api/v1/users/"+userId+"/roles",java.util.Map.of("role","PROCESS_MANAGER"),admin).status()).isEqualTo(200);
        assertThat(call("DELETE","/api/v1/access-roles/"+code,null,admin).status()).isEqualTo(200);
        assertThat(call("PUT","/api/v1/users/"+userId+"/roles",java.util.Map.of("role",code),admin).status()).isEqualTo(404);
        assertThat(call("GET","/api/v1/auth/me",null,token).status()).isEqualTo(403);
    }

    @Test void v6PreservesStationZeroUsersAndReplacesTheirAccess(){
        String schema="role_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var before=org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("5").load();before.migrate();
        var upgradeJdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword()));
        UUID user=UUID.randomUUID(),oldRole=upgradeJdbc.queryForObject("select id from roles where code='STATION_0'",UUID.class);
        upgradeJdbc.update("insert into users(id,username,display_name,password_hash) values (?,'station-zero','Station zero','test')",user);
        upgradeJdbc.update("insert into user_roles values (?,?)",user,oldRole);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(upgradeJdbc.queryForObject("select role_id from user_roles where user_id=?",UUID.class,user)).isEqualTo(oldRole);
        assertThat(upgradeJdbc.queryForObject("select code from roles where id=?",String.class,oldRole)).isEqualTo("STYLE_CONFIGURATOR");
        assertThat(upgradeJdbc.queryForList("select module_key from role_modules where role_id=?",String.class,oldRole)).containsExactlyInAnyOrder("newstyle","lots");
        assertThat(upgradeJdbc.queryForObject("select initial_password_expires_at is null from users where id=?",Boolean.class,user)).isTrue();
        assertThat(upgradeJdbc.queryForList("select permission from role_permissions where role_id=?",String.class,oldRole)).containsExactly("READ");
        assertThat(upgradeJdbc.queryForObject("select pad_access from roles where id=?",Boolean.class,oldRole)).isFalse();
        assertThat(upgradeJdbc.queryForObject("select pc_access from roles where code='SUPERVISOR'",Boolean.class)).isTrue();
        assertThat(upgradeJdbc.queryForObject("select count(*) from roles where built_in",Integer.class)).isEqualTo(7);
    }

    @Test void corsPreflightAndActualResponsesAreHandledBeforeAuthentication()throws Exception{
        var client=java.net.http.HttpClient.newHttpClient();
        for(String path:List.of("/api/v1/auth/login","/api/v1/processes","/api/v1/access-roles/NW")){
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+path))
                .header("Origin","http://localhost:8080")
                .header("Access-Control-Request-Method","PUT")
                .header("Access-Control-Request-Headers","authorization,content-type")
                .method("OPTIONS",java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:8080");
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PUT");
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("authorization","content-type");
        }
        var forbidden=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/processes"))
            .header("Origin","http://untrusted.example").header("Access-Control-Request-Method","GET")
            .method("OPTIONS",java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        var blocked=client.send(forbidden,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(403);assertThat(blocked.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        // Cross-origin real requests retain authentication and authorization, with readable errors.
        var unauthenticated=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/processes"))
            .header("Origin","http://localhost:8080").GET().build();
        var denied=client.send(unauthenticated,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:8080");
        var authenticated=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/processes"))
            .header("Origin","http://localhost:8080").header("Authorization","Bearer "+login("admin","PC")).GET().build();
        var allowed=client.send(authenticated,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:8080");
    }

    @Test void configurationFieldsAssignmentsPermissionsAndChannels()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString().substring(0,8);
        String processUid="P-"+suffix,deviceUid="D-"+suffix,storageUid="S-"+suffix;
        for(String uid:List.of(processUid,deviceUid,storageUid))assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name","Friendly "+uid,"uid",uid),admin).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name",("Friendly "+processUid).toUpperCase(java.util.Locale.ROOT),"uid","EXTRA-"+suffix),admin).status()).isEqualTo(409);
        var payload=new java.util.LinkedHashMap<String,Object>();payload.put("code","P-"+suffix);payload.put("name","Preparation");payload.put("description","Process description");payload.put("stationReference","REF-"+suffix);payload.put("nfcMode","PROCESS");payload.put("nfcUid",processUid);payload.put("devices",List.of());
        Reply process=call("POST","/api/v1/processes",payload,admin);
        assertThat(process.status()).as(process.body().toString()).isEqualTo(200);
        assertThat(process.body().get("description").asText()).isEqualTo("Process description");
        String station=process.body().get("station_id").asText(),processId=process.body().get("id").asText();
        payload.put("code","D-"+suffix);payload.put("name","Washing");payload.put("nfcMode","DEVICES");payload.remove("nfcUid");payload.put("devices",List.of(java.util.Map.of("name","Washer","description","Device description","type","WASHER","nfcUid",deviceUid)));
        Reply deviceProcess=call("POST","/api/v1/processes",payload,admin);
        assertThat(deviceProcess.status()).as(deviceProcess.body().toString()).isEqualTo(200);
        assertThat(deviceProcess.body().get("station_id").asText()).isEqualTo(station);
        assertThat(deviceProcess.body().get("devices").get(0).get("description").asText()).isEqualTo("Device description");
        Reply storage=call("POST","/api/v1/storage-areas",java.util.Map.of("stationReference","ref-"+suffix,"code","STORE-"+suffix,"name","Storage","description","Area description","nfcUids",List.of(storageUid)),admin);
        assertThat(storage.status()).as(storage.body().toString()).isEqualTo(200);
        assertThat(storage.body().get("station_id").asText()).isEqualTo(station);
        assertThat(storage.body().get("nfc_uid").asText()).isEqualTo(storageUid.toUpperCase(java.util.Locale.ROOT));
        assertThat(call("POST","/api/v1/storage-areas",java.util.Map.of("stationReference","OTHER-AREA-"+suffix,"code","store-"+suffix,"name","Duplicate","nfcUids",List.of(storageUid)),admin).status()).isEqualTo(409);
        assertThat(call("GET","/api/v1/processes",null,admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/storage-areas",null,admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/machines",null,admin).status()).isEqualTo(200);
        payload.put("code","DUP-"+suffix);
        assertThat(call("POST","/api/v1/processes",payload,admin).status()).isEqualTo(409);
        String otherStation=UUID.randomUUID().toString();jdbc.update("insert into stations(id,code,name) values (?::uuid,?,?)",otherStation,"OTHER-"+suffix,"Other");
        UUID otherProcess=UUID.randomUUID();jdbc.update("insert into processes(id,code,name,station_id) values (?,?,?,?::uuid)",otherProcess,"OTHER-P-"+suffix,"Other process",otherStation);
        var assignments=List.of(java.util.Map.of("processId",processId,"activeOnShift",true),java.util.Map.of("processId",otherProcess.toString(),"activeOnShift",true));
        String username="pm-"+suffix;
        Reply user=call("POST","/api/v1/users",java.util.Map.of("username",username,"displayName","Process manager","initialPassword","ChangeMe123!","role","PROCESS_MANAGER","processAssignments",assignments),admin);
        assertThat(user.status()).as(user.body().toString()).isEqualTo(200);assertThat(user.body().get("processAssignments").size()).isEqualTo(2);
        String pm=login(username,"PAD");
        Reply own=call("GET","/api/v1/stations",null,pm);assertThat(own.status()).isEqualTo(200);assertThat(own.body().get("items").size()).isEqualTo(2);
        assertThat(call("GET","/api/v1/users",null,pm).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/access-roles",null,admin).body().get("items").size()).isEqualTo(7);
        assertThat(call("POST","/api/v1/access-roles",java.util.Map.of("code","CUSTOM"),admin).status()).isEqualTo(400);
        Reply supervisor=call("POST","/api/v1/users",java.util.Map.of("username","sup-"+suffix,"displayName","Supervisor","initialPassword","ChangeMe123!","role","SUPERVISOR"),admin);
        assertThat(supervisor.status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/auth/login",java.util.Map.of("username","sup-"+suffix,"password","ChangeMe123!","clientType","PC"),null).status()).isEqualTo(200);
        String sup=login("sup-"+suffix,"PAD");assertThat(call("GET","/api/v1/stations/"+station,null,sup).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/stations/%"+Integer.toHexString(station.charAt(0))+station.substring(1),null,sup).status()).isEqualTo(403);
        // Role permission edits are effective for an already-issued token.
        var permissions=java.util.Set.of("OPERATE","QC","REPORTS","TRANSPORT","READ");
        assertThat(call("PUT","/api/v1/access-roles/PROCESS_MANAGER",java.util.Map.of("pcAccess",true,"padAccess",true,"permissions",List.of("READ")),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/dashboard",null,pm).status()).isEqualTo(403);
        assertThat(call("PUT","/api/v1/access-roles/PROCESS_MANAGER",java.util.Map.of("pcAccess",true,"padAccess",true,"permissions",permissions),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/dashboard",null,pm).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/sync/bootstrap",null,pm).body().get("processAssignments").size()).isEqualTo(2);
        assertThat(call("GET","/api/v1/sync/changes?since=2020-01-01T00:00:00Z",null,pm).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/nfc/resolve",java.util.Map.of("uid",deviceUid),pm).status()).isEqualTo(403);
        assertThat(call("POST","/api/v1/users",java.util.Map.of("username","top-"+suffix,"displayName","Top manager","initialPassword","ChangeMe123!","role","TOP_MANAGER"),admin).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/auth/login",java.util.Map.of("username","top-"+suffix,"password","ChangeMe123!","clientType","PAD"),null).status()).isEqualTo(403);
        String top=login("top-"+suffix,"PC");
        assertThat(call("POST","/api/v1/styles",java.util.Map.of("styleNumber","FORBIDDEN","description",""),top).status()).isEqualTo(403);
        String pcManager=login(username,"PC");
        assertThat(call("POST","/api/v1/nfc/resolve",java.util.Map.of("uid",processUid),pcManager).status()).isEqualTo(403);
        // Every advertised REST operation has an importable Postman request.
        var collection=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/postman/NW_Track.postman_collection.json")));
        java.util.List<String> examples=new java.util.ArrayList<>();
        for(var f:collection.get("item"))for(var item:f.get("item")){assertThat(item.has("event")).isFalse();assertThat(item.get("request").get("url").isObject()).isTrue();examples.add(item.get("request").get("method").asText()+" "+(item.get("request").get("url").isTextual()?item.get("request").get("url").asText():item.get("request").get("url").get("raw").asText()).replace("{{baseUrl}}","").split("\\?")[0].replaceAll("\\{\\{[^}]+}}","example"));}
        Reply spec=call("GET","/v3/api-docs",null,null);assertThat(spec.status()).as(spec.body().toString()).isEqualTo(200);
        var paths=spec.body().get("paths").fields();
        while(paths.hasNext()){
            var path=paths.next();if(!path.getKey().startsWith("/api/v1"))continue;
            var methods=path.getValue().fieldNames();while(methods.hasNext()){
                String method=methods.next();if(!java.util.Set.of("get","post","put","patch","delete").contains(method))continue;
                String pattern=method.toUpperCase()+" "+path.getKey().replaceAll("\\{[^}]+}","[^/]+");
                assertThat(examples.stream().anyMatch(example->example.matches(pattern))).as("Missing Postman request: "+method+" "+path.getKey()).isTrue();
            }
        }
    }

    @Test void finalQualityRequiresBalancedCountsRemarksAndClosesWithoutRescan(){
        String suffix=UUID.randomUUID().toString().substring(0,8);UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        UUID station=UUID.randomUUID(),process=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name) values (?,?,?)",station,"Q-"+suffix,"Quality");
        jdbc.update("insert into processes(id,code,name,station_id,quality,nfc_mode) values (?,?,?, ?,true,'DEVICES')",process,"Q-"+suffix,"Final quality",station);
        UUID device=UUID.randomUUID();jdbc.update("insert into machines(id,station_id,process_id,name,machine_type) values (?,?,?,?,?)",device,station,process,"Inspection device","QC");
        nfc.registerAndAssign("Q-TAG-"+suffix,"MACHINE",device,actor,"Test");
        UUID otherProcess=UUID.randomUUID(),otherDevice=UUID.randomUUID();
        jdbc.update("insert into processes(id,code,name,station_id,nfc_mode) values (?,?,?,?,'DEVICES')",otherProcess,"OTHER-Q-"+suffix,"Other process",station);
        jdbc.update("insert into machines(id,station_id,process_id,name,machine_type) values (?,?,?,?,?)",otherDevice,station,otherProcess,"Other device","QC");
        nfc.registerAndAssign("OTHER-Q-TAG-"+suffix,"MACHINE",otherDevice,actor,"Test");
        UUID style=production.createStyle("QSTYLE-"+suffix,"QC",actor);production.setProcesses(style,List.of(process));production.release(style);
        UUID lot=production.createLot(style,10,actor);
        UUID visit=jdbc.queryForObject("select id from station_visits where lot_id=?",UUID.class,lot),execution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,visit);
        jdbc.update("update station_visits set status='RECEIVED' where id=?",visit);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->machines.transition(execution,"start",null,null,List.of("Q-TAG-"+suffix,"Q-TAG-"+suffix),actor)).hasMessageContaining("exactly one");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->machines.transition(execution,"start",null,null,List.of("OTHER-Q-TAG-"+suffix),actor)).hasMessageContaining("does not belong");
        machines.transition(execution,"start",null,null,List.of("Q-TAG-"+suffix),actor);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->machines.transition(execution,"complete",null,null,List.of(),actor)).hasMessageContaining("passedQuantity");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->machines.completeQuality(execution,7,2,"Rejects",actor)).hasMessageContaining("Lot quantity");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->machines.completeQuality(execution,8,2,"",actor)).hasMessageContaining("Remarks");
        assertThat(jdbc.queryForObject("select status from process_executions where id=?",String.class,execution)).isEqualTo("IN_PROGRESS");
        machines.completeQuality(execution,8,2,"Two damaged pieces; rework tracked separately",actor);
        assertThat(jdbc.queryForObject("select status from lots where id=?",String.class,lot)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select rejected_quantity from qc_inspections where lot_id=?",Integer.class,lot)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from machines where id=?",String.class,device)).isEqualTo("AVAILABLE");
        UUID unspecified=production.createLot(style,null,actor);
        UUID optionalVisit=jdbc.queryForObject("select id from station_visits where lot_id=?",UUID.class,unspecified);
        UUID optionalExecution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,optionalVisit);
        jdbc.update("update station_visits set status='RECEIVED' where id=?",optionalVisit);
        machines.transition(optionalExecution,"start",null,null,List.of("Q-TAG-"+suffix),actor);
        machines.completeQuality(optionalExecution,4,1,"One damaged piece",actor);
        assertThat(jdbc.queryForObject("select quantity from lots where id=?",Integer.class,unspecified)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select original_quantity is null from lots where id=?",Boolean.class,unspecified)).isTrue();
    }

    @Test void offlineEventsEnforceScansAndCommitIdempotently()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString().substring(0,8),uid="OFF-"+suffix.toUpperCase();
        assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name","Offline process","uid",uid),admin).status()).isEqualTo(200);
        Reply process=call("POST","/api/v1/processes",java.util.Map.of("code","OFF-"+suffix,"name","Offline work","stationReference","OFF-"+suffix,"nfcMode","PROCESS","nfcUid",uid,"quality",false),admin);
        assertThat(process.status()).as(process.body().toString()).isEqualTo(200);
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class),processId=UUID.fromString(process.body().get("id").asText());
        UUID style=production.createStyle("OFF-"+suffix,"No final QC",actor);production.setProcesses(style,List.of(processId));production.release(style);
        UUID lot=production.createLot(style,5,actor),visit=jdbc.queryForObject("select id from station_visits where lot_id=?",UUID.class,lot),execution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,visit);
        jdbc.update("update station_visits set status='RECEIVED' where id=?",visit);
        var event=new java.util.LinkedHashMap<String,Object>();event.put("id",UUID.randomUUID().toString());event.put("aggregateType","PROCESS_EXECUTION");event.put("aggregateId",execution.toString());event.put("eventType","PROCESS_START");event.put("occurredAt","2026-11-03T00:00:00Z");
        Reply missingScan=call("POST","/api/v1/sync/events",java.util.Map.of("deviceId","pad-test","events",List.of(event)),admin);
        assertThat(missingScan.status()).as(missingScan.body().toString()).isEqualTo(200);assertThat(missingScan.body().get(0).get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select status from process_executions where id=?",String.class,execution)).isEqualTo("PENDING");
        event.put("id",UUID.randomUUID().toString());event.put("processNfcUid",uid.toLowerCase());
        var batch=java.util.Map.of("deviceId","pad-test","events",List.of(event));
        Reply start=call("POST","/api/v1/sync/events",batch,admin);assertThat(start.status()).as(start.body().toString()).isEqualTo(200);assertThat(start.body().get(0).get("outcome").asText()).isEqualTo("APPLIED");
        Reply replay=call("POST","/api/v1/sync/events",batch,admin);assertThat(replay.status()).isEqualTo(200);assertThat(replay.body().get(0).get("detail").asText()).isEqualTo("duplicate");
        event.put("reason","Changed payload");assertThat(call("POST","/api/v1/sync/events",batch,admin).status()).isEqualTo(409);
        event.put("id",UUID.randomUUID().toString());event.put("eventType","PROCESS_COMPLETE");event.remove("processNfcUid");
        Reply done=call("POST","/api/v1/sync/events",batch,admin);assertThat(done.status()).as(done.body().toString()).isEqualTo(200);assertThat(done.body().get(0).get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select status from lots where id=?",String.class,lot)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from process_state_events where process_execution_id=?",Integer.class,execution)).isEqualTo(2);
    }

    @Test void mockOnlyCrudUsesProcessAssignmentsAndIndependentTagLifecycle()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString().substring(0,8);
        String[] uids={"MOCK-P-"+suffix,"MOCK-D1-"+suffix,"MOCK-D2-"+suffix,"MOCK-S-"+suffix};
        List<String> tags=new java.util.ArrayList<>();
        for(String uid:uids){
            Reply tag=call("POST","/api/v1/nfc-tags",java.util.Map.of("tagName","Mock "+uid,"tagId",uid),admin);
            assertThat(tag.status()).as(tag.body().toString()).isEqualTo(200);
            assertThat(tag.body().get("status").asText()).isEqualTo("ACTIVE");assertThat(tag.body().get("assigned").asBoolean()).isFalse();tags.add(tag.body().get("id").asText());
        }
        var processBody=new java.util.LinkedHashMap<String,Object>();processBody.put("name","Mock process "+suffix);processBody.put("description","Mock description");processBody.put("stationNumber","MOCK-"+suffix);processBody.put("mode","process");processBody.put("processTag",uids[0]);processBody.put("deviceCount",0);
        Reply process=call("POST","/api/v1/processes",processBody,admin);assertThat(process.status()).as(process.body().toString()).isEqualTo(200);
        String processId=process.body().get("id").asText(),code=process.body().get("code").asText(),oldStation=process.body().get("station_id").asText();assertThat(code).startsWith("P-");
        String username="mock-user-"+suffix;
        Reply user=call("POST","/api/v1/users",java.util.Map.of("username",username,"displayName","Mock user","initialPassword","ChangeMe123!","role","PROCESS_MANAGER","processAssignments",List.of(java.util.Map.of("processId",processId,"activeOnShift",true))),admin);
        assertThat(user.status()).as(user.body().toString()).isEqualTo(200);String userId=user.body().get("id").asText();
        assertThat(user.body().has("stations")).isFalse();
        Reply token=call("POST","/api/v1/auth/login",java.util.Map.of("username",username,"password","ChangeMe123!","clientType","PAD"),null);assertThat(token.status()).isEqualTo(200);
        assertThat(token.body().get("processAssignments").get(0).get("stationId").asText()).isEqualTo(oldStation);String pm=token.body().get("accessToken").asText();
        assertThat(call("POST","/api/v1/auth/change-password",java.util.Map.of("currentPassword","ChangeMe123!","newPassword","ReadyForWork123!"),pm).status()).isEqualTo(200);
        processBody.put("stationNumber","MOVED-"+suffix);processBody.put("name","Edited mock process");
        Reply moved=call("PUT","/api/v1/processes/"+processId,processBody,admin);assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(moved.body().get("code").asText()).isEqualTo(code);String station=moved.body().get("station_id").asText();assertThat(station).isNotEqualTo(oldStation);
        Reply me=call("GET","/api/v1/auth/me",null,pm);assertThat(me.body().get("processAssignments").get(0).get("stationNumber").asText()).isEqualTo("MOVED-"+suffix);
        assertThat(call("GET","/api/v1/stations/"+oldStation,null,pm).status()).isEqualTo(403);
        var devices=List.of(java.util.Map.of("name","Device 1","tag",uids[1]),java.util.Map.of("name","Device 2","description","Second device","tag",uids[2]));
        var deviceBody=new java.util.LinkedHashMap<String,Object>();deviceBody.put("name","Mock devices");deviceBody.put("stationNumber","moved-"+suffix);deviceBody.put("mode","devices");deviceBody.put("deviceCount",3);deviceBody.put("devices",devices);
        assertThat(call("POST","/api/v1/processes",deviceBody,admin).status()).isEqualTo(400);
        deviceBody.put("deviceCount",2);Reply deviceProcess=call("POST","/api/v1/processes",deviceBody,admin);assertThat(deviceProcess.status()).as(deviceProcess.body().toString()).isEqualTo(200);
        assertThat(deviceProcess.body().get("deviceCount").asInt()).isEqualTo(2);assertThat(deviceProcess.body().get("station_id").asText()).isEqualTo(station);assertThat(deviceProcess.body().get("devices").get(0).get("type").asText()).isEqualTo("GENERAL");
        String deviceProcessId=deviceProcess.body().get("id").asText();
        var assigned=List.of(java.util.Map.of("processId",processId,"activeOnShift",true),java.util.Map.of("processId",deviceProcessId,"activeOnShift",false));
        assertThat(call("PUT","/api/v1/users/"+userId+"/assignments",java.util.Map.of("processAssignments",assigned),admin).status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/nfc/resolve",java.util.Map.of("uid",uids[1]),pm).status()).isEqualTo(403);
        assigned=List.of(java.util.Map.of("processId",processId,"activeOnShift",true),java.util.Map.of("processId",deviceProcessId,"activeOnShift",true));
        assertThat(call("PUT","/api/v1/users/"+userId+"/assignments",java.util.Map.of("processAssignments",assigned),admin).status()).isEqualTo(200);
        Reply scan=call("POST","/api/v1/nfc/resolve",java.util.Map.of("uid",uids[1]),pm);assertThat(scan.status()).as(scan.body().toString()).isEqualTo(200);assertThat(scan.body().get("processId").asText()).isEqualTo(deviceProcessId);assertThat(scan.body().get("stationId").asText()).isEqualTo(station);
        assertThat(scan.body().get("processAssignments").size()).isEqualTo(1);
        notifications.stationEvent(UUID.fromString(station),"PROCESS_ASSIGNMENT_TEST",java.util.Map.of("stationId",station));
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_user_id=?::uuid and event_type='PROCESS_ASSIGNMENT_TEST'",Integer.class,userId)).isEqualTo(1);
        assertThat(call("DELETE","/api/v1/nfc-tags/"+tags.get(1),null,admin).status()).isEqualTo(409);
        var areaBody=new java.util.LinkedHashMap<String,Object>();areaBody.put("storageNumber","MOCK-STORE-"+suffix);areaBody.put("description","Mock storage");areaBody.put("stationNumber","MOVED-"+suffix);areaBody.put("nfcUid",uids[3]);
        Reply area=call("POST","/api/v1/storage-areas",areaBody,admin);assertThat(area.status()).as(area.body().toString()).isEqualTo(200);assertThat(area.body().get("name").asText()).isEqualTo("MOCK-STORE-"+suffix);assertThat(area.body().get("station_id").asText()).isEqualTo(station);
        String areaId=area.body().get("id").asText();areaBody.put("description","Updated storage");areaBody.remove("nfcUid");areaBody.put("tagId",tags.get(3));
        assertThat(call("PUT","/api/v1/storage-areas/"+areaId,areaBody,admin).status()).isEqualTo(200);
        assertThat(call("DELETE","/api/v1/storage-areas/"+areaId,null,admin).status()).isEqualTo(200);
        Reply unassigned=call("GET","/api/v1/nfc-tags/"+tags.get(3),null,admin);assertThat(unassigned.body().get("status").asText()).isEqualTo("ACTIVE");assertThat(unassigned.body().get("assigned").asBoolean()).isFalse();
        assertThat(call("DELETE","/api/v1/nfc-tags/"+tags.get(3),null,admin).body().get("status").asText()).isEqualTo("DELETED");
        assertThat(call("POST","/api/v1/processes",java.util.Map.of("name","Deleted tag rejected","stationNumber","ORPHAN-"+suffix,"nfcMode","PROCESS","nfcUid",uids[3]),admin).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from stations where code=?",Integer.class,"ORPHAN-"+suffix)).isZero();
        assertThat(call("GET","/api/v1/nfc-tags/statuses",null,admin).body().get("items").size()).isEqualTo(2);
        Reply available=call("GET","/api/v1/nfc-tags?status=ACTIVE&assigned=false",null,admin);for(var tag:available.body().get("items"))assertThat(tag.get("id").asText()).isNotEqualTo(tags.get(3));
        assertThat(call("PATCH","/api/v1/nfc-tags/"+tags.get(3)+"/status",java.util.Map.of("status","ACTIVE"),admin).body().get("status").asText()).isEqualTo("ACTIVE");
        String deviceId=deviceProcess.body().get("devices").get(0).get("id").asText();
        assertThat(call("PUT","/api/v1/machines/"+deviceId,java.util.Map.of("processId",deviceProcessId,"name","Device renamed","nfcUid",uids[1]),admin).status()).isEqualTo(200);
        assertThat(call("DELETE","/api/v1/machines/"+deviceId,null,admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/processes/"+deviceProcessId,null,admin).body().get("deviceCount").asInt()).isEqualTo(1);
        assertThat(call("DELETE","/api/v1/processes/"+deviceProcessId,null,admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/nfc-tags/"+tags.get(2),null,admin).body().get("assigned").asBoolean()).isFalse();
        assertThat(call("PUT","/api/v1/users/"+userId,java.util.Map.of("username",username,"displayName","Edited user","role","PROCESS_MANAGER","processAssignments",List.of(java.util.Map.of("processId",processId,"activeOnShift",true))),admin).status()).isEqualTo(200);
        assertThat(call("DELETE","/api/v1/users/"+userId,null,admin).body().get("enabled").asBoolean()).isFalse();
        assertThat(call("GET","/api/v1/auth/me",null,pm).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select to_regclass('public.user_station_assignments') is null",Boolean.class)).isTrue();
    }

    @Test void v5MigratesProcessShiftFlagsAndTagLifecycleWithoutStationAssignments(){
        String schema="assignment_"+UUID.randomUUID().toString().replace("-","");
        org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("4").load().migrate();
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword());JdbcTemplate old=new JdbcTemplate(ds);
        UUID user=UUID.randomUUID(),stationA=UUID.randomUUID(),stationB=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID(),p3=UUID.randomUUID();
        old.update("insert into users(id,username,display_name,password_hash) values (?,'shift-user','Shift user','test')",user);
        old.update("insert into stations(id,code,name) values (?,'A','A'),(?,'B','B')",stationA,stationB);
        old.update("insert into processes(id,code,name,station_id) values (?,'P1','P1',?),(?,'P2','P2',?),(?,'P3','P3',?)",p1,stationA,p2,stationA,p3,stationB);
        old.update("insert into user_station_assignments(user_id,station_id,active_on_shift) values (?,?,true),(?,?,false)",user,stationA,user,stationB);
        for(UUID process:List.of(p1,p2,p3))old.update("insert into user_process_assignments(user_id,process_id) values (?,?)",user,process);
        old.update("insert into nfc_tags(id,uid,name,active,enabled) values (?,'ACTIVE-UID','Active tag',false,true),(?,'DELETED-UID','Deleted tag',false,false)",UUID.randomUUID(),UUID.randomUUID());
        org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(old.queryForObject("select count(*) from user_process_assignments where active_on_shift",Integer.class)).isEqualTo(2);
        assertThat(old.queryForObject("select active_on_shift from user_process_assignments where process_id=?",Boolean.class,p3)).isFalse();
        assertThat(old.queryForObject("select to_regclass(?) is null",Boolean.class,schema+".user_station_assignments")).isTrue();
        assertThat(old.queryForObject("select status from nfc_tags where uid='ACTIVE-UID'",String.class)).isEqualTo("ACTIVE");
        assertThat(old.queryForObject("select status from nfc_tags where uid='DELETED-UID'",String.class)).isEqualTo("DELETED");
    }

    @Test void upgradesLegacyRolesAndKeepsHistoricalReferences(){
        String schema="migration_"+UUID.randomUUID().toString().replace("-","");
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("3").load();
        flyway.migrate();
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword());
        JdbcTemplate legacy=new JdbcTemplate(ds);
        UUID operator=UUID.randomUUID(),display=UUID.randomUUID(),multi=UUID.randomUUID();
        for(UUID id:List.of(operator,display,multi))legacy.update("insert into users(id,username,display_name,password_hash) values (?,?,?,'test')",id,id.toString(),"Legacy");
        legacy.update("insert into user_roles select ?,id from roles where code='OPERATOR'",operator);
        legacy.update("insert into user_roles select ?,id from roles where code='DISPLAY_DEVICE'",display);
        legacy.update("insert into user_roles select ?,id from roles where code in ('ADMIN','OPERATOR')",multi);
        org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(legacy.queryForObject("select count(*) from roles",Integer.class)).isEqualTo(7);
        assertThat(legacy.queryForObject("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,operator)).isEqualTo("PROCESS_MANAGER");
        assertThat(legacy.queryForObject("select enabled from users where id=?",Boolean.class,display)).isFalse();
        assertThat(legacy.queryForObject("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,multi)).isEqualTo("ADMIN_IT");
    }

    @Test void migrationsBootstrapAdminAndCreateFrozenLotRoute(){
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isGreaterThanOrEqualTo(2);
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        UUID station=UUID.randomUUID(),qcStation=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name,station_type,is_station_zero) values (?,'S0','Station 0','PRODUCTION',true)",station);
        jdbc.update("insert into stations(id,code,name,station_type,is_qc) values (?,'QC','Quality Control','QC',true)",qcStation);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,'P1','Preparation',?)",p1,station);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,'P2','Inspection',?)",p2,qcStation);
        jdbc.update("update processes set nfc_mode='DEVICES' where id=?",p1);jdbc.update("update processes set quality=true where id=?",p2);
        UUID style=production.createStyle("STYLE-IT","integration",actor);
        production.setProcesses(style,List.of(p1,p2));production.release(style);
        UUID lot=production.createLot(style,25,actor);
        UUID pendingLot=production.createLot(style,25,actor);
        assertThat(jdbc.queryForObject("select count(*) from station_visits where lot_id=?",Integer.class,lot)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from process_executions pe join station_visits sv on sv.id=pe.station_visit_id where sv.lot_id=?",Integer.class,lot)).isEqualTo(2);

        UUID trolley=UUID.randomUUID();
        jdbc.update("insert into trolleys(id,code) values (?,'T-IT')",trolley);
        operations.linkTrolley(lot,trolley,actor);
        operations.dispatch(lot,actor);
        UUID firstVisit=jdbc.queryForObject("select sv.id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=? and lr.sequence_no=1",UUID.class,lot);
        UUID firstExecution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,firstVisit);
        UUID firstMachine=UUID.randomUUID();jdbc.update("insert into machines(id,station_id,name,machine_type,process_id) values (?,?,?,?,?)",firstMachine,station,"Washer IT","WASHER",p1);
        nfc.registerAndAssign("MACHINE-P1-IT","MACHINE",firstMachine,actor,"integration test");
        placements.receiveAndPlace(firstVisit,actor,new PlacementService.PlacementCommand(PlacementService.DestinationType.PROCESS_AREA,firstExecution,null));
        assertThat(jdbc.queryForObject("select status from trolleys where id=?",String.class,trolley)).isEqualTo("AVAILABLE");
        machines.transition(firstExecution,"start",null,null,List.of("MACHINE-P1-IT"),actor);
        machines.transition(firstExecution,"complete",null,null,List.of("MACHINE-P1-IT"),actor);
        assertThat(jdbc.queryForObject("select status from machines where id=?",String.class,firstMachine)).isEqualTo("AVAILABLE");
        operations.linkTrolley(lot,trolley,actor);
        operations.dispatch(lot,actor);
        UUID qcVisit=jdbc.queryForObject("select sv.id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=? and lr.sequence_no=2",UUID.class,lot);
        UUID qcExecution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,qcVisit);
        nfc.registerAndAssign("PROC-P2-IT","PROCESS",p2,actor,"integration test");
        placements.receiveAndPlace(qcVisit,actor,new PlacementService.PlacementCommand(PlacementService.DestinationType.PROCESS_AREA,qcExecution,null));
        machines.transition(qcExecution,"start",null,"PROC-P2-IT",List.of(),actor);
        qc.complete(lot,"PASS",25,25,0,null,actor);
        assertThat(jdbc.queryForObject("select status from lots where id=?",String.class,lot)).isEqualTo("COMPLETED");
        var revision=revisions.revise(style,"updated",List.of(p1,p2),30,"Admin correction",StyleRevisionService.Scope.UNSTARTED,actor,"NW");
        assertThat(revision.affectedLots()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from lots where id=?",Integer.class,pendingLot)).isEqualTo(30);
        assertThat(jdbc.queryForObject("select original_quantity from lots where id=?",Integer.class,pendingLot)).isEqualTo(25);
    }
}
