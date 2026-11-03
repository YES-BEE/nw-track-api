package com.yesbee.nwtrack.transport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/transport-requests")
@Tag(name = "Transport", description = "AGV/AMR provider abstraction and simulator")
public class TransportController {
    private final TransportProvider provider; private final TransportRequestService requests; private final JdbcTemplate jdbc;private final com.yesbee.nwtrack.auth.AccessPolicy access;
    public TransportController(TransportProvider provider,TransportRequestService requests,JdbcTemplate jdbc,com.yesbee.nwtrack.auth.AccessPolicy access) { this.provider = provider;this.requests=requests;this.jdbc=jdbc;this.access=access; }
    @PostMapping @PreAuthorize("isAuthenticated()") public Map<String,Object> create(@Valid @RequestBody CreateRequest r){access.lot(r.lotId());access.station(jdbc.queryForObject("select station_id from transport_locations where id=?",UUID.class,r.sourceLocationId()));UUID id=requests.create(r.lotId(),r.trolleyId(),r.sourceLocationId(),r.destinationLocationId(),r.priority());return get(id);}
    @GetMapping @PreAuthorize("isAuthenticated()") public List<Map<String,Object>> list(){return jdbc.queryForList(SELECT+" where "+access.lotPredicate("tr.lot_id")+" order by tr.created_at desc");}
    @GetMapping("/{id}") @PreAuthorize("isAuthenticated()") public Map<String,Object> get(@PathVariable UUID id){return jdbc.queryForMap(SELECT+" where tr.id=?",id);}
    @PostMapping("/{id}/cancel") @PreAuthorize("isAuthenticated()") public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody Reason r){requests.cancel(id,r.reason());return get(id);}
    @PostMapping("/{id}/retry") @PreAuthorize("isAuthenticated()") public Map<String,Object> retry(@PathVariable UUID id){requests.retry(id);return get(id);}
    @PostMapping("/{id}/manual-takeover") @PreAuthorize("isAuthenticated()") public Map<String,Object> manual(@PathVariable UUID id,@RequestBody Reason r){requests.manualTakeover(id,r.reason());return get(id);}
    @PostMapping("/{id}/reconcile") @PreAuthorize("isAuthenticated()") public Map<String,Object> reconcile(@PathVariable UUID id){requests.reconcile(id);return get(id);}
    @PostMapping("/{id}/provider-events") @PreAuthorize("isAuthenticated()") public void callback(@PathVariable UUID id,@Valid @RequestBody ProviderEvent r){requests.providerEvent(id,r.eventId(),r.status(),r.detail()==null?Map.of():r.detail());}
    public record ProviderResponse(String provider, TransportProvider.ProviderHealth health) {}
    public record CreateRequest(@NotNull UUID lotId,UUID trolleyId,@NotNull UUID sourceLocationId,@NotNull UUID destinationLocationId,int priority){} public record Reason(@NotBlank String reason){} public record ProviderEvent(@NotBlank String eventId,@NotBlank String status,Map<String,Object> detail){}
    private static final String SELECT="select tr.id,tr.lot_id,l.lot_number,tr.trolley_id,tr.source_location_id,tr.destination_location_id,tr.provider_code,tr.provider_mission_id,tr.status,tr.priority,tr.correlation_id,tr.created_at,tr.updated_at,tr.version from transport_requests tr join lots l on l.id=tr.lot_id";
}
