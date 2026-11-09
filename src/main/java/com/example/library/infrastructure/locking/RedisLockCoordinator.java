package com.example.library.infrastructure.locking;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class RedisLockCoordinator extends OrderedLockCoordinator {
    private final RedissonClient client;

    public RedisLockCoordinator(RedissonClient client, Duration waitTime) {
        super(waitTime);
        this.client = client;
    }
    @Override public String mode() { return "redis"; }

    @Override
    protected ManagedLock lock(String name) {
        RLock delegate = client.getLock(name);
        return new ManagedLock() {
            @Override public boolean tryLock(long nanos) throws InterruptedException {
                // No fixed leaseTime: Redisson watchdog renews the acquired lock.
                return delegate.tryLock(nanos, TimeUnit.NANOSECONDS);
            }
            @Override public void unlock() { delegate.unlock(); }
        };
    }
}
