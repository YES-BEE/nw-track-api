package com.yesbee.nwtrack.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("nwtrack")
public record NwTrackProperties(
        @NotBlank String factoryName,
        @Valid @NotNull Security security,
        @Valid @NotNull Transport transport) {

    public record Security(
            @NotBlank String jwtSecret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotBlank String bootstrapAdminUsername,
            @NotBlank String bootstrapAdminPassword) {}

    public record Transport(
            @NotBlank String provider,
            @NotNull Duration simulatorStepDelay) {}
}
