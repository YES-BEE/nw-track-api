package com.yesbee.nwtrack.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryConfigControllerTest {
    @Test
    void returnsEffectiveFactorySettings() {
        var security = new NwTrackProperties.Security("secret", "issuer", Duration.ofMinutes(15), "admin", "password");
        var transport = new NwTrackProperties.Transport("simulator", Duration.ofSeconds(1));
        var properties = new NwTrackProperties("Factory A", security, transport);

        var response = new FactoryConfigController(properties).get();

        assertThat(response.factoryName()).isEqualTo("Factory A");
        assertThat(response.transportProvider()).isEqualTo("simulator");
    }
}
