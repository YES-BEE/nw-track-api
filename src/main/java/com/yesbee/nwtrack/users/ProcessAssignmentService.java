package com.yesbee.nwtrack.users;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ProcessAssignmentService {
    private final JdbcTemplate jdbc;
    public ProcessAssignmentService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<View> forUsername(String username){return list(jdbc.queryForObject("select id from users where lower(username)=lower(?)",UUID.class,username));}

    public List<View> list(UUID user){
        return jdbc.query("select upa.process_id,p.name,p.station_id,s.code,upa.active_on_shift,p.active process_active,s.active station_active from user_process_assignments upa join processes p on p.id=upa.process_id join stations s on s.id=p.station_id where upa.user_id=? order by s.code,p.name,p.id",
                (rs,n)->new View(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getBoolean(5),rs.getBoolean(6),rs.getBoolean(7)),user);
    }

    @Transactional public void replace(UUID user,List<Command> assignments){
        jdbc.queryForObject("select id from users where id=? for update",UUID.class,user);
        List<Command> commands=assignments==null?List.of():assignments;
        Set<UUID> ids=new HashSet<>();
        for(Command command:commands){
            if(command==null||command.processId()==null||!ids.add(command.processId()))throw new IllegalArgumentException("Process assignments must contain unique non-null process IDs");
            jdbc.queryForObject("select p.id from processes p join stations s on s.id=p.station_id where p.id=? and p.active and s.active",UUID.class,command.processId());
        }
        jdbc.update("delete from user_process_assignments where user_id=?",user);
        for(Command command:commands)jdbc.update("insert into user_process_assignments(user_id,process_id,active_on_shift) values (?,?,?)",user,command.processId(),Boolean.TRUE.equals(command.activeOnShift()));
    }
    public record Command(@NotNull UUID processId,Boolean activeOnShift){}
    public record View(UUID processId,String processName,UUID stationId,String stationNumber,boolean activeOnShift,boolean processActive,boolean stationActive){}
}
