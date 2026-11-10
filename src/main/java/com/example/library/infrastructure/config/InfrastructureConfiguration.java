package com.example.library.infrastructure.config;

import com.example.library.infrastructure.locking.LockCoordinator;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LibraryProperties.class)
public class InfrastructureConfiguration {
    @Bean
    public Clock libraryClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "local", matchIfMissing = true)
    public LockCoordinator localLocks(LibraryProperties properties) {
        return new LockCoordinator(properties.getLock().getWaitTime());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "redis")
    public RedissonClient redisson(LibraryProperties properties) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setLockWatchdogTimeout(properties.getLock().getWatchdogTimeout().toMillis());
        SingleServerConfig server = config.useSingleServer()
                .setAddress(properties.getRedis().getAddress())
                .setConnectTimeout(Math.toIntExact(properties.getRedis().getConnectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(properties.getRedis().getCommandTimeout().toMillis()))
                .setRetryAttempts(0);
        if (StringUtils.hasText(properties.getRedis().getPassword())) {
            server.setPassword(properties.getRedis().getPassword());
        }
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnProperty(name = "library.lock.mode", havingValue = "redis")
    public LockCoordinator redisLocks(RedissonClient client, LibraryProperties properties) {
        return new LockCoordinator(client, properties.getLock().getWaitTime());
    }
}
