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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresWorkflowIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);registry.add("spring.datasource.password",postgres::getPassword);registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");registry.add("spring.flyway.enabled",()->"true");registry.add("nwtrack.outbox.enabled",()->"false");}
    @Autowired JdbcTemplate jdbc; @Autowired ProductionService production; @Autowired OperationsService operations; @Autowired PlacementService placements; @Autowired QcService qc; @Autowired NfcAssetService nfc; @Autowired ProcessMachineService machines; @Autowired StyleRevisionService revisions;

    @Test void migrationsBootstrapAdminAndCreateFrozenLotRoute(){
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isGreaterThanOrEqualTo(2);
        UUID actor=jdbc.queryForObject("select id from users where username='admin'",UUID.class);
        UUID station=UUID.randomUUID(),qcStation=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID();
        jdbc.update("insert into stations(id,code,name,station_type,is_station_zero) values (?,'S0','Station 0','PRODUCTION',true)",station);
        jdbc.update("insert into stations(id,code,name,station_type,is_qc) values (?,'QC','Quality Control','QC',true)",qcStation);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,'P1','Preparation',?)",p1,station);
        jdbc.update("insert into processes(id,code,name,station_id) values (?,'P2','Inspection',?)",p2,qcStation);
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
        UUID firstMachine=UUID.randomUUID();jdbc.update("insert into machines(id,station_id,name,machine_type) values (?,?,?,?)",firstMachine,station,"Washer IT","WASHER");
        nfc.registerAndAssign("PROC-P1-IT","PROCESS",p1,actor,"integration test");nfc.registerAndAssign("MACHINE-P1-IT","MACHINE",firstMachine,actor,"integration test");
        placements.receiveAndPlace(firstVisit,actor,new PlacementService.PlacementCommand(PlacementService.DestinationType.PROCESS_AREA,firstExecution,null));
        assertThat(jdbc.queryForObject("select status from trolleys where id=?",String.class,trolley)).isEqualTo("AVAILABLE");
        machines.transition(firstExecution,"start",null,"PROC-P1-IT",List.of("MACHINE-P1-IT"),actor);
        machines.transition(firstExecution,"complete",null,"PROC-P1-IT",List.of("MACHINE-P1-IT"),actor);
        assertThat(jdbc.queryForObject("select status from machines where id=?",String.class,firstMachine)).isEqualTo("AVAILABLE");
        operations.linkTrolley(lot,trolley,actor);
        operations.dispatch(lot,actor);
        UUID qcVisit=jdbc.queryForObject("select sv.id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=? and lr.sequence_no=2",UUID.class,lot);
        UUID qcExecution=jdbc.queryForObject("select id from process_executions where station_visit_id=?",UUID.class,qcVisit);
        UUID qcMachine=UUID.randomUUID();jdbc.update("insert into machines(id,station_id,name,machine_type) values (?,?,?,?)",qcMachine,qcStation,"QC Device IT","QC_DEVICE");
        nfc.registerAndAssign("PROC-P2-IT","PROCESS",p2,actor,"integration test");nfc.registerAndAssign("MACHINE-P2-IT","MACHINE",qcMachine,actor,"integration test");
        placements.receiveAndPlace(qcVisit,actor,new PlacementService.PlacementCommand(PlacementService.DestinationType.PROCESS_AREA,qcExecution,null));
        machines.transition(qcExecution,"start",null,"PROC-P2-IT",List.of("MACHINE-P2-IT"),actor);
        machines.transition(qcExecution,"complete",null,"PROC-P2-IT",List.of("MACHINE-P2-IT"),actor);
        qc.complete(lot,"PASS",25,25,0,null,actor);
        assertThat(jdbc.queryForObject("select status from lots where id=?",String.class,lot)).isEqualTo("COMPLETED");
        var revision=revisions.revise(style,"updated",List.of(p1,p2),30,"Admin correction",StyleRevisionService.Scope.UNSTARTED,actor,"SUPER_ADMIN");
        assertThat(revision.affectedLots()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from lots where id=?",Integer.class,pendingLot)).isEqualTo(30);
        assertThat(jdbc.queryForObject("select original_quantity from lots where id=?",Integer.class,pendingLot)).isEqualTo(25);
    }
}
