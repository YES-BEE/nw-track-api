package com.yesbee.nwtrack.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController @RequestMapping("/api/v1") @Tag(name="Dashboard and displays")
@PreAuthorize("isAuthenticated()")
public class DashboardController {
    private final JdbcTemplate jdbc;
    private final com.yesbee.nwtrack.auth.AccessPolicy access;
    private final ZoneId factoryZone;
    public DashboardController(JdbcTemplate jdbc,com.yesbee.nwtrack.auth.AccessPolicy access,
            @Value("${nwtrack.factory-timezone:Asia/Kolkata}") String factoryTimezone){
        this.jdbc=jdbc;this.access=access;this.factoryZone=ZoneId.of(factoryTimezone);
    }

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
        result.put("productionTrend",productionTrend());
        return result;
    }
    private long count(List<Map<String,Object>> rows,String status){return rows.stream().filter(row->status.equals(row.get("status"))).mapToLong(row->((Number)row.get("count")).longValue()).sum();}

    @GetMapping({"/dashboard/lots","/displays/lots"})
    public ApiPage<Map<String,Object>> lots(@ModelAttribute LotFilters filters){return filteredLots(filters);}
    @GetMapping({"/dashboard/lots/{id}","/displays/lots/{id}"})
    public Map<String,Object> lotDetail(@PathVariable UUID id){access.lot(id);return jdbc.queryForMap(LOT_VIEW+" where l.id=?",id);}
    @GetMapping("/reports/style-progress")
    public ApiPage<Map<String,Object>> styleProgress(){
        return Pages.query(jdbc,"select s.id,s.style_number,s.status,s.created_at,s.released_at,"+
            "count(v.id) lot_count,count(v.id) filter(where v.status='COMPLETED') completed_lots,"+
            "coalesce(round(avg(v.progress_percent),2),0) progress_percent from styles s left join ("+
            LOT_VIEW+" where "+access.lotPredicate("l.id")+") v on v.style_id=s.id where "+
            access.stylePredicate("s")+" group by s.id order by s.created_at desc,s.id");
    }
    @GetMapping("/reports/lot-progress")
    public ApiPage<Map<String,Object>> lotProgress(@ModelAttribute LotFilters filters){return filteredLots(filters);}

    private String filteredSource(LotFilters filters,List<Object> args){
        return " from ("+LOT_VIEW+" where "+access.lotPredicate("l.id")+") v"+filters.where(args);
    }
    private ApiPage<Map<String,Object>> filteredLots(LotFilters filters){
        List<Object> args=new ArrayList<>();
        return Pages.query(jdbc,"select v.*"+filteredSource(filters,args)+" order by v.created_at desc,v.id",args.toArray());
    }

    @GetMapping({"/dashboard/filter-options/{type}","/reports/filter-options/{type}"})
    public ApiPage<Map<String,Object>> filterOptions(@PathVariable String type,@ModelAttribute LotFilters filters){
        List<Object> args=new ArrayList<>();
        String source=filteredSource(filters,args);
        String sql=switch(type){
            case "lots" -> "select v.id,v.lot_number,v.style_id,v.style_number"+source+" order by v.lot_number,v.id";
            case "styles" -> "select distinct v.style_id id,v.style_number"+source+" order by style_number,id";
            case "stations" -> {
                if(filters.routeScope()==LotFilters.RouteScope.CURRENT)
                    yield "select distinct v.current_station_id id,v.current_station code"+source+" and v.current_station_id is not null order by code,id";
                String matchingProcess="";
                if(filters.processId()!=null){
                    matchingProcess=" where exists(select 1 from process_executions option_pe where option_pe.station_visit_id=sv.id and option_pe.process_id=? and not (option_pe.status='SKIPPED' and coalesce(option_pe.reason,'') like 'Superseded by Manager route revision:%'))";
                    args.add(filters.processId());
                }
                yield "select distinct st.id,st.code,st.name from stations st join station_visits sv on sv.station_id=st.id join (select v.id"+source+") eligible on eligible.id=sv.lot_id"+matchingProcess+" order by st.code,st.id";
            }
            case "processes" -> {
                if(filters.routeScope()==LotFilters.RouteScope.CURRENT)
                    yield "select distinct v.current_process_id id,v.current_process name"+source+" and v.current_process_id is not null order by name,id";
                String matchingStation="";
                if(filters.stationId()!=null){matchingStation=" and sv.station_id=?";args.add(filters.stationId());}
                yield "select distinct p.id,p.code,p.name from processes p join process_executions pe on pe.process_id=p.id join station_visits sv on sv.id=pe.station_visit_id join (select v.id"+source+") eligible on eligible.id=sv.lot_id where not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')"+matchingStation+" order by p.name,p.id";
            }
            default -> throw new IllegalArgumentException("type must be lots, styles, stations or processes");
        };
        return Pages.query(jdbc,sql,args.toArray());
    }
    @GetMapping("/stations/{id}/work-in-progress") public ApiPage<Map<String,Object>> station(@PathVariable UUID id){return Pages.query(jdbc,LOT_VIEW+" join station_visits sv2 on sv2.lot_id=l.id where sv2.station_id=? and sv2.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH') and "+access.lotPredicate("l.id")+" and l.status not in ('COMPLETED','CANCELLED') order by l.created_at,l.id",id);}
    @GetMapping("/displays/factory-summary")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> factoryDisplay(){return summary();}
    @GetMapping("/displays/stations/{id}") public Map<String,Object> stationDisplay(@PathVariable UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("select id,code,name,station_type,is_qc,is_station_zero from stations where id=?",id));result.put("incoming",Pages.query(jdbc,"select sv.id visit_id,l.id lot_id,l.lot_number,s.style_number,sv.dispatched_at from station_visits sv join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id where sv.station_id=? and sv.status='DISPATCHED' and l.status not in ('COMPLETED','CANCELLED') and "+access.lotPredicate("l.id")+" order by sv.dispatched_at,sv.id",id));result.put("workInProgress",station(id));return result;}
    private List<Map<String,Object>> counts(String sql){return jdbc.queryForList(sql);}
    private Map<String,Object> productionTrend(){
        var now=jdbc.queryForObject("select transaction_timestamp()",OffsetDateTime.class).atZoneSameInstant(factoryZone);
        var start=now.toLocalDate().atStartOfDay(factoryZone);
        var points=jdbc.queryForList("""
            with visible_lots as (
              select l.completed_at,l.cancelled_at,
                (select min(sv.dispatched_at) from station_visits sv where sv.lot_id=l.id) started_at
              from lots l where %s
            ), buckets as (
              select tick bucket_start,least(tick+interval '1 hour',?::timestamptz) bucket_end
              from generate_series(?::timestamptz,?::timestamptz,interval '1 hour') tick
            )
            select b.bucket_start,b.bucket_end,
              (select count(*) from visible_lots l where l.completed_at>=b.bucket_start
                 and l.completed_at<b.bucket_start+interval '1 hour' and l.completed_at<=?::timestamptz) completed,
              (select count(*) from visible_lots l where l.started_at<=b.bucket_end
                 and (l.completed_at is null or l.completed_at>b.bucket_end)
                 and (l.cancelled_at is null or l.cancelled_at>b.bucket_end)) in_progress
            from buckets b order by b.bucket_start
            """.formatted(access.lotPredicate("l.id")),now.toOffsetDateTime(),start.toOffsetDateTime(),
                now.withMinute(0).withSecond(0).withNano(0).toOffsetDateTime(),now.toOffsetDateTime());
        return Map.of("date",start.toLocalDate(),"timezone",factoryZone.getId(),"interval","HOUR","points",points);
    }
    private String stationSql(){return "select s.id,s.code,s.name,count(sv.id) wip from stations s left join station_visits sv on sv.station_id=s.id and sv.status in ('DISPATCHED','RECEIVED','ACTIVE','READY_FOR_DISPATCH') and exists(select 1 from lots scoped_l where scoped_l.id=sv.lot_id and scoped_l.status not in ('COMPLETED','CANCELLED') and "+access.lotPredicate("scoped_l.id")+") where "+access.stationPredicate("s.id")+" group by s.id";}
    private ApiPage<Map<String,Object>> stationWip(){return Pages.query(jdbc,"select station_counts.*,coalesce(round(100.0*wip/nullif(sum(wip) over (),0),2),0) share_percent from ("+stationSql()+") station_counts order by code,id");}
    private static final String LOT_VIEW="""
        select l.id,l.lot_number,l.quantity,l.status,l.current_route_sequence,l.current_location_type,l.current_location_id,l.created_at,l.completed_at,
         s.id style_id,s.style_number,
         case when l.status='COMPLETED' then 100.00 else coalesce(round(100.0*pr.completed_processes/nullif(pr.total_processes,0),2),0) end progress_percent,
         pr.completed_processes,pr.total_processes,
         (select sv.station_id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence) current_station_id,
         (select st.code from station_visits sv join stations st on st.id=sv.station_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence) current_station,
         cp.process_id current_process_id,cp.name current_process,cp.status current_process_status,
         case l.current_location_type
          when 'TROLLEY' then (select t.code from trolleys t where t.id=l.current_location_id)
          when 'PROCESS_AREA' then (select coalesce(nullif(st.name,''),st.code) from stations st where st.id=l.current_location_id)
          when 'STORAGE' then (select coalesce(nullif(sl.name,''),sl.code) from storage_locations sl where sl.id=l.current_location_id)
          when 'COMPLETED' then 'Completed' when 'CANCELLED' then 'Cancelled' else null end location_label
        from lots l join styles s on s.id=l.style_id
        left join lateral (
         select count(*) total_processes,count(*) filter(where pe.status in ('COMPLETED','SKIPPED')) completed_processes
         from process_executions pe join station_visits sv on sv.id=pe.station_visit_id
         where sv.lot_id=l.id and not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')
        ) pr on true
        left join lateral (
         select pe.process_id,p.name,pe.status from process_executions pe join processes p on p.id=pe.process_id
         join station_visits sv on sv.id=pe.station_visit_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id
         where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence and pe.status not in ('COMPLETED','SKIPPED')
         order by case pe.status when 'IN_PROGRESS' then 0 when 'PAUSED' then 1 when 'BLOCKED' then 2 else 3 end,pe.sequence_no limit 1
        ) cp on true
        """;
}
