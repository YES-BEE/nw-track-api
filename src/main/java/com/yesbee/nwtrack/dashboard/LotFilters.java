package com.yesbee.nwtrack.dashboard;

import com.yesbee.nwtrack.shared.Pages;
import java.util.*;

/** Body contract shared by Dashboard and Reports production tables. */
public record LotFilters(
        List<UUID> lotIds,
        List<UUID> styleIds,
        UUID stationId,
        UUID processId,
        String status,
        RouteScope routeScope,
        Integer page,
        Integer size,
        String search) {
    public enum RouteScope { CURRENT, ANY }

    public LotFilters {
        lotIds=ids("lotIds",lotIds);
        styleIds=ids("styleIds",styleIds);
        if(routeScope==null)routeScope=RouteScope.CURRENT;
        if(page==null)page=0;
        if(size==null)size=20;
        search=search==null?"":search;
        new Pages.Options(page,size,search);
        status=status==null?null:status.trim().toUpperCase(Locale.ROOT);
        if(status!=null&&status.isEmpty())status=null;
    }

    public Pages.Options options(){return new Pages.Options(page,size,search);}

    public String where(List<Object> args){
        StringBuilder sql=new StringBuilder(" where true");
        addIds(sql,args,"v.id",lotIds);
        addIds(sql,args,"v.style_id",styleIds);
        if(status!=null){sql.append(" and v.status=?");args.add(status);}
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

    public static void addIds(StringBuilder sql,List<Object> args,String column,List<UUID> ids){
        if(ids.isEmpty())return;
        sql.append(" and ").append(column).append(" in (")
            .append(String.join(",",Collections.nCopies(ids.size(),"?"))).append(")");
        args.addAll(ids);
    }

    private static List<UUID> ids(String name,List<UUID> values){
        if(values==null||values.isEmpty())return List.of();
        if(values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException(name+" cannot contain null");
        if(new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException(name+" cannot contain duplicates");
        if(values.size()>100)throw new IllegalArgumentException(name+" cannot contain more than 100 values");
        return List.copyOf(values);
    }
}
