package com.example.library.infrastructure.locking;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Only coordinates business lock order. Redis/Java libraries implement mutual exclusion. */
public abstract class OrderedLockCoordinator implements LockCoordinator {
    private static final Logger log = LoggerFactory.getLogger(OrderedLockCoordinator.class);
    private final long waitNanos;

    protected OrderedLockCoordinator(Duration waitTime) {
        if (waitTime.isNegative() || waitTime.isZero()) throw new IllegalArgumentException("锁等待时间必须为正数");
        waitNanos = waitTime.toNanos();
    }

    protected interface ManagedLock {
        boolean tryLock(long nanos) throws InterruptedException;
        void unlock();
    }

    protected abstract ManagedLock lock(String name);

    @Override
    public <T> T withLocks(long accountId, long bookId, Supplier<T> action) {
        ManagedLock account = null;
        ManagedLock book = null;
        boolean accountHeld = false;
        boolean bookHeld = false;
        long started = System.nanoTime();
        try {
            try {
                account = lock("digital-library:lock:account:" + accountId);
                accountHeld = account.tryLock(waitNanos);
                if (!accountHeld) throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
                long remaining = waitNanos - (System.nanoTime() - started);
                if (remaining <= 0) throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
                book = lock("digital-library:lock:book:" + bookId);
                bookHeld = book.tryLock(remaining);
                if (!bookHeld) throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT, "操作等待被中断，请稍后重试", ex);
            } catch (BusinessException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.LOCK_SERVICE_UNAVAILABLE,
                        ErrorCode.LOCK_SERVICE_UNAVAILABLE.message(), ex);
            }
            log.info("locks_acquired mode={} accountId={} bookId={} waitMs={}",
                    mode(), accountId, bookId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            // Callback errors are deliberately outside the acquisition error translation.
            return action.get();
        } finally {
            if (bookHeld) release(book, "book", bookId);
            if (accountHeld) release(account, "account", accountId);
        }
    }

    private void release(ManagedLock lock, String resource, long id) {
        try {
            lock.unlock();
        } catch (RuntimeException ex) {
            // A release failure must never turn a confirmed commit into an apparent rollback.
            log.error("lock_release_failed mode={} resource={} id={}", mode(), resource, id, ex);
        }
    }
}
