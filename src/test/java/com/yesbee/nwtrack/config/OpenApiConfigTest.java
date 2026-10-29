package com.yesbee.nwtrack.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {
    @Test
    void publishesApiMetadataAndJwtSecurityScheme() {
        var api = new OpenApiConfig().nwTrackOpenApi();

        assertThat(api.getInfo().getTitle()).isEqualTo("NW Track API");
        assertThat(api.getComponents().getSecuritySchemes())
                .containsKey(OpenApiConfig.BEARER_AUTH);
        assertThat(api.getSecurity()).anySatisfy(requirement ->
                assertThat(requirement).containsKey(OpenApiConfig.BEARER_AUTH));
    }
}
