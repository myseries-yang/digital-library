package com.example.library.infrastructure.locking;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class LocalLockCoordinator extends OrderedLockCoordinator {
    // Only validated, pre-seeded resource IDs reach this map. No CRUD and no unsafe lock eviction.
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalLockCoordinator(Duration waitTime) { super(waitTime); }
    @Override public String mode() { return "local"; }

    @Override
    protected ManagedLock lock(String name) {
        ReentrantLock delegate = locks.computeIfAbsent(name, ignored -> new ReentrantLock());
        return new ManagedLock() {
            @Override public boolean tryLock(long nanos) throws InterruptedException {
                return delegate.tryLock(nanos, TimeUnit.NANOSECONDS);
            }
            @Override public void unlock() { delegate.unlock(); }
        };
    }
}
