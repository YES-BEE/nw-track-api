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
    private record Reply(int status,com.fasterxml.jackson.databind.JsonNode body){}
    private Reply call(String method,String path,Object body,String token)throws Exception{
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var reply=java.net.http.HttpClient.newHttpClient().send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(),reply.body().isBlank()?json.nullNode():json.readTree(reply.body()));
    }
    private String login(String user,String channel)throws Exception{
        Reply result=call("POST","/api/v1/auth/login",java.util.Map.of("username",user,"password","ChangeMe123!","clientType",channel),null);
        assertThat(result.status()).as(result.body().toString()).isEqualTo(200);return result.body().get("accessToken").asText();
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
        var assignments=java.util.Map.of("stations",List.of(java.util.Map.of("stationId",station,"activeOnShift",true),java.util.Map.of("stationId",otherStation,"activeOnShift",true)),"processIds",List.of(processId));
        String username="pm-"+suffix;
        Reply user=call("POST","/api/v1/users",java.util.Map.of("username",username,"displayName","Process manager","initialPassword","ChangeMe123!","role","PROCESS_MANAGER","assignments",assignments),admin);
        assertThat(user.status()).as(user.body().toString()).isEqualTo(200);assertThat(user.body().get("stations").size()).isEqualTo(2);
        String pm=login(username,"PAD");
        Reply own=call("GET","/api/v1/stations",null,pm);assertThat(own.status()).isEqualTo(200);assertThat(own.body().size()).isEqualTo(2);
        assertThat(call("GET","/api/v1/users",null,pm).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/access-roles",null,admin).body().size()).isEqualTo(7);
        assertThat(call("POST","/api/v1/access-roles",java.util.Map.of("code","CUSTOM"),admin).status()).isEqualTo(405);
        Reply supervisor=call("POST","/api/v1/users",java.util.Map.of("username","sup-"+suffix,"displayName","Supervisor","initialPassword","ChangeMe123!","role","SUPERVISOR"),admin);
        assertThat(supervisor.status()).isEqualTo(200);
        assertThat(call("POST","/api/v1/auth/login",java.util.Map.of("username","sup-"+suffix,"password","ChangeMe123!","clientType","PC"),null).status()).isEqualTo(403);
        String sup=login("sup-"+suffix,"PAD");assertThat(call("GET","/api/v1/stations/"+station,null,sup).status()).isEqualTo(403);
        assertThat(call("GET","/api/v1/stations/%"+Integer.toHexString(station.charAt(0))+station.substring(1),null,sup).status()).isEqualTo(403);
        // Role permission edits are effective for an already-issued token.
        var permissions=java.util.Set.of("OPERATE","QC","REPORTS","TRANSPORT","READ");
        assertThat(call("PUT","/api/v1/access-roles/PROCESS_MANAGER",java.util.Map.of("pcAccess",true,"padAccess",true,"permissions",List.of("READ")),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/dashboard",null,pm).status()).isEqualTo(403);
        assertThat(call("PUT","/api/v1/access-roles/PROCESS_MANAGER",java.util.Map.of("pcAccess",true,"padAccess",true,"permissions",permissions),admin).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/dashboard",null,pm).status()).isEqualTo(200);
        assertThat(call("GET","/api/v1/sync/bootstrap",null,pm).body().get("assignments").size()).isEqualTo(2);
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
        UUID lot=production.createLot(style,"QLOT-"+suffix,10,actor);
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
    }

    @Test void offlineEventsEnforceScansAndCommitIdempotently()throws Exception{
        String admin=login("admin","PC"),suffix=UUID.randomUUID().toString().substring(0,8),uid="OFF-"+suffix.toUpperCase();
        assertThat(call("POST","/api/v1/nfc-tags",java.util.Map.of("name","Offline process","uid",uid),admin).status()).isEqualTo(200);
        Reply process=call("POST","/api/v1/processes",java.util.Map.of("code","OFF-"+suffix,"name","Offline work","stationReference","OFF-"+suffix,"nfcMode","PROCESS","nfcUid",uid,"quality",false),admin);
        assertThat(process.status()).as(process.body().toString()).isEqualTo(200);
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class),processId=UUID.fromString(process.body().get("id").asText());
        UUID style=production.createStyle("OFF-"+suffix,"No final QC",actor);production.setProcesses(style,List.of(processId));production.release(style);
        UUID lot=production.createLot(style,"OFFLOT-"+suffix,5,actor),visit=jdbc.queryForObject("select id from station_visits where lot_id=?",UUID.class,lot),execution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,visit);
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
        UUID lot=production.createLot(style,"LOT-IT",25,actor);
        UUID pendingLot=production.createLot(style,"LOT-IT-PENDING",25,actor);
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
