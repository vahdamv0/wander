package com.wander.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.wander.config.WanderProperties;
import com.wander.sync.TripChanges;
import com.wander.trip.TripRepository;
import com.wander.user.UserRepository;

/**
 * Turns the scheduler on, and only on a demo instance.
 *
 * {@code DemoSweeper} is declared here rather than annotated {@code @Component}
 * so that an ordinary instance has neither the bean nor a scheduler thread —
 * wander has no other scheduled work, and a periodic transaction that can only
 * ever find nothing is a thing an operator would reasonably ask about. The demo
 * seeder is unconditional and returns early instead, because it has to run
 * exactly once and reads the flag itself; this one has a schedule attached to
 * it, and a schedule is easier not to create than to leave idling.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wander.demo", name = "enabled", havingValue = "true")
@EnableScheduling
class DemoScheduling {

    @Bean
    DemoSweeper demoSweeper(WanderProperties properties, UserRepository users, TripRepository trips,
            TripChanges changes) {
        return new DemoSweeper(properties, users, trips, changes);
    }
}
