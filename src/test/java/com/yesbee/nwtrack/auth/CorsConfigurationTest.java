package com.yesbee.nwtrack.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTest {
    @Test void configuredFrontendOriginsAreIndependentAndCommaSeparated(){
        var source=new SecurityConfig().corsConfigurationSource("http://localhost:5173, http://192.168.1.20:3000");
        var config=source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS","/api/v1/auth/login"));
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(config.checkOrigin("http://192.168.1.20:3000")).isEqualTo("http://192.168.1.20:3000");
        assertThat(config.checkOrigin("http://localhost:3000")).isNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }
}
