package com.example.library.infrastructure.locking;

import java.util.function.Supplier;

public interface LockCoordinator {
    <T> T withLocks(long accountId, long bookId, Supplier<T> action);
    String mode();
}
