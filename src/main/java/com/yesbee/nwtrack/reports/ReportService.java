package com.yesbee.nwtrack.reports;

import com.yesbee.nwtrack.auth.AccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportService {
    private static final String NOT_SUPERSEDED=
        "not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')";
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final ZoneId factoryZone;
    private final String factoryName;
    private final ReportHistoryService history;

    public ReportService(JdbcTemplate jdbc,AccessPolicy access,ReportHistoryService history,
            @Value("${nwtrack.factory-timezone:Asia/Kolkata}") String factoryTimezone,
            @Value("${nwtrack.factory-name:NW Track}") String factoryName){
        this.jdbc=jdbc;this.access=access;this.history=history;this.factoryZone=ZoneId.of(factoryTimezone);this.factoryName=factoryName;
    }

    public List<Map<String,Object>> catalog(){
        Map<String,ReportModels.Type> types=new HashMap<>();
        for(var type:ReportModels.Type.values())types.put(type.key,type);
        return jdbc.queryForList("""
            select id,report_key,display_name,description,display_order
            from report_categories where active order by display_order,report_key
            """).stream().map(row->{
                String key=(String)row.get("report_key");
                ReportModels.Type type=types.get(key);
                if(type==null)throw new IllegalStateException("Active report category has no implementation: "+key);
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("id",row.get("id"));result.put("key",key);result.put("name",row.get("display_name"));
                result.put("description",row.get("description"));result.put("displayOrder",row.get("display_order"));
                result.put("formats",type.formats.stream().map(format->format.name().toLowerCase(Locale.ROOT)).sorted().toList());
                result.put("filters",type.filters.stream().sorted().toList());
                result.put("requiredParameters",List.of("from","to","format"));
                return result;
            }).toList();
    }

    public ReportModels.Category category(UUID id){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select id,report_key,display_name from report_categories where id=? and active
            """,id);
        if(rows.isEmpty())throw new IllegalArgumentException("Report is unavailable: "+id);
        Map<String,Object> row=rows.getFirst();
        return new ReportModels.Category((UUID)row.get("id"),
            ReportModels.Type.parse((String)row.get("report_key")),(String)row.get("display_name"));
    }

    public ReportModels.Prepared prepare(ReportModels.Category category,ReportModels.Request request){
        if(request.type()==ReportModels.Type.STORAGE&&request.status()!=null
            &&!Set.of("WAITING","RETRIEVED").contains(request.status()))
            throw new IllegalArgumentException("status must be WAITING or RETRIEVED for storage-waiting-time");
        if(request.type()==ReportModels.Type.TROLLEY&&request.status()!=null
            &&!Set.of("AVAILABLE","IN_USE","INACTIVE").contains(request.status()))
            throw new IllegalArgumentException("status must be AVAILABLE, IN_USE or INACTIVE for trolley-utilization");
        if(request.type()==ReportModels.Type.DELAY&&request.status()!=null
            &&!Set.of("ON_TIME","DELAYED").contains(request.status()))
            throw new IllegalArgumentException("status must be ON_TIME or DELAYED for delay-bottleneck");
        return new ReportModels.Prepared(category,request,access.analyticsLotPredicate("l.id"),
            access.analyticsStationPredicate("st.id"),access.analyticsProcessPredicate("p.id"));
    }

    public UUID recordRequest(ReportModels.Prepared prepared){
        return history.requested(prepared);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public void write(ReportModels.Prepared prepared,UUID exportId,OutputStream output) throws IOException {
        try{
            OffsetDateTime snapshot=jdbc.queryForObject("select transaction_timestamp()",OffsetDateTime.class);
            OffsetDateTime start=prepared.request().from().atStartOfDay(factoryZone).toOffsetDateTime();
            OffsetDateTime end=prepared.request().to().plusDays(1).atStartOfDay(factoryZone).toOffsetDateTime();
            var sections=sections(prepared,start,end,snapshot);
            var document=new ReportModels.Document(prepared.category().title(),factoryName,factoryZone.getId(),
                prepared.request().from(),prepared.request().to(),snapshot,sections);
            ReportExporters.write(prepared.request().format(),document,this::stream,output);
            history.completed(exportId);
        }catch(IOException|RuntimeException ex){
            history.failed(exportId,ex);
            throw ex;
        }
    }

    private void stream(ReportModels.Query query,List<ReportModels.Column> columns,
                        ReportModels.RowConsumer consumer) throws IOException {
        try{
            jdbc.query(connection->{
                PreparedStatement statement=connection.prepareStatement(query.sql(),
                    ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
                statement.setFetchSize(1_000);
                for(int i=0;i<query.args().size();i++)statement.setObject(i+1,query.args().get(i));
                return statement;
            },resultSet->{
                Map<String,Object> row=new LinkedHashMap<>();
                for(var column:columns)row.put(column.key(),resultSet.getObject(column.key()));
                try{consumer.accept(row);}catch(IOException ex){throw new UncheckedIOException(ex);}
            });
        }catch(UncheckedIOException ex){
            throw ex.getCause();
        }
    }

    private List<ReportModels.Section> sections(ReportModels.Prepared prepared,OffsetDateTime start,
                                                 OffsetDateTime end,OffsetDateTime snapshot){
        return switch(prepared.request().type()){
            case STYLE_LOT -> styleLot(prepared,start,end,snapshot);
            case STATION_TIME -> stationTime(prepared,start,end,snapshot);
            case DELAY -> delay(prepared,start,end,snapshot);
            case MOVEMENT -> movement(prepared,start,end,snapshot);
            case STORAGE -> storage(prepared,start,end,snapshot);
            case TROLLEY -> trolley(prepared,start,end,snapshot);
            case OPERATOR -> operator(prepared,start,end,snapshot);
            case DAILY -> daily(prepared);
        };
    }

    private List<ReportModels.Section> styleLot(ReportModels.Prepared prepared,OffsetDateTime start,
                                                OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=new SqlFilter();
        filter.add("l.created_at>=? and l.created_at<?",start,end);
        filter.raw(prepared.lotPredicate());
        filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        filter.add("l.status=?",request.status());
        filter.add("exists(select 1 from station_visits fsv where fsv.lot_id=l.id and fsv.station_id=?)",request.stationId());
        filter.add("exists(select 1 from station_visits fsv join process_executions fpe on fpe.station_visit_id=fsv.id where fsv.lot_id=l.id and fpe.process_id=?)",request.processId());
        String base=styleLotBase(filter.sql());
        List<Object> args=with(snapshot,filter.args());
        var summary=query(base+"""
            select count(*) total_lots,
             count(*) filter(where status='COMPLETED') completed_lots,
             count(*) filter(where status not in ('COMPLETED','CANCELLED')) open_lots,
             coalesce(round(avg(progress_percent),2),0) average_progress_percent,
             coalesce(round(avg(total_duration_minutes) filter(where total_duration_minutes is not null),2),0) average_duration_minutes
            from report_rows
            """,args);
        var details=query(base+"""
            select style_number,lot_number,created_at,total_processes,completed_processes,progress_percent,
             current_station,status,start_time,completion_time,total_duration_minutes
            from report_rows order by created_at,lot_number
            """,args);
        return List.of(section("Summary",summary,false,
                "total_lots","Total Lots","completed_lots","Completed Lots","open_lots","Open Lots",
                "average_progress_percent","Average Progress %","average_duration_minutes","Average Duration (minutes)"),
            section("Lot Details",details,true,
                "style_number","Style No.","lot_number","Lot No.","created_at","Created At",
                "total_processes","Total Processes","completed_processes","Completed Processes",
                "progress_percent","Progress %","current_station","Current Station","status","Status",
                "start_time","Start Time","completion_time","Completion Time",
                "total_duration_minutes","Total Duration (minutes)"));
    }

    private String styleLotBase(String filter){
        return """
            with params as (select ?::timestamptz snapshot_at),
            report_rows as (
             select s.style_number,l.lot_number,l.created_at,l.status,l.completed_at completion_time,
              coalesce(pr.total_processes,0) total_processes,coalesce(pr.completed_processes,0) completed_processes,
              case when l.status='COMPLETED' then 100.00
                   else coalesce(round(100.0*pr.completed_processes/nullif(pr.total_processes,0),2),0) end progress_percent,
              current_station.code current_station,started.start_time,
              case when started.start_time is null then null
                   else round(extract(epoch from (coalesce(l.completed_at,params.snapshot_at)-started.start_time))/60.0,2) end total_duration_minutes
             from lots l join styles s on s.id=l.style_id cross join params
             left join lateral (
              select count(*) total_processes,count(*) filter(where pe.status in ('COMPLETED','SKIPPED')) completed_processes
              from process_executions pe join station_visits sv on sv.id=pe.station_visit_id
              where sv.lot_id=l.id and %s
             ) pr on true
             left join lateral (
              select min(sv.dispatched_at) start_time from station_visits sv where sv.lot_id=l.id
             ) started on true
             left join lateral (
              select st.code from station_visits sv join stations st on st.id=sv.station_id
              join lot_route_entries lr on lr.id=sv.lot_route_entry_id
              where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence limit 1
             ) current_station on true
             where %s
            )
            """.formatted(NOT_SUPERSEDED,filter);
    }

    private List<ReportModels.Section> stationTime(ReportModels.Prepared prepared,OffsetDateTime start,
                                                   OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=processFilter(prepared,start,end);
        filter.add("pe.operator_id=?",request.operatorId());filter.add("pe.status=?",request.status());
        String base=processBase(filter.sql(),true);
        List<Object> args=with(snapshot,filter.args());
        var summary=query(base+"""
            select station_code,station_name,process_code,process_name,count(*) executions,
             round(avg(duration_minutes),2) average_minutes,round(min(duration_minutes),2) minimum_minutes,
             round(max(duration_minutes),2) maximum_minutes
            from report_rows where duration_minutes is not null
            group by station_code,station_name,process_code,process_name
            order by station_code,process_code
            """,args);
        var details=query(base+"""
            select station_code,station_name,process_code,process_name,style_number,lot_number,status,
             operator_name,start_time,end_time,duration_minutes,
             round(avg(duration_minutes) over(partition by station_id,process_id),2) group_average_minutes,
             round(min(duration_minutes) over(partition by station_id,process_id),2) group_minimum_minutes,
             round(max(duration_minutes) over(partition by station_id,process_id),2) group_maximum_minutes
            from report_rows order by start_time,lot_number
            """,args);
        return List.of(section("Station Summary",summary,false,
                "station_code","Station","station_name","Station Name","process_code","Process",
                "process_name","Process Name","executions","Executions","average_minutes","Average (minutes)",
                "minimum_minutes","Minimum (minutes)","maximum_minutes","Maximum (minutes)"),
            section("Processing Details",details,true,
                "station_code","Station","station_name","Station Name","process_code","Process",
                "process_name","Process Name","style_number","Style No.","lot_number","Lot No.",
                "status","Status","operator_name","Operator","start_time","Start Time","end_time","End Time",
                "duration_minutes","Duration (minutes)","group_average_minutes","Average (minutes)",
                "group_minimum_minutes","Minimum (minutes)","group_maximum_minutes","Maximum (minutes)"));
    }

    private SqlFilter processFilter(ReportModels.Prepared prepared,OffsetDateTime start,OffsetDateTime end){
        var request=prepared.request();var filter=new SqlFilter();
        filter.add("pe.started_at>=? and pe.started_at<?",start,end);
        filter.raw(prepared.lotPredicate());filter.raw(prepared.stationPredicate());filter.raw(prepared.processPredicate());
        filter.raw(NOT_SUPERSEDED);
        filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        filter.add("st.id=?",request.stationId());filter.add("p.id=?",request.processId());
        return filter;
    }

    private String processBase(String filter,boolean useSnapshot){
        return """
            %s
            report_rows as (
             select st.id station_id,st.code station_code,st.name station_name,p.id process_id,
              p.code process_code,p.name process_name,s.style_number,l.lot_number,pe.status,
              u.display_name operator_name,pe.started_at start_time,pe.completed_at end_time,
              case when pe.started_at is null then null
                   else round(extract(epoch from (%s-pe.started_at))/60.0,2) end duration_minutes
             from process_executions pe join station_visits sv on sv.id=pe.station_visit_id
             join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id
             join stations st on st.id=sv.station_id join processes p on p.id=pe.process_id
             left join users u on u.id=pe.operator_id %s
            )
            """.formatted(useSnapshot?"with params as (select ?::timestamptz snapshot_at),":"with",
                useSnapshot?"coalesce(pe.completed_at,params.snapshot_at)":"pe.completed_at",
                (useSnapshot?"cross join params ":"")+"where "+filter);
    }

    private List<ReportModels.Section> delay(ReportModels.Prepared prepared,OffsetDateTime start,
                                             OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=processFilter(prepared,start,end);
        filter.add("pe.operator_id=?",request.operatorId());filter.raw("p.target_duration_seconds is not null");
        List<Object> args=with(snapshot,filter.args());
        String base=processBase(filter.sql(),true).replace("p.name process_name,",
            "p.name process_name,p.target_duration_seconds,");
        String calculated=base+"""
            , calculated as (
             select report_rows.*,
              round(target_duration_seconds/60.0,2) expected_minutes,
              greatest(round(duration_minutes-target_duration_seconds/60.0,2),0) delay_minutes,
              greatest(round(100.0*(duration_minutes-target_duration_seconds/60.0)/nullif(target_duration_seconds/60.0,0),2),0) delay_percent,
              case when duration_minutes>target_duration_seconds/60.0 then 'DELAYED' else 'ON_TIME' end delay_status
             from report_rows
            ), filtered as (
             select * from calculated where (?::text is null or delay_status=?)
            )
            """;
        List<Object> calculatedArgs=new ArrayList<>(args);calculatedArgs.add(request.status());calculatedArgs.add(request.status());
        var summary=query(calculated+"""
            select station_code,station_name,process_code,process_name,count(*) executions,
             count(*) filter(where delay_status='DELAYED') delayed_executions,
             round(avg(duration_minutes),2) average_actual_minutes,round(avg(expected_minutes),2) expected_minutes,
             round(avg(delay_minutes) filter(where delay_status='DELAYED'),2) average_delay_minutes,
             round(max(delay_minutes),2) maximum_delay_minutes
            from filtered group by station_code,station_name,process_code,process_name
            order by delayed_executions desc,station_code,process_code
            """,calculatedArgs);
        var details=query(calculated+"""
            select lot_number,style_number,station_code,station_name,process_code,process_name,
             expected_minutes,duration_minutes actual_minutes,delay_minutes,delay_percent,delay_status,
             start_time,end_time,operator_name
            from filtered order by delay_minutes desc,start_time
            """,calculatedArgs);
        return List.of(section("Bottleneck Summary",summary,false,
                "station_code","Station","station_name","Station Name","process_code","Process",
                "process_name","Process Name","executions","Executions","delayed_executions","Delayed",
                "average_actual_minutes","Average Actual (minutes)","expected_minutes","Expected (minutes)",
                "average_delay_minutes","Average Delay (minutes)","maximum_delay_minutes","Maximum Delay (minutes)"),
            section("Delay Details",details,true,
                "lot_number","Lot No.","style_number","Style No.","station_code","Station",
                "station_name","Station Name","process_code","Process","process_name","Process Name",
                "expected_minutes","Expected (minutes)","actual_minutes","Actual (minutes)",
                "delay_minutes","Delay (minutes)","delay_percent","Delay %",
                "delay_status","Delay Status","start_time","Start Time","end_time","End Time",
                "operator_name","Operator"));
    }

    private List<ReportModels.Section> movement(ReportModels.Prepared prepared,OffsetDateTime start,
                                                OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=new SqlFilter();
        filter.raw(prepared.lotPredicate());filter.raw(prepared.stationPredicate());
        filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        filter.add("st.id=?",request.stationId());filter.add("sv.received_by=?",request.operatorId());
        filter.add("sv.status=?",request.status());
        String sql="""
            with params as (select ?::timestamptz snapshot_at),
            ordered as (
             select s.style_number,l.lot_number,st.id station_id,st.code to_station,sv.status,
              lag(st.code) over(partition by l.id order by lr.sequence_no) from_station,
              sv.dispatched_at movement_time,sv.received_at received_time,u.display_name operator_name,
              l.id lot_id
             from station_visits sv join lots l on l.id=sv.lot_id join styles s on s.id=l.style_id
             join stations st on st.id=sv.station_id join lot_route_entries lr on lr.id=sv.lot_route_entry_id
             left join users u on u.id=sv.received_by where %s
            ), report_rows as (
             select ordered.style_number,ordered.lot_number,coalesce(ordered.from_station,'ORIGIN') from_station,
              ordered.to_station,trolley.code trolley_id,storage.code storage_location,
              ordered.movement_time,ordered.received_time,ordered.operator_name,ordered.status
             from ordered cross join params
             left join lateral (
              select t.code from lot_trolley_assignments a join trolleys t on t.id=a.trolley_id
              where a.lot_id=ordered.lot_id and a.linked_at<=coalesce(ordered.received_time,params.snapshot_at)
               and (a.released_at is null or a.released_at>=ordered.movement_time)
              order by a.linked_at desc limit 1
             ) trolley on true
             left join lateral (
              select sl.code from storage_occupancies so join storage_locations sl on sl.id=so.storage_location_id
              where so.lot_id=ordered.lot_id and so.retrieved_at is not null
               and so.retrieved_at<=ordered.movement_time
              order by so.retrieved_at desc limit 1
             ) storage on true
             where ordered.movement_time>=? and ordered.movement_time<?
            )
            select style_number,lot_number,from_station,to_station,trolley_id,storage_location,
             movement_time,received_time,operator_name,status
            from report_rows order by movement_time,lot_number
            """.formatted(filter.sql());
        List<Object> args=new ArrayList<>();args.add(snapshot);args.addAll(filter.args());args.add(start);args.add(end);
        var details=query(sql,args);
        return List.of(section("Movement Details",details,true,
            "style_number","Style No.","lot_number","Lot No.","from_station","From Station",
            "to_station","To Station","trolley_id","Trolley ID","storage_location","Storage Location",
            "movement_time","Movement Time","received_time","Received Time",
            "operator_name","Operator","status","Status"));
    }

    private List<ReportModels.Section> storage(ReportModels.Prepared prepared,OffsetDateTime start,
                                               OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=new SqlFilter();
        filter.add("so.stored_at>=? and so.stored_at<?",start,end);
        filter.raw(prepared.lotPredicate());filter.raw(prepared.stationPredicate());
        filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        filter.add("st.id=?",request.stationId());filter.add("so.stored_by=?",request.operatorId());
        String status=request.status();
        String sql="""
            with params as (select ?::timestamptz snapshot_at), report_rows as (
             select s.style_number,l.lot_number,st.code station,sa.code storage_area,sl.code storage_location,
              so.stored_at entry_time,so.retrieved_at exit_time,
              round(extract(epoch from (coalesce(so.retrieved_at,params.snapshot_at)-so.stored_at))/60.0,2) waiting_minutes,
              case when so.retrieved_at is null then 'WAITING' else 'RETRIEVED' end status,
              stored.display_name stored_by,retrieved.display_name retrieved_by
             from storage_occupancies so join lots l on l.id=so.lot_id join styles s on s.id=l.style_id
             join stations st on st.id=so.station_id join storage_locations sl on sl.id=so.storage_location_id
             join storage_areas sa on sa.id=sl.storage_area_id cross join params
             left join users stored on stored.id=so.stored_by left join users retrieved on retrieved.id=so.retrieved_by
             where %s
            )
            select * from report_rows where (?::text is null or status=?) order by entry_time,lot_number
            """.formatted(filter.sql());
        List<Object> args=new ArrayList<>();args.add(snapshot);args.addAll(filter.args());args.add(status);args.add(status);
        var details=query(sql,args);
        var summary=query("select status,count(*) occupancies,round(avg(waiting_minutes),2) average_waiting_minutes,"+
            "round(max(waiting_minutes),2) maximum_waiting_minutes from ("+sql+") rows group by status order by status",args);
        return List.of(section("Storage Summary",summary,false,
                "status","Status","occupancies","Occupancies","average_waiting_minutes","Average Waiting (minutes)",
                "maximum_waiting_minutes","Maximum Waiting (minutes)"),
            section("Storage Details",details,true,
                "style_number","Style No.","lot_number","Lot No.","station","Station",
                "storage_area","Storage Area","storage_location","Storage Location",
                "entry_time","Entry Time","exit_time","Exit Time","waiting_minutes","Waiting (minutes)",
                "status","Status","stored_by","Stored By","retrieved_by","Retrieved By"));
    }

    private List<ReportModels.Section> trolley(ReportModels.Prepared prepared,OffsetDateTime start,
                                               OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=new SqlFilter();
        filter.add("a.linked_at>=? and a.linked_at<?",start,end);filter.raw(prepared.lotPredicate());
        filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        String status=request.status();
        String base="""
            with params as (select ?::timestamptz snapshot_at), report_rows as (
             select t.id trolley_uuid,t.code trolley_id,s.style_number,l.lot_number,a.linked_at linked_time,
              a.released_at delinked_time,
              round(extract(epoch from (coalesce(a.released_at,params.snapshot_at)-a.linked_at))/60.0,2) association_minutes,
              count(*) over(partition by t.id) assignments,
              case when not t.active then 'INACTIVE'
                   when exists(select 1 from lot_trolley_assignments active where active.trolley_id=t.id and active.released_at is null)
                   then 'IN_USE' else 'AVAILABLE' end availability
             from lot_trolley_assignments a join trolleys t on t.id=a.trolley_id
             join lots l on l.id=a.lot_id join styles s on s.id=l.style_id cross join params
             where %s
            ), filtered as (select * from report_rows where (?::text is null or availability=?))
            """.formatted(filter.sql());
        List<Object> args=new ArrayList<>();args.add(snapshot);args.addAll(filter.args());args.add(status);args.add(status);
        var summary=query(base+"""
            select trolley_id,count(*) assignments,round(sum(association_minutes),2) total_association_minutes,
             round(avg(association_minutes),2) average_association_minutes,max(availability) availability
            from filtered group by trolley_uuid,trolley_id order by trolley_id
            """,args);
        var details=query(base+"""
            select trolley_id,style_number,lot_number,linked_time,delinked_time,association_minutes,assignments,availability
            from filtered order by linked_time,trolley_id
            """,args);
        return List.of(section("Trolley Summary",summary,false,
                "trolley_id","Trolley ID","assignments","Assignments","total_association_minutes","Total Association (minutes)",
                "average_association_minutes","Average Association (minutes)","availability","Availability"),
            section("Assignment Details",details,true,
                "trolley_id","Trolley ID","style_number","Style No.","lot_number","Lot No.",
                "linked_time","Linked Time","delinked_time","Delinked Time",
                "association_minutes","Association Duration (minutes)",
                "assignments","Number of Assignments","availability","Availability"));
    }

    private List<ReportModels.Section> operator(ReportModels.Prepared prepared,OffsetDateTime start,
                                                OffsetDateTime end,OffsetDateTime snapshot){
        var request=prepared.request();var filter=processFilter(prepared,start,end);
        filter.add("pe.operator_id=?",request.operatorId());filter.add("pe.status=?",request.status());
        String base=processBase(filter.sql(),true).replace("u.display_name operator_name,",
            "u.id operator_id,u.display_name operator_name,");
        List<Object> args=with(snapshot,filter.args());
        var summary=query(base+"""
            select operator_name,count(*) tasks,count(*) filter(where status='COMPLETED') completed_tasks,
             round(sum(duration_minutes) filter(where duration_minutes is not null),2) total_minutes,
             round(avg(duration_minutes) filter(where duration_minutes is not null),2) average_minutes
            from report_rows group by operator_id,operator_name order by completed_tasks desc,operator_name
            """,args);
        var details=query(base+"""
            select operator_name,station_code,station_name,style_number,lot_number,process_code,process_name,
             start_time,end_time,duration_minutes,status,
             count(*) filter(where status='COMPLETED') over(partition by operator_id) completed_tasks
            from report_rows order by start_time,operator_name
            """,args);
        return List.of(section("Operator Summary",summary,false,
                "operator_name","Operator","tasks","Tasks","completed_tasks","Completed Tasks",
                "total_minutes","Total Duration (minutes)","average_minutes","Average Duration (minutes)"),
            section("Activity Details",details,true,
                "operator_name","Operator","station_code","Station","station_name","Station Name",
                "style_number","Style No.","lot_number","Lot No.","process_code","Process",
                "process_name","Process Name","start_time","Start Time","end_time","Completion Time",
                "duration_minutes","Duration (minutes)","status","Status","completed_tasks","Completed Tasks"));
    }

    private List<ReportModels.Section> daily(ReportModels.Prepared prepared){
        var request=prepared.request();var filter=new SqlFilter();
        filter.raw(prepared.lotPredicate());filter.addIds("l.style_id",request.styleIds());filter.addIds("l.id",request.lotIds());
        filter.add("l.status=?",request.status());
        filter.add("exists(select 1 from station_visits fsv where fsv.lot_id=l.id and fsv.station_id=?)",request.stationId());
        filter.add("exists(select 1 from station_visits fsv join process_executions fpe on fpe.station_visit_id=fsv.id where fsv.lot_id=l.id and fpe.process_id=?)",request.processId());
        String base="""
            with days as (
             select day::date report_date,
              day::date::timestamp at time zone ? day_start,
              (day::date+1)::timestamp at time zone ? day_end
             from generate_series(?::date,?::date,interval '1 day') day
            ), eligible_lots as (
             select l.*,s.style_number,
              (select min(sv.dispatched_at) from station_visits sv where sv.lot_id=l.id) started_at
             from lots l join styles s on s.id=l.style_id where %s
            )
            """.formatted(filter.sql());
        List<Object> args=new ArrayList<>(List.of(factoryZone.getId(),factoryZone.getId(),request.from(),request.to()));
        args.addAll(filter.args());
        var summary=query(base+"""
            select d.report_date,
             count(distinct l.style_id) filter(where l.created_at<d.day_end and (l.completed_at is null or l.completed_at>=d.day_start)
               and (l.cancelled_at is null or l.cancelled_at>=d.day_start)) styles_in_production,
             count(l.id) filter(where l.created_at>=d.day_start and l.created_at<d.day_end) total_lots,
             count(l.id) filter(where l.started_at>=d.day_start and l.started_at<d.day_end) started_lots,
             count(l.id) filter(where l.completed_at>=d.day_start and l.completed_at<d.day_end) completed_lots,
             count(l.id) filter(where l.started_at<d.day_end and (l.completed_at is null or l.completed_at>=d.day_end)
               and (l.cancelled_at is null or l.cancelled_at>=d.day_end)) in_progress_lots,
             count(l.id) filter(where exists(
              select 1 from process_executions pe join station_visits sv on sv.id=pe.station_visit_id
              join processes p on p.id=pe.process_id join stations st on st.id=sv.station_id
              where sv.lot_id=l.id and pe.started_at<d.day_end and p.target_duration_seconds is not null
               and %s and %s and extract(epoch from (least(coalesce(pe.completed_at,d.day_end),d.day_end)-pe.started_at))>p.target_duration_seconds
             )) delayed_lots,
             round(avg(extract(epoch from (l.completed_at-l.started_at))/60.0)
               filter(where l.completed_at>=d.day_start and l.completed_at<d.day_end and l.started_at is not null),2)
               average_completion_minutes
            from days d left join eligible_lots l on l.created_at<d.day_end
            group by d.report_date order by d.report_date
            """.formatted(prepared.stationPredicate(),prepared.processPredicate()),args);
        String outputSql=base+"""
            , station_completions as (
             select sv.lot_id,st.code station,st.id station_id,max(pe.completed_at) completed_at
             from station_visits sv join stations st on st.id=sv.station_id
             join process_executions pe on pe.station_visit_id=sv.id
             join processes p on p.id=pe.process_id join eligible_lots l on l.id=sv.lot_id
             where %s and %s and %s
             group by sv.id,sv.lot_id,st.id,st.code
             having bool_and(pe.status in ('COMPLETED','SKIPPED'))
            )
            select d.report_date,sc.station,count(*) completed_lots
            from days d join station_completions sc on sc.completed_at>=d.day_start and sc.completed_at<d.day_end
            group by d.report_date,sc.station order by d.report_date,sc.station
            """.formatted(prepared.lotPredicate(),prepared.stationPredicate(),prepared.processPredicate());
        var output=query(outputSql,args);
        return List.of(section("Daily Summary",summary,false,
                "report_date","Date","styles_in_production","Styles in Production","total_lots","Total Lots",
                "started_lots","Started Lots","completed_lots","Completed Lots",
                "in_progress_lots","In-Progress Lots","delayed_lots","Delayed Lots",
                "average_completion_minutes","Average Completion (minutes)"),
            section("Station Output",output,false,
                "report_date","Date","station","Station","completed_lots","Completed Lots"));
    }

    private static ReportModels.Section section(String name,ReportModels.Query query,boolean csvPrimary,String... columns){
        List<ReportModels.Column> result=new ArrayList<>();
        for(int i=0;i<columns.length;i+=2)result.add(new ReportModels.Column(columns[i],columns[i+1]));
        return new ReportModels.Section(name,List.copyOf(result),query,csvPrimary);
    }
    private static ReportModels.Query query(String sql,List<Object> args){
        return new ReportModels.Query(sql,Collections.unmodifiableList(new ArrayList<>(args)));
    }
    private static List<Object> with(Object first,List<Object> rest){
        List<Object> result=new ArrayList<>();result.add(first);result.addAll(rest);return result;
    }

    private static final class SqlFilter {
        private final List<String> clauses=new ArrayList<>();
        private final List<Object> args=new ArrayList<>();
        void raw(String clause){clauses.add("("+clause+")");}
        void add(String clause,Object value){if(value!=null){clauses.add("("+clause+")");args.add(value);}}
        void add(String clause,Object first,Object second){
            if(first!=null&&second!=null){clauses.add("("+clause+")");args.add(first);args.add(second);}
        }
        void addIds(String column,List<UUID> values){
            if(values==null||values.isEmpty())return;
            clauses.add("("+column+" in ("+String.join(",",Collections.nCopies(values.size(),"?"))+"))");
            args.addAll(values);
        }
        String sql(){return clauses.isEmpty()?"true":String.join(" and ",clauses);}
        List<Object> args(){return List.copyOf(args);}
    }
}
