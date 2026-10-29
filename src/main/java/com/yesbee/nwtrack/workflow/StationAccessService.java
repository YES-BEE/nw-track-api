package com.yesbee.nwtrack.workflow;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class StationAccessService {
    private final JdbcTemplate jdbc;
    public StationAccessService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void assertCanOperateCurrentLotStation(UUID actor,UUID lotId){UUID station=jdbc.queryForObject("select sv.station_id from station_visits sv join lot_route_entries lre on lre.id=sv.lot_route_entry_id join lots l on l.id=sv.lot_id where sv.lot_id=? and lre.sequence_no=coalesce(l.current_route_sequence,1)",UUID.class,lotId);assertCanOperate(actor,station);}
    public void assertCanOperate(UUID actor,UUID station){Integer privileged=jdbc.queryForObject("select count(*) from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and r.code in ('SUPER_ADMIN','ADMIN')",Integer.class,actor);if(privileged!=null&&privileged>0)return;Integer assigned=jdbc.queryForObject("select count(*) from user_station_assignments where user_id=? and station_id=? and active_on_shift",Integer.class,actor,station);if(assigned==null||assigned==0)throw new BusinessRuleException("STATION_ACCESS_DENIED","Operator is not active/on-shift at this Station");}
}
