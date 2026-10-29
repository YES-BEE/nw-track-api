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

@RestController @RequestMapping("/api/v1") @Tag(name="Styles and Lots")
public class ProductionController {
    private final ProductionService service;private final JdbcTemplate jdbc;private final ActorResolver actors;
    public ProductionController(ProductionService service,JdbcTemplate jdbc,ActorResolver actors){this.service=service;this.jdbc=jdbc;this.actors=actors;}
    @PostMapping("/styles") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") public Map<String,Object> createStyle(@Valid @RequestBody StyleCommand r,@AuthenticationPrincipal Jwt jwt){return style(service.createStyle(r.styleNumber(),r.description(),actors.id(jwt)));}
    @GetMapping("/styles") public List<Map<String,Object>> styles(){return jdbc.queryForList("select id,style_number,description,status,released_at,created_at,version from styles order by created_at desc");}
    @GetMapping("/styles/{id}") public Map<String,Object> style(@PathVariable UUID id){return jdbc.queryForMap("select id,style_number,description,status,released_at,created_at,version from styles where id=?",id);}
    @PutMapping("/styles/{id}/processes") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") public List<RouteDerivationService.RouteEntry> processes(@PathVariable UUID id,@RequestBody @NotEmpty List<UUID> processIds){service.setProcesses(id,processIds);return service.derive(id);}
    @GetMapping("/styles/{id}/derived-route") public List<RouteDerivationService.RouteEntry> route(@PathVariable UUID id){return service.derive(id);}
    @PostMapping("/styles/{id}/release") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") public Map<String,Object> release(@PathVariable UUID id){service.release(id);return style(id);}
    @PostMapping("/styles/{id}/lots") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") public Map<String,Object> createLot(@PathVariable UUID id,@Valid @RequestBody LotCommand r,@AuthenticationPrincipal Jwt jwt){return lot(service.createLot(id,r.lotNumber(),r.quantity(),actors.id(jwt)));}
    @GetMapping("/styles/{id}/lots") public List<Map<String,Object>> lots(@PathVariable UUID id){return jdbc.queryForList(LOT_SELECT+" where l.style_id=? order by l.created_at",id);}
    @GetMapping("/lots/{id}") public Map<String,Object> lot(@PathVariable UUID id){return jdbc.queryForMap(LOT_SELECT+" where l.id=?",id);}
    @GetMapping("/lots/{id}/timeline") public List<Map<String,Object>> timeline(@PathVariable UUID id){return jdbc.queryForList("select location_type,location_id,actor_id,occurred_at,detail from lot_location_events where lot_id=? order by occurred_at",id);}
    @PostMapping("/lots/{id}/cancel") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody ReasonCommand r,@AuthenticationPrincipal Jwt jwt){service.cancelLot(id,r.reason(),actors.id(jwt));return lot(id);}
    private static final String LOT_SELECT="select l.id,l.style_id,s.style_number,l.lot_number,l.quantity,l.status,l.current_route_sequence,l.current_location_type,l.current_location_id,l.created_at,l.completed_at,l.version from lots l join styles s on s.id=l.style_id";
    public record StyleCommand(@NotBlank String styleNumber,String description){} public record LotCommand(@NotBlank String lotNumber,@Positive int quantity){} public record ReasonCommand(@NotBlank String reason){}
}
