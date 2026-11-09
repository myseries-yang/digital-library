package com.example.library.infrastructure.locking;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class OrderedLockCoordinatorTest {
    @Test
    void locksHaveFixedOrderAndReleaseAfterCallback() {
        List<String> events = new ArrayList<>();
        var locks = new FakeLocks(events, false, false);
        String result = locks.withLocks(2, 3, () -> { events.add("commit-completed"); return "success"; });
        assertThat(result).isEqualTo("success");
        assertThat(events).containsExactly("acquire-account", "acquire-book", "commit-completed", "release-book", "release-account");
    }

    @Test
    void secondLockFailureReleasesFirstWithoutRunningCallback() {
        List<String> events = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();
        assertThatThrownBy(() -> new FakeLocks(events, true, false).withLocks(2, 3, () -> ran.getAndSet(true)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo(ErrorCode.LOCK_ACQUIRE_TIMEOUT);
        assertThat(ran).isFalse();
        assertThat(events).containsExactly("acquire-account", "acquire-book", "release-account");
    }

    @Test
    void unlockFailureDoesNotMaskCommitOrBusinessRejection() {
        var locks = new FakeLocks(new ArrayList<>(), false, true);
        assertThat(locks.withLocks(2, 3, () -> "committed")).isEqualTo("committed");
        assertThatThrownBy(() -> locks.withLocks(2, 3, () -> { throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED); }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
    }

    private static class FakeLocks extends OrderedLockCoordinator {
        private final List<String> events;
        private final boolean failBook;
        private final boolean failRelease;

        FakeLocks(List<String> events, boolean failBook, boolean failRelease) {
            super(Duration.ofSeconds(1)); this.events = events; this.failBook = failBook; this.failRelease = failRelease;
        }
        @Override public String mode() { return "test"; }
        @Override protected ManagedLock lock(String name) {
            String kind = name.contains(":account:") ? "account" : "book";
            return new ManagedLock() {
                @Override public boolean tryLock(long nanos) {
                    events.add("acquire-" + kind);
                    return !(failBook && kind.equals("book"));
                }
                @Override public void unlock() {
                    events.add("release-" + kind);
                    if (failRelease) throw new IllegalStateException("injected release failure");
                }
            };
        }
    }
}
