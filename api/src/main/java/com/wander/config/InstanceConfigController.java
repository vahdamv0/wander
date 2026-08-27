package com.wander.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wander.config.dto.InstanceConfig;
import com.wander.config.dto.MapConfig;

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

    @GetMapping
    public InstanceConfig getInstanceConfig() {
        WanderProperties.MapTiles map = properties.map();
        return new InstanceConfig(properties.geocoding().enabled(),
                new MapConfig(map.enabled(), map.tileUrl(), map.attribution(), map.maxZoom()));
    }
}
