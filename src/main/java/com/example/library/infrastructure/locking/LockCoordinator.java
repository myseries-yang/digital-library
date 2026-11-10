package com.example.library.infrastructure.locking;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** 统一按账号、图书顺序加锁；本地锁和 Redis 锁都使用标准 Lock 接口。 */
public class LockCoordinator {
    private static final Logger log = LoggerFactory.getLogger(LockCoordinator.class);
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<String, ReentrantLock>();
    private final RedissonClient redis;
    private final long waitNanos;

    public LockCoordinator(Duration waitTime) {
        this(null, waitTime);
    }

    public LockCoordinator(RedissonClient redis, Duration waitTime) {
        if (waitTime.isNegative() || waitTime.isZero()) {
            throw new IllegalArgumentException("锁等待时间必须为正数");
        }
        this.redis = redis;
        this.waitNanos = waitTime.toNanos();
    }

    public String getMode() {
        return redis == null ? "local" : "redis";
    }

    public LockHandle acquire(long accountId, long bookId) {
        Lock account = null;
        Lock book = null;
        boolean accountHeld = false;
        boolean bookHeld = false;
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            account = findLock("digital-library:lock:account:" + accountId);
            accountHeld = account.tryLock(waitNanos, TimeUnit.NANOSECONDS);
            if (!accountHeld) {
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
            }
            long remaining = waitNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
            }
            book = findLock("digital-library:lock:book:" + bookId);
            // RLock 不传固定租期，由 Redisson watchdog 续期。
            bookHeld = book.tryLock(remaining, TimeUnit.NANOSECONDS);
            if (!bookHeld) {
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
            }
            log.info("locks_acquired mode={} accountId={} bookId={} waitMs={}",
                    getMode(), accountId, bookId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            LockHandle handle = new LockHandle(account, book, getMode(), accountId, bookId);
            acquired = true;
            return handle;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT, "操作等待被中断，请稍后重试", ex);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.LOCK_SERVICE_UNAVAILABLE,
                    ErrorCode.LOCK_SERVICE_UNAVAILABLE.getMessage(), ex);
        } finally {
            if (!acquired) {
                if (bookHeld) {
                    release(book, getMode(), "book", bookId);
                }
                if (accountHeld) {
                    release(account, getMode(), "account", accountId);
                }
            }
        }
    }

    private Lock findLock(String name) {
        if (redis != null) {
            return redis.getLock(name);
        }
        // 只登记已验证存在的预置资源，避免无效 ID 导致注册表无限增长。
        return localLocks.computeIfAbsent(name, key -> new ReentrantLock());
    }

    private static void release(Lock lock, String mode, String resource, long id) {
        try {
            lock.unlock();
        } catch (RuntimeException ex) {
            // 解锁失败不能覆盖已经确认提交的结果或原始业务异常。
            log.error("lock_release_failed mode={} resource={} id={}", mode, resource, id, ex);
        }
    }

    public static final class LockHandle implements AutoCloseable {
        private final Lock account;
        private final Lock book;
        private final String mode;
        private final long accountId;
        private final long bookId;
        private boolean closed;

        private LockHandle(Lock account, Lock book, String mode, long accountId, long bookId) {
            this.account = account;
            this.book = book;
            this.mode = mode;
            this.accountId = accountId;
            this.bookId = bookId;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release(book, mode, "book", bookId);
                release(account, mode, "account", accountId);
            }
        }
    }
}
