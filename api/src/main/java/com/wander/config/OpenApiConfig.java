package com.wander.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

/**
 * This document is the contract. The Angular client is generated from it
 * (`npm run api:gen`), so a DTO is never hand-written twice — change the Java
 * record and the TypeScript type follows.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI wanderOpenApi() {
        return new OpenAPI()
                // Relative, so the generated client calls the origin it was
                // served from. Left to itself springdoc writes whatever host and
                // port produced the document — in the export test, a random one.
                .servers(List.of(new Server().url("/")))
                .info(new Info()
                .title("wander API")
                .version("0.1.0")
                .description("Self-hostable collaborative travel planner")
                .license(new License().name("AGPL-3.0-or-later")));
    }
}
