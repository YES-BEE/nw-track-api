package com.yesbee.nwtrack.production;

import com.yesbee.nwtrack.routing.RouteDerivationService;
import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.yesbee.nwtrack.shared.ApiPage;
import com.yesbee.nwtrack.shared.Pages;

@RestController @RequestMapping("/api/v1") @Tag(name="Styles and Lots")
public class ProductionController {
    private final LotTrolleyService trolleyMappings;private final ProductionService service;private final JdbcTemplate jdbc;private final ActorResolver actors;private final StyleRevisionService revisions;private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public ProductionController(LotTrolleyService trolleyMappings,ProductionService service,JdbcTemplate jdbc,ActorResolver actors,StyleRevisionService revisions,com.yesbee.nwtrack.auth.AccessPolicy access){this.trolleyMappings=trolleyMappings;this.service=service;this.jdbc=jdbc;this.actors=actors;this.revisions=revisions;this.access=access;}
    @PostMapping("/styles") @PreAuthorize("isAuthenticated()") public Map<String,Object> createStyle(@Valid @RequestBody StyleCommand r,@AuthenticationPrincipal Jwt jwt){
        if(access.ownStylesOnly()&&(r.processIds()==null||r.processIds().isEmpty()))throw new IllegalArgumentException("New Style requires processIds in the create request");
        validateProcesses(r.processIds());return style(service.createConfiguredStyle(r.styleNumber(),r.description(),actors.id(jwt),r.processIds(),r.lotCount(),r.quantity()));
    }
    @GetMapping("/styles") public ApiPage<Map<String,Object>> styles(){return Pages.query(jdbc,"select id,style_number,description,status,released_at,created_at,created_by,lot_count,lot_quantity,version from styles s where "+access.stylePredicate("s")+" order by created_at desc,id");}
    @GetMapping("/styles/{id}") public Map<String,Object> style(@PathVariable UUID id){return jdbc.queryForMap("select id,style_number,description,status,released_at,created_at,created_by,lot_count,lot_quantity,version from styles where id=?",id);}
    @PutMapping("/styles/{id}/processes") @PreAuthorize("isAuthenticated()") public List<RouteDerivationService.RouteEntry> processes(@PathVariable UUID id,@RequestBody @NotEmpty List<UUID> processIds){validateProcesses(processIds);service.setProcesses(id,processIds);return service.derive(id);}
    @GetMapping("/styles/{id}/derived-route") public List<RouteDerivationService.RouteEntry> route(@PathVariable UUID id){return service.derive(id);}
    @PostMapping("/styles/{id}/release") @PreAuthorize("isAuthenticated()") public Map<String,Object> release(@PathVariable UUID id){service.release(id);return style(id);}
    @PostMapping("/styles/{id}/revisions/unstarted") @PreAuthorize("isAuthenticated()") public StyleRevisionService.RevisionResult reviseUnstarted(@PathVariable UUID id,@Valid @RequestBody StyleRevisionCommand r,@AuthenticationPrincipal Jwt jwt){validateProcesses(r.processIds());return revisions.revise(id,r.description(),r.processIds(),r.quantity(),r.reason(),StyleRevisionService.Scope.UNSTARTED,actors.id(jwt),jwt.getClaimAsStringList("roles").getFirst());}
    @PostMapping("/styles/{id}/revisions/in-progress") @PreAuthorize("isAuthenticated()") public StyleRevisionService.RevisionResult reviseInProgress(@PathVariable UUID id,@Valid @RequestBody StyleRevisionCommand r,@AuthenticationPrincipal Jwt jwt){validateProcesses(r.processIds());return revisions.revise(id,r.description(),r.processIds(),r.quantity(),r.reason(),StyleRevisionService.Scope.ALL_IN_PROGRESS,actors.id(jwt),jwt.getClaimAsStringList("roles").getFirst());}
    @GetMapping("/styles/{id}/revisions") public ApiPage<Map<String,Object>> revisions(@PathVariable UUID id){return Pages.query(jdbc,"select id,revision_no,actor_id,actor_role,reason,affected_lot_count,created_at from style_revisions where style_id=? order by revision_no",id);}
    @PostMapping("/styles/{id}/lots") @PreAuthorize("isAuthenticated()") public Map<String,Object> createLot(@PathVariable UUID id,@Valid @RequestBody LotCommand r,@AuthenticationPrincipal Jwt jwt){var ids=service.createLots(id,r.lotCount()==null?1:r.lotCount(),r.quantity(),actors.id(jwt));return Map.of("createdCount",ids.size(),"items",ids.stream().map(this::lot).toList());}
    @GetMapping("/styles/{id}/lots") public ApiPage<Map<String,Object>> lots(@PathVariable UUID id){return Pages.query(jdbc,LOT_SELECT+" where l.style_id=? and "+access.lotPredicate("l.id")+" order by l.created_at,l.id",id);}
    @GetMapping("/lots") public ApiPage<Map<String,Object>> allLots(){return Pages.query(jdbc,LOT_SELECT+" where "+access.lotPredicate("l.id")+" order by l.created_at,l.id");}
    @GetMapping("/lots/{id}") public Map<String,Object> lot(@PathVariable UUID id){return jdbc.queryForMap(LOT_SELECT+" where l.id=?",id);}
    @GetMapping("/lots/{id}/timeline") public ApiPage<Map<String,Object>> timeline(@PathVariable UUID id){return Pages.query(jdbc,"select location_type,location_id,actor_id,occurred_at,detail from lot_location_events where lot_id=? order by occurred_at,id",id);}
    @PostMapping("/lots/{id}/cancel") @PreAuthorize("isAuthenticated()") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody ReasonCommand r,@AuthenticationPrincipal Jwt jwt){service.cancelLot(id,r.reason(),actors.id(jwt));return lot(id);}
    @PutMapping("/lots/{id}/trolley") public Map<String,Object> assignTrolley(@PathVariable UUID id,@Valid @RequestBody TrolleyMapping r,@AuthenticationPrincipal Jwt jwt){trolleyMappings.assign(id,r.trolleyId(),actors.id(jwt));return lot(id);}
    @DeleteMapping("/lots/{id}/trolley") public Map<String,Object> unassignTrolley(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){trolleyMappings.unassign(id,actors.id(jwt));return lot(id);}
    public record TrolleyMapping(@NotNull UUID trolleyId){}
    private static final String LOT_SELECT="select l.id,l.style_id,s.style_number,l.lot_number,l.quantity,l.status,l.current_route_sequence,l.current_location_type,l.current_location_id,l.created_at,l.completed_at,l.version,(select a.trolley_id from lot_trolley_assignments a where a.lot_id=l.id and a.released_at is null) trolley_id,(select t.code from lot_trolley_assignments a join trolleys t on t.id=a.trolley_id where a.lot_id=l.id and a.released_at is null) trolley_code from lots l join styles s on s.id=l.style_id";
    private void validateProcesses(List<UUID> ids){
        if(ids==null)return;
        if(ids.contains(null)||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Process IDs must be unique and non-null");
        for(UUID process:ids){
            jdbc.queryForObject("select p.id from processes p join stations s on s.id=p.station_id where p.id=? and p.active and s.active",UUID.class,process);
            if(!access.role().equals("STYLE_CONFIGURATOR"))access.readProcess(process);
        }
    }
    public record StyleCommand(@NotBlank String styleNumber,String description,List<@NotNull UUID> processIds,@Min(0) @Max(1000) Integer lotCount,@Positive Integer quantity){} public record LotCommand(@Min(1) @Max(1000) Integer lotCount,@Positive Integer quantity){} public record ReasonCommand(@NotBlank String reason){} public record StyleRevisionCommand(String description,@NotEmpty List<UUID> processIds,@Positive Integer quantity,@NotBlank String reason){}
}
