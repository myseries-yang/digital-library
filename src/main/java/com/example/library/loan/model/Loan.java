package com.example.library.loan.model;

import com.example.library.policy.model.BorrowingPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Loan / 一次借阅。到期是派生状态，不等同于归还。 */
public record Loan(long id, long accountId, long bookId, long policyId, long policyVersion,
                   int maxActiveLoansSnapshot, int loanDaysSnapshot,
                   Instant borrowedAt, Instant dueAt, Instant returnedAt) {
    public static Loan borrow(long accountId, long bookId, BorrowingPolicy policy, Instant now) {
        Instant borrowedAt = now.truncatedTo(ChronoUnit.MICROS);
        return new Loan(0, accountId, bookId, policy.id(), policy.version(),
                policy.maxActiveLoans(), policy.loanDays(), borrowedAt,
                borrowedAt.plus(policy.loanDays(), ChronoUnit.DAYS), null);
    }

    public boolean isReturned() { return returnedAt != null; }
    public boolean isOverdue(Instant now) { return !isReturned() && !now.isBefore(dueAt); }

    public Loan returnAt(Instant now) {
        if (isReturned()) return this;
        return new Loan(id, accountId, bookId, policyId, policyVersion,
                maxActiveLoansSnapshot, loanDaysSnapshot, borrowedAt, dueAt,
                now.truncatedTo(ChronoUnit.MICROS));
    }
}
