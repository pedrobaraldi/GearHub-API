package com.gearhub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SESSION_COOKIE = "gearhub_session";

    @Bean
    OpenAPI gearHubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("GearHub API")
                        .version("v1")
                        .description("API da comunidade GearHub: reviews, rankings e comparação de periféricos e hardware."))
                .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(SESSION_COOKIE)
                        .description("Sessão criada pelo login")));
    }
}
