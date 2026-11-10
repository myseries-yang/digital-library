package com.example.library.infrastructure.locking;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LockCoordinatorTest {
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RLock account = mock(RLock.class);
    private final RLock book = mock(RLock.class);
    private final LockCoordinator locks = new LockCoordinator(redis, Duration.ofSeconds(1));

    @BeforeEach
    void configureLocks() throws Exception {
        when(redis.getLock("digital-library:lock:account:2")).thenReturn(account);
        when(redis.getLock("digital-library:lock:book:3")).thenReturn(book);
        when(account.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(book.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    }

    @Test
    void locksHaveFixedOrderAndReleaseOnlyWhenHandleCloses() throws Exception {
        LockCoordinator.LockHandle handle = locks.acquire(2, 3);
        verify(account, never()).unlock();
        verify(book, never()).unlock();
        handle.close();
        handle.close();
        InOrder order = inOrder(account, book);
        order.verify(account).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        order.verify(book).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        order.verify(book).unlock();
        order.verify(account).unlock();
        verify(account, times(1)).unlock();
        verify(book, times(1)).unlock();
    }

    @Test
    void secondLockFailureReleasesFirst() throws Exception {
        when(book.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
        assertThatThrownBy(() -> locks.acquire(2, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
        verify(account).unlock();
        verify(book, never()).unlock();
    }

    @Test
    void unlockFailureDoesNotMaskCommitOrBusinessRejection() {
        doThrow(new IllegalStateException("injected release failure")).when(book).unlock();
        try (LockCoordinator.LockHandle handle = locks.acquire(2, 3)) {
            // 正常结束不会被解锁异常覆盖。
        }
        assertThatThrownBy(() -> {
            try (LockCoordinator.LockHandle handle = locks.acquire(2, 3)) {
                throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED);
            }
        }).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        verify(account, times(2)).unlock();
    }

    @Test
    void interruptedWaitReleasesAccountAndPreservesInterruptFlag() throws Exception {
        when(book.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("interrupted"));
        try {
            assertThatThrownBy(() -> locks.acquire(2, 3))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(account).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void redisFailureIsReportedWithoutFallingBackToLocalLocks() {
        when(redis.getLock("digital-library:lock:account:2")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> locks.acquire(2, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.LOCK_SERVICE_UNAVAILABLE);
        verifyNoInteractions(account, book);
    }
}
