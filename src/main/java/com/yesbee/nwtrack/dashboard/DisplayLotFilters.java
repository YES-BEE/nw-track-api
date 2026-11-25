package com.yesbee.nwtrack.dashboard;

import java.util.*;

/** Existing query contract retained for read-only factory displays. */
public record DisplayLotFilters(UUID lotId,UUID styleId,UUID stationId,UUID processId,String status,
                                LotFilters.RouteScope routeScope,Integer page,Integer size,String search) {
    public DisplayLotFilters { if(routeScope==null)routeScope=LotFilters.RouteScope.CURRENT; }
    public LotFilters body(){
        return new LotFilters(
            lotId==null?List.of():List.of(lotId),
            styleId==null?List.of():List.of(styleId),
            stationId,processId,status,routeScope,page,size,search);
    }
}
