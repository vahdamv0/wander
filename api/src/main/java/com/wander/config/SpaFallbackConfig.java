package com.wander.config;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular build out of the jar and sends unknown paths to index.html,
 * so a deep link like /trips/7 loads the app instead of 404ing — the router only
 * gets to see the URL once the shell is running.
 *
 * `/api/**` is excluded deliberately: an unknown API path must stay a JSON 404.
 * Falling back there would hand the client a 200 full of HTML, which is a
 * genuinely nasty bug to chase from the frontend.
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    private static final List<String> API_PREFIXES = List.of("api/", "v3/", "swagger-ui", "actuator/");

    /**
     * `.webmanifest` is in neither Spring's `mime.types` nor Boot's table, so
     * the app manifest would otherwise be served with no usable content type.
     * Browsers parse one regardless, which is why this is easy to leave wrong.
     *
     * On the container rather than the resource handler because
     * `ResourceHandlerRegistration` exposes no way to add one, and
     * `ResourceHttpRequestHandler` asks the servlet context first.
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webmanifestMimeType() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                // META-INF/resources is where the :web jar puts the Angular build.
                .addResourceLocations("classpath:/META-INF/resources/", "classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (API_PREFIXES.stream().anyMatch(resourcePath::startsWith)) {
                            return null;
                        }
                        Resource index = new ClassPathResource("META-INF/resources/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
