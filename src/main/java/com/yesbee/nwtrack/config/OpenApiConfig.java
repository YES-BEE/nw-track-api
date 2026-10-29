package com.yesbee.nwtrack.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    ObjectMapper legacyObjectMapper() { return JsonMapper.builder().findAndAddModules().build(); }

    @Bean
    OpenAPI nwTrackOpenApi() {
        return new OpenAPI()
                .info(new Info().title("NW Track API").version("v1")
                        .description("On-premise factory production traceability API")
                        .contact(new Contact().name("YesBee")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
