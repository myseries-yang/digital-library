package com.example.library.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("library")
public record LibraryProperties(Duration transactionTimeout, LockSettings lock, RedisSettings redis) {
    public record LockSettings(String mode, Duration waitTime, Duration watchdogTimeout) {}
    public record RedisSettings(String address, String password, Duration connectTimeout, Duration commandTimeout) {}
}
