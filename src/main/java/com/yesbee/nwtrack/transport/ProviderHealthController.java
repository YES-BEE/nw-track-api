package com.yesbee.nwtrack.transport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/transport/providers")
public class ProviderHealthController {
    private final TransportProvider provider;
    public ProviderHealthController(TransportProvider provider){this.provider=provider;}
    @GetMapping("/health") @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPERVISOR')")
    public TransportController.ProviderResponse health(){return new TransportController.ProviderResponse(provider.code(),provider.health());}
}
