package com.example.library.infrastructure.config;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LockHealthConfiguration {
    @Bean
    public HealthIndicator lockServiceHealthIndicator(LibraryProperties properties, ObjectProvider<RedissonClient> clients) {
        return () -> {
            if (!"redis".equals(properties.lock().mode())) {
                return Health.up().withDetail("mode", "local").withDetail("scope", "single JVM").build();
            }
            try {
                // EXISTS is a read-only health probe, not a data cache or a new Redis record.
                clients.getObject().getBucket("digital-library:health").isExists();
                return Health.up().withDetail("mode", "redis").build();
            } catch (RuntimeException ex) {
                return Health.down().withDetail("mode", "redis")
                        .withDetail("reason", "Lock service unavailable; database reads remain available").build();
            }
        };
    }
}
