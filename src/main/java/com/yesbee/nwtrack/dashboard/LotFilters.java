package com.yesbee.nwtrack.dashboard;

import java.util.*;

/** Shared filters for paginated production tables and their dropdown options. */
public record LotFilters(UUID lotId,UUID styleId,UUID stationId,UUID processId,String status,RouteScope routeScope) {
    public enum RouteScope { CURRENT, ANY }
    public LotFilters { if(routeScope==null)routeScope=RouteScope.CURRENT; }

    public String where(List<Object> args){
        StringBuilder sql=new StringBuilder(" where true");
        if(lotId!=null){sql.append(" and v.id=?");args.add(lotId);}
        if(styleId!=null){sql.append(" and v.style_id=?");args.add(styleId);}
        if(status!=null&&!status.isBlank()){sql.append(" and v.status=?");args.add(status);}
        if(routeScope==RouteScope.CURRENT){
            if(stationId!=null){sql.append(" and v.current_station_id=?");args.add(stationId);}
            if(processId!=null){sql.append(" and v.current_process_id=?");args.add(processId);}
        }else if(stationId!=null||processId!=null){
            sql.append(" and exists(select 1 from station_visits filter_sv where filter_sv.lot_id=v.id");
            if(stationId!=null){sql.append(" and filter_sv.station_id=?");args.add(stationId);}
            if(processId!=null){
                sql.append(" and exists(select 1 from process_executions filter_pe where filter_pe.station_visit_id=filter_sv.id and filter_pe.process_id=? and not (filter_pe.status='SKIPPED' and coalesce(filter_pe.reason,'') like 'Superseded by Manager route revision:%'))");
                args.add(processId);
            }
            sql.append(")");
        }
        return sql.toString();
    }
}
