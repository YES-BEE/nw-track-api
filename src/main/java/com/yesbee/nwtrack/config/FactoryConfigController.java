package com.yesbee.nwtrack.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/factory-config")
@Tag(name = "Factory configuration")
public class FactoryConfigController {
    private final NwTrackProperties properties;
    public FactoryConfigController(NwTrackProperties properties) { this.properties = properties; }
    @GetMapping @Operation(summary = "Get effective factory configuration") public FactoryConfigResponse get() {
        return new FactoryConfigResponse(properties.factoryName(), properties.transport().provider());
    }
    public record FactoryConfigResponse(String factoryName, String transportProvider) {}
}
