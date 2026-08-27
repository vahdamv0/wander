package com.wander.common;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String version;

    public HealthController(@Value("${wander.version:dev}") String version) {
        this.version = version;
    }

    /** Public so a reverse proxy or container healthcheck can hit it unauthenticated. */
    @PublicEndpoint
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", version);
    }
}
