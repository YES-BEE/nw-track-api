package com.yesbee.nwtrack.workflow;

import com.yesbee.nwtrack.auth.AccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class StationAccessService {
    private final JdbcTemplate jdbc; private final AccessPolicy access;
    public StationAccessService(JdbcTemplate jdbc,AccessPolicy access){this.jdbc=jdbc;this.access=access;}
    public void assertCanOperateCurrentLotStation(UUID actor,UUID lot){UUID station=jdbc.queryForObject("select sv.station_id from station_visits sv join lot_route_entries lr on lr.id=sv.lot_route_entry_id join lots l on l.id=sv.lot_id where l.id=? and lr.sequence_no=coalesce(l.current_route_sequence,1)",UUID.class,lot);assertCanOperate(actor,station);}
    public void assertCanOperate(UUID actor,UUID station){access.station(actor,station,true);}
    public void assertCanExecute(UUID actor,UUID execution){access.execution(actor,execution);}
}
