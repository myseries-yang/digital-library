package com.example.library.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("library")
public class LibraryProperties {
    private Duration transactionTimeout;
    private LockSettings lock;
    private RedisSettings redis;

    public LibraryProperties() {
    }

    public LibraryProperties(Duration transactionTimeout, LockSettings lock, RedisSettings redis) {
        this.transactionTimeout = transactionTimeout;
        this.lock = lock;
        this.redis = redis;
    }

    public Duration getTransactionTimeout() {
        return transactionTimeout;
    }

    public void setTransactionTimeout(Duration transactionTimeout) {
        this.transactionTimeout = transactionTimeout;
    }

    public LockSettings getLock() {
        return lock;
    }

    public void setLock(LockSettings lock) {
        this.lock = lock;
    }

    public RedisSettings getRedis() {
        return redis;
    }

    public void setRedis(RedisSettings redis) {
        this.redis = redis;
    }
    public static class LockSettings {
        private String mode;
        private Duration waitTime;
        private Duration watchdogTimeout;

        public LockSettings() {
        }

        public LockSettings(String mode, Duration waitTime, Duration watchdogTimeout) {
            this.mode = mode;
            this.waitTime = waitTime;
            this.watchdogTimeout = watchdogTimeout;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Duration getWaitTime() {
            return waitTime;
        }

        public void setWaitTime(Duration waitTime) {
            this.waitTime = waitTime;
        }

        public Duration getWatchdogTimeout() {
            return watchdogTimeout;
        }

        public void setWatchdogTimeout(Duration watchdogTimeout) {
            this.watchdogTimeout = watchdogTimeout;
        }
    }

    public static class RedisSettings {
        private String address;
        private String password;
        private Duration connectTimeout;
        private Duration commandTimeout;

        public RedisSettings() {
        }

        public RedisSettings(String address, String password, Duration connectTimeout, Duration commandTimeout) {
            this.address = address;
            this.password = password;
            this.connectTimeout = connectTimeout;
            this.commandTimeout = commandTimeout;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }
    }
}
