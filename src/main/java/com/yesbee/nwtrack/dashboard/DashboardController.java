package com.yesbee.nwtrack.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

@RestController @RequestMapping("/api/v1") @Tag(name="Dashboard and displays")
@PreAuthorize("isAuthenticated()")
public class DashboardController {
    private final JdbcTemplate jdbc;
    private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public DashboardController(JdbcTemplate jdbc,com.yesbee.nwtrack.auth.AccessPolicy access){this.jdbc=jdbc;this.access=access;}

    @GetMapping("/dashboard")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("snapshotAt",jdbc.queryForObject("select transaction_timestamp()",OffsetDateTime.class));
        result.put("refreshIntervalSeconds",5);
        var styleCounts=counts("select status,count(*) count from styles where id in(select l.style_id from lots l where "+access.lotPredicate("l.id")+") group by status order by status");
        var lotCounts=counts("select status,count(*) count from lots l where "+access.lotPredicate("l.id")+" group by status order by status");
        long stored=jdbc.queryForObject("select count(distinct lot_id) from storage_occupancies where retrieved_at is null and "+access.stationPredicate("station_id")+" and "+access.lotPredicate("lot_id"),Long.class);
        long available=jdbc.queryForObject("select count(*) from trolleys t where active and not exists(select 1 from lot_trolley_assignments a where a.trolley_id=t.id and a.released_at is null)",Long.class);
        String stationSql=stationSql();
        var workload=jdbc.queryForMap("select coalesce(sum(wip),0)::bigint total,coalesce(max(wip),0)::bigint highest from ("+stationSql+") station_counts");
        var highest=jdbc.queryForList("select id,code,name,wip from ("+stationSql+") station_counts where wip>0 order by wip desc,code,id limit 1");
        long total=lotCounts.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum();
        long completed=count(lotCounts,"COMPLETED"),cancelled=count(lotCounts,"CANCELLED");
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("totalLots",total);metrics.put("openLots",total-completed-cancelled);
        metrics.put("completedLots",completed);
        metrics.put("stylesWithLots",styleCounts.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum());
        metrics.put("storedLots",stored);metrics.put("availableTrolleys",available);
        metrics.put("completedSharePercent",total==0?BigDecimal.ZERO:BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),2,RoundingMode.HALF_UP));
        metrics.put("inTransitLots",count(lotCounts,"DISPATCHED"));metrics.put("readyToStartLots",count(lotCounts,"READY"));
        metrics.put("totalStationLotVisits",workload.get("total"));metrics.put("highestStationWorkloadCount",workload.get("highest"));
        metrics.put("highestStationWorkload",highest.isEmpty()?null:highest.getFirst());
        result.put("metrics",metrics);result.put("styles",styleCounts);result.put("lots",lotCounts);
        result.put("stations",stationWip());result.put("storageOccupancies",stored);result.put("availableTrolleys",available);
        return result;
    }
    private long count(List<Map<String,Object>> rows,String status){return rows.stream().filter(row->status.equals(row.get("status"))).mapToLong(row->((Number)row.get("count")).longValue()).sum();}

    @GetMapping({"/dashboard/lots","/displays/lots"})
    public ApiPage<Map<String,Object>> lots(@RequestParam(required=false)String status){
        String sql=LOT_VIEW+" where "+access.lotPredicate("l.id")+(status==null?" order by l.created_at desc,l.id":" and l.status=? order by l.created_at desc,l.id");
        return status==null?Pages.query(jdbc,sql):Pages.query(jdbc,sql,status);
    }
    @GetMapping({"/dashboard/lots/{id}","/displays/lots/{id}"})
    public Map<String,Object> lotDetail(@PathVariable UUID id){access.lot(id);return jdbc.queryForMap(LOT_VIEW+" where l.id=?",id);}
    @GetMapping("/styles/{id}/progress") public Map<String,Object> progress(@PathVariable UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,style_number,status,created_at,released_at from styles where id=?",id));result.put("lots",Pages.query(jdbc,LOT_VIEW+" where l.style_id=? and "+access.lotPredicate("l.id")+" order by l.created_at,l.id",id));return result;}
    @GetMapping("/stations/{id}/work-in-progress") public ApiPage<Map<String,Object>> station(@PathVariable UUID id){return Pages.query(jdbc,LOT_VIEW+" join station_visits sv2 on sv2.lot_id=l.id where sv2.station_id=? and sv2.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH') and "+access.lotPredicate("l.id")+" and l.status not in ('COMPLETED','CANCELLED') order by l.created_at,l.id",id);}
    @GetMapping("/reports/lot-history") public ApiPage<Map<String,Object>> history(@RequestParam UUID lotId){return Pages.query(jdbc,"select e.id,e.location_type,e.location_id,e.actor_id,e.occurred_at,e.detail from lot_location_events e where e.lot_id=? order by e.occurred_at,e.id",lotId);}
    @GetMapping("/displays/factory-summary")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> factoryDisplay(){return summary();}
    @GetMapping("/displays/stations/{id}") public Map<String,Object> stationDisplay(@PathVariable UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,code,name,station_type,is_qc,is_station_zero from stations where id=?",id));result.put("incoming",Pages.query(jdbc,"select sv.id visit_id,l.id lot_id,l.lot_number,s.style_number,sv.dispatched_at from station_visits sv join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id where sv.station_id=? and sv.status='DISPATCHED' and l.status not in ('COMPLETED','CANCELLED') and "+access.lotPredicate("l.id")+" order by sv.dispatched_at,sv.id",id));result.put("workInProgress",station(id));return result;}
    private List<Map<String,Object>> counts(String sql){return jdbc.queryForList(sql);}
    private String stationSql(){return "select s.id,s.code,s.name,count(sv.id) wip from stations s left join station_visits sv on sv.station_id=s.id and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH') and exists(select 1 from lots scoped_l where scoped_l.id=sv.lot_id and scoped_l.status not in ('COMPLETED','CANCELLED') and "+access.lotPredicate("scoped_l.id")+") where "+access.stationPredicate("s.id")+" group by s.id";}
    private ApiPage<Map<String,Object>> stationWip(){return Pages.query(jdbc,"select station_counts.*,coalesce(round(100.0*wip/nullif(sum(wip) over (),0),2),0) share_percent from ("+stationSql()+") station_counts order by code,id");}
    private static final String LOT_VIEW="""
        select l.id,l.lot_number,l.quantity,l.status,l.current_route_sequence,l.current_location_type,l.current_location_id,l.created_at,l.completed_at,
         s.id style_id,s.style_number,
         (select st.code from station_visits sv join stations st on st.id=sv.station_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence) current_station,
         cp.process_id current_process_id,cp.name current_process,cp.status current_process_status,
         case l.current_location_type
          when 'TROLLEY' then (select t.code from trolleys t where t.id=l.current_location_id)
          when 'PROCESS_AREA' then (select coalesce(nullif(st.name,''),st.code) from stations st where st.id=l.current_location_id)
          when 'STORAGE' then (select coalesce(nullif(sl.name,''),sl.code) from storage_locations sl where sl.id=l.current_location_id)
          when 'COMPLETED' then 'Completed' when 'CANCELLED' then 'Cancelled' else null end location_label
        from lots l join styles s on s.id=l.style_id
        left join lateral (
         select pe.process_id,p.name,pe.status from process_executions pe join processes p on p.id=pe.process_id
         join station_visits sv on sv.id=pe.station_visit_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id
         where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence and pe.status not in ('COMPLETED','SKIPPED')
         order by case pe.status when 'IN_PROGRESS' then 0 when 'PAUSED' then 1 when 'BLOCKED' then 2 else 3 end,pe.sequence_no limit 1
        ) cp on true
        """;
}
