package com.example.library.infrastructure.config;

import com.example.library.infrastructure.locking.LocalLockCoordinator;
import com.example.library.infrastructure.locking.RedisLockCoordinator;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LibraryProperties.class)
public class InfrastructureConfiguration {
    @Bean
    public Clock libraryClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "local", matchIfMissing = true)
    public LocalLockCoordinator localLocks(LibraryProperties properties) {
        return new LocalLockCoordinator(properties.lock().waitTime());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "redis")
    public RedissonClient redisson(LibraryProperties properties) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setLockWatchdogTimeout(properties.lock().watchdogTimeout().toMillis());
        var server = config.useSingleServer()
                .setAddress(properties.redis().address())
                .setConnectTimeout(Math.toIntExact(properties.redis().connectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(properties.redis().commandTimeout().toMillis()))
                .setRetryAttempts(0);
        if (properties.redis().password() != null && !properties.redis().password().isBlank()) {
            server.setPassword(properties.redis().password());
        }
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "redis")
    public RedisLockCoordinator redisLocks(RedissonClient client, LibraryProperties properties) {
        return new RedisLockCoordinator(client, properties.lock().waitTime());
    }
}
