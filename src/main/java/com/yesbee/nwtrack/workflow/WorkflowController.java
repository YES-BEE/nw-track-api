package com.yesbee.nwtrack.workflow;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yesbee.nwtrack.shared.ActorResolver;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/station-visits")
@Tag(name = "Station workflow", description = "Operator station receipt, placement and trolley release")
public class WorkflowController {
    private final PlacementService placements; private final ActorResolver actors;
    public WorkflowController(PlacementService placements, ActorResolver actors) { this.placements = placements; this.actors = actors; }

    @PostMapping("/{visitId}/receive-and-place")
    @Operation(summary = "Receive and place a lot", description = "Places the lot in its process or storage area before releasing its trolley")
    @PreAuthorize("isAuthenticated()")
    public PlacementService.PlacementResult receiveAndPlace(@PathVariable UUID visitId, @AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody PlacementService.PlacementCommand command) {
        return placements.receiveAndPlace(visitId, actors.id(jwt), command);
    }
}
