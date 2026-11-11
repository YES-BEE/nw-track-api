package com.yesbee.nwtrack.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;

@RestController @RequestMapping("/api/v1") @Tag(name="Dashboard and displays")
@PreAuthorize("isAuthenticated()")
public class DashboardController {
    private final JdbcTemplate jdbc;private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public DashboardController(JdbcTemplate jdbc,com.yesbee.nwtrack.auth.AccessPolicy access){this.jdbc=jdbc;this.access=access;}
    @GetMapping("/dashboard") public Map<String,Object> summary(){Map<String,Object> result=new LinkedHashMap<>();result.put("styles",counts("select status,count(*) count from styles where id in(select l.style_id from lots l where "+access.lotPredicate("l.id")+") group by status"));result.put("lots",counts("select status,count(*) count from lots l where "+access.lotPredicate("l.id")+" group by status"));result.put("stations",stationWip());result.put("storageOccupancies",jdbc.queryForObject("select count(*) from storage_occupancies where retrieved_at is null and "+access.stationPredicate("station_id")+"",Integer.class));result.put("availableTrolleys",jdbc.queryForObject("select count(*) from trolleys t where active and not exists(select 1 from lot_trolley_assignments a where a.trolley_id=t.id and a.released_at is null)",Integer.class));return result;}
    @GetMapping("/dashboard/lots") public ApiPage<Map<String,Object>> lots(@RequestParam(required=false)String status){String sql=LOT_VIEW+" where "+access.lotPredicate("l.id")+(status==null?" order by l.created_at desc,l.id":" and l.status=? order by l.created_at desc,l.id");return status==null?Pages.query(jdbc,sql):Pages.query(jdbc,sql,status);}
    @GetMapping("/styles/{id}/progress") public Map<String,Object> progress(@PathVariable UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,style_number,status,created_at,released_at from styles where id=?",id));result.put("lots",Pages.query(jdbc,LOT_VIEW+" where l.style_id=? and "+access.lotPredicate("l.id")+" order by l.created_at,l.id",id));return result;}
    @GetMapping("/stations/{id}/work-in-progress") public ApiPage<Map<String,Object>> station(@PathVariable UUID id){return Pages.query(jdbc,LOT_VIEW+" join station_visits sv2 on sv2.lot_id=l.id where sv2.station_id=? and sv2.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH') order by l.created_at,l.id",id);}
    @GetMapping("/reports/lot-history") public ApiPage<Map<String,Object>> history(@RequestParam UUID lotId){return Pages.query(jdbc,"select e.id,e.location_type,e.location_id,e.actor_id,e.occurred_at,e.detail from lot_location_events e where e.lot_id=? order by e.occurred_at,e.id",lotId);}
    @GetMapping("/displays/factory-summary") public Map<String,Object> factoryDisplay(){return summary();}
    @GetMapping("/displays/stations/{id}") public Map<String,Object> stationDisplay(@PathVariable UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,code,name,station_type,is_qc,is_station_zero from stations where id=?",id));result.put("incoming",Pages.query(jdbc,"select sv.id visit_id,l.id lot_id,l.lot_number,s.style_number,sv.dispatched_at from station_visits sv join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id where sv.station_id=? and sv.status='DISPATCHED' order by sv.dispatched_at,sv.id",id));result.put("workInProgress",station(id));return result;}
    private List<Map<String,Object>> counts(String sql){return jdbc.queryForList(sql);}
    private ApiPage<Map<String,Object>> stationWip(){return Pages.query(jdbc,"select s.id,s.code,s.name,count(sv.id) filter(where sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH')) wip from stations s left join station_visits sv on sv.station_id=s.id where "+access.stationPredicate("s.id")+" group by s.id order by s.code");}
    private static final String LOT_VIEW="select l.id,l.lot_number,l.quantity,l.status,l.current_route_sequence,l.current_location_type,l.current_location_id,l.created_at,l.completed_at,s.id style_id,s.style_number,(select st.code from station_visits sv join stations st on st.id=sv.station_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence) current_station,(select p.name from process_executions pe join processes p on p.id=pe.process_id join station_visits sv on sv.id=pe.station_visit_id where sv.lot_id=l.id and pe.status='IN_PROGRESS' limit 1) current_process from lots l join styles s on s.id=l.style_id";
}
