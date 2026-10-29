package com.yesbee.nwtrack.qc;

import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/v1") @Tag(name="Quality Control")
public class QcController {
    private final QcService service;private final ActorResolver actors;private final JdbcTemplate jdbc;
    public QcController(QcService service,ActorResolver actors,JdbcTemplate jdbc){this.service=service;this.actors=actors;this.jdbc=jdbc;}
    @PostMapping("/lots/{id}/qc") @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')") public Map<String,Object> complete(@PathVariable UUID id,@Valid @RequestBody QcCommand r,@AuthenticationPrincipal Jwt jwt){UUID inspection=service.complete(id,r.outcome(),r.inspectedQuantity(),r.goodQuantity(),r.rejectedQuantity(),r.reason(),actors.id(jwt));return jdbc.queryForMap("select id,lot_id,outcome,inspected_quantity,good_quantity,rejected_quantity,reason,operator_id,completed_at from qc_inspections where id=?",inspection);}
    @GetMapping("/lots/{id}/qc") public Map<String,Object> get(@PathVariable UUID id){return jdbc.queryForMap("select id,lot_id,outcome,inspected_quantity,good_quantity,rejected_quantity,reason,operator_id,completed_at from qc_inspections where lot_id=?",id);}
    public record QcCommand(@NotBlank String outcome,@Positive int inspectedQuantity,@PositiveOrZero int goodQuantity,@PositiveOrZero int rejectedQuantity,String reason){}
}
