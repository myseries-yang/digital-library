package com.example.library;

import com.example.library.account.model.LibraryAccount;
import com.example.library.book.model.Book;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.loan.model.Loan;
import com.example.library.policy.model.BorrowingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;

class BusinessModelsTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    private final BorrowingPolicy policy = new BorrowingPolicy(1, 10, 15, 7);

    @Test
    @DisplayName("借阅保存完整规则快照，期限为15×24小时")
    void borrowingCapturesTerms() {
        Loan loan = Loan.borrow(1, 2, policy, clock.instant());
        assertThat(loan.getDueAt()).isEqualTo(clock.instant().plus(15, ChronoUnit.DAYS));
        assertThat(loan.getPolicyVersion()).isEqualTo(7);
        assertThat(loan.getMaxActiveLoansSnapshot()).isEqualTo(10);
        assertThat(loan.getLoanDaysSnapshot()).isEqualTo(15);
    }

    @Test
    @DisplayName("应还时刻成为逾期，但没有发生归还")
    void overdueIsDerivedWithoutReturning() {
        Loan loan = Loan.borrow(1, 2, policy, clock.instant());
        assertThat(loan.isOverdue(loan.getDueAt().minusNanos(1))).isFalse();
        assertThat(loan.isOverdue(loan.getDueAt())).isTrue();
        assertThat(loan.getReturnedAt()).isNull();
    }

    @Test
    @DisplayName("重复归还保留第一次时间，已归还记录不再逾期")
    void returningIsIdempotent() {
        Loan returned = Loan.borrow(1, 2, policy, clock.instant()).returnAt(clock.instant().plusSeconds(20));
        assertThat(returned.returnAt(clock.instant().plusSeconds(60))).isSameAs(returned);
        assertThat(returned.isOverdue(returned.getDueAt().plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("降低上限允许保留已有数量，但阻止新借阅")
    void lowerLimitOnlyBlocksNewBorrowing() {
        LibraryAccount account = new LibraryAccount(1, "LIB001", "演示", 8);
        BorrowingPolicy reduced = new BorrowingPolicy(1, 5, 7, 8);
        assertThatThrownBy(() -> account.requireBorrowingCapacity(reduced))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        assertThat(account.getActiveLoanCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("无授权名额不可借阅，规则值必须为正数")
    void invalidCapacityAndPolicyAreRejected() {
        assertThatThrownBy(() -> new Book(1, "图书", "作者", "", 1, 0).requireAvailableLicense())
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new BorrowingPolicy(1, 0, 15, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new BorrowingPolicy(1, 10, -1, 1)).isInstanceOf(BusinessException.class);
    }
}
