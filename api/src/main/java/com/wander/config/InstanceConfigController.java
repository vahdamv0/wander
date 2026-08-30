package com.wander.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wander.common.PublicEndpoint;
import com.wander.config.dto.InstanceConfig;
import com.wander.config.dto.MapConfig;
import com.wander.config.dto.SignInConfig;

/**
 * Read-only instance settings for the client.
 *
 * Authenticated, not public: none of it is secret, but nothing anonymous needs
 * it either — the map and the search box both live behind sign-in, so leaving it
 * off the allow-list keeps the default-deny rule intact for free.
 */
@RestController
@RequestMapping("/api/config")
public class InstanceConfigController {

    private final WanderProperties properties;

    public InstanceConfigController(WanderProperties properties) {
        this.properties = properties;
    }

    /**
     * Public, and the only endpoint here that is.
     *
     * The sign-in page runs before anybody has a session, so it cannot read the
     * authenticated config above — which is why the login page went on offering
     * "Create one" on an instance with registration switched off. Deliberately
     * the narrowest thing that fixes that: one boolean, not the operator's
     * configuration.
     */
    @GetMapping("/sign-in")
    @PublicEndpoint
    public SignInConfig getSignInConfig() {
        return new SignInConfig(properties.registrationEnabled());
    }

    @GetMapping
    public InstanceConfig getInstanceConfig() {
        WanderProperties.MapTiles map = properties.map();
        return new InstanceConfig(properties.geocoding().enabled(), properties.currency(),
                properties.weather().enabled(),
                new MapConfig(map.enabled(), map.styleUrl(), map.darkStyleUrl(), map.tileUrl(),
                        map.attribution(), map.maxZoom()));
    }
}
