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
     *
     * It carries a second field now, and the bar it had to clear was not "is it
     * harmless" but "does it *have* to be anonymous". The source link does:
     * AGPL-3.0 section 13 owes source to everyone interacting with the instance
     * over a network, and on a public instance most of them never get past this
     * page. The rest of the operator's configuration stays authenticated.
     */
    @GetMapping("/sign-in")
    @PublicEndpoint
    public SignInConfig getSignInConfig() {
        WanderProperties.Demo demo = properties.demo();
        return new SignInConfig(properties.registrationEnabled(), properties.sourceUrl(),
                demo.enabled() ? demo.email() : "",
                demo.enabled() ? demo.password() : "");
    }

    @GetMapping
    public InstanceConfig getInstanceConfig() {
        WanderProperties.MapTiles map = properties.map();
        return new InstanceConfig(properties.geocoding().enabled(), properties.currency(),
                properties.weather().enabled(),
                new MapConfig(map.enabled(), map.styleUrl(), map.darkStyleUrl(), map.tileUrl(),
                        map.attribution(), map.maxZoom()),
                properties.version(), properties.buildRef(), properties.sourceUrl(),
                // Zero on an ordinary instance: there is no sweep, and nothing
                // for the client to promise anybody.
                properties.demo().enabled() ? properties.demo().sweepMinutes() : 0);
    }
}
