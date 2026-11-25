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
import com.yesbee.nwtrack.shared.ApiPage;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.math.BigDecimal;
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    ObjectMapper legacyObjectMapper() { return JsonMapper.builder().findAndAddModules().build(); }

    @Bean
    OperationCustomizer collectionPagingParameters(){
        return (operation,method)->{
            boolean paged=ApiPage.class.isAssignableFrom(method.getReturnType().getParameterType())
                || Set.of("lookups","bootstrap","changes","progress","stationDisplay","summary","factoryDisplay").contains(method.getMethod().getName());
            if(paged){
                operation.addParametersItem(new Parameter().name("page").in("query").description("Zero-based page number").schema(new IntegerSchema()._default(0).minimum(BigDecimal.ZERO)));
                operation.addParametersItem(new Parameter().name("size").in("query").description("Items per page, 1–100").schema(new IntegerSchema()._default(20).minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100))));
                operation.addParametersItem(new Parameter().name("search").in("query").description("Case-insensitive literal substring across projected fields").schema(new StringSchema().maxLength(200)));
            }
            return operation;
        };
    }

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
