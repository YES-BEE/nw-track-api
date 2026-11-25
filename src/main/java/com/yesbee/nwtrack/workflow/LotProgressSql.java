package com.yesbee.nwtrack.workflow;

import java.util.List;
import java.util.stream.Collectors;

public final class LotProgressSql {
    private LotProgressSql() {}

    private record Column(String databaseName, String apiName) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("progress_percent", "progressPercent"),
            new Column("completed_route_steps", "completedRouteSteps"),
            new Column("total_route_steps", "totalRouteSteps"),
            new Column("current_process_execution_id", "currentProcessExecutionId"),
            new Column("current_process_id", "currentProcessId"),
            new Column("current_process_name", "currentProcessName"),
            new Column("current_process_code", "currentProcessCode"),
            new Column("current_process_status", "currentProcessStatus"),
            new Column("current_step_id", "currentStepId"),
            new Column("current_step_sequence", "currentStepSequence"),
            new Column("current_step_status", "currentStepStatus"),
            new Column("current_step_station_id", "currentStepStationId"),
            new Column("current_step_station_code", "currentStepStationCode"),
            new Column("current_step_station_name", "currentStepStationName"),
            new Column("next_process_execution_id", "nextProcessExecutionId"),
            new Column("next_process_id", "nextProcessId"),
            new Column("next_process_name", "nextProcessName"),
            new Column("next_process_code", "nextProcessCode"),
            new Column("next_process_status", "nextProcessStatus"),
            new Column("next_step_id", "nextStepId"),
            new Column("next_step_sequence", "nextStepSequence"),
            new Column("next_step_status", "nextStepStatus"),
            new Column("next_step_station_id", "nextStepStationId"),
            new Column("next_step_station_code", "nextStepStationCode"),
            new Column("next_step_station_name", "nextStepStationName")
    );

    public static String columns(boolean camelCase) {
        return COLUMNS.stream()
                .map(column -> "route_progress." + column.databaseName()
                        + (camelCase ? " as \"" + column.apiName() + "\"" : " as " + column.databaseName()))
                .collect(Collectors.joining(","));
    }

    public static final String JOIN = """
        left join lateral (
         select
          case when l.status='COMPLETED' then 100.00
           else coalesce(round(100.0*totals.completed_route_steps/nullif(totals.total_route_steps,0),2),0)
          end progress_percent,
          totals.completed_route_steps,totals.total_route_steps,
          current_process.execution_id current_process_execution_id,
          current_process.process_id current_process_id,
          current_process.process_name current_process_name,
          current_process.process_code current_process_code,
          current_process.status current_process_status,
          current_step.id current_step_id,
          current_step.sequence_no current_step_sequence,
          current_step.status current_step_status,
          current_step.station_id current_step_station_id,
          current_step.station_code current_step_station_code,
          current_step.station_name current_step_station_name,
          next_process.execution_id next_process_execution_id,
          next_process.process_id next_process_id,
          next_process.process_name next_process_name,
          next_process.process_code next_process_code,
          next_process.status next_process_status,
          next_step.id next_step_id,
          next_step.sequence_no next_step_sequence,
          next_step.status next_step_status,
          next_step.station_id next_step_station_id,
          next_step.station_code next_step_station_code,
          next_step.station_name next_step_station_name
         from lateral (
          select count(*) filter(where state.has_work and state.is_complete) completed_route_steps,
                 count(*) filter(where state.has_work) total_route_steps
          from lot_route_entries lr
          cross join lateral (
           select exists(
                   select 1 from station_visits sv join process_executions pe on pe.station_visit_id=sv.id
                   where sv.lot_route_entry_id=lr.id
                    and not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')
                  ) has_work,
                  not exists(
                   select 1 from station_visits sv join process_executions pe on pe.station_visit_id=sv.id
                   where sv.lot_route_entry_id=lr.id
                    and pe.status not in ('COMPLETED','SKIPPED')
                    and not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')
                  ) is_complete
          ) state
          where lr.lot_id=l.id
         ) totals
         left join lateral (
          select lr.id,lr.sequence_no,lr.status,st.id station_id,st.code station_code,st.name station_name
          from lot_route_entries lr join stations st on st.id=lr.station_id
          where lr.lot_id=l.id and lr.sequence_no=l.current_route_sequence
         ) current_step on true
         left join lateral (
          select pe.id execution_id,p.id process_id,p.name process_name,p.code process_code,pe.status
          from station_visits sv
          join lot_route_entries lr on lr.id=sv.lot_route_entry_id
          join process_executions pe on pe.station_visit_id=sv.id
          join processes p on p.id=pe.process_id
          where sv.lot_id=l.id and lr.sequence_no=l.current_route_sequence
           and pe.status not in ('COMPLETED','SKIPPED')
          order by case pe.status when 'IN_PROGRESS' then 0 when 'PAUSED' then 1 when 'BLOCKED' then 2 else 3 end,pe.sequence_no
          limit 1
         ) current_process on true
         left join lateral (
          select lr.id,lr.sequence_no,lr.status,lr.station_id,st.code station_code,st.name station_name,sv.id visit_id
          from lot_route_entries lr
          join stations st on st.id=lr.station_id
          join station_visits sv on sv.lot_route_entry_id=lr.id
          where lr.lot_id=l.id and lr.sequence_no>l.current_route_sequence
           and exists(
            select 1 from process_executions pe
            where pe.station_visit_id=sv.id
             and not (pe.status='SKIPPED' and coalesce(pe.reason,'') like 'Superseded by Manager route revision:%')
           )
          order by lr.sequence_no
          limit 1
         ) next_step on true
         left join lateral (
          select pe.id execution_id,p.id process_id,p.name process_name,p.code process_code,pe.status
          from process_executions pe join processes p on p.id=pe.process_id
          where pe.station_visit_id=next_step.visit_id and pe.status not in ('COMPLETED','SKIPPED')
          order by pe.sequence_no
          limit 1
         ) next_process on true
        ) route_progress on true
        """;
}
