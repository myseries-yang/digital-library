package com.example.library.loan.model;

import com.example.library.policy.model.BorrowingPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 一次完整借阅，逾期不等于归还。 */
public class Loan {
    private long id;
    private long accountId;
    private long bookId;
    private long policyId;
    private long policyVersion;
    private int maxActiveLoansSnapshot;
    private int loanDaysSnapshot;
    private Instant borrowedAt;
    private Instant dueAt;
    private Instant returnedAt;

    public Loan() {
    }

    public Loan(long id, long accountId, long bookId, long policyId, long policyVersion, int maxActiveLoansSnapshot,
                int loanDaysSnapshot, Instant borrowedAt, Instant dueAt, Instant returnedAt) {
        this.id = id;
        this.accountId = accountId;
        this.bookId = bookId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.maxActiveLoansSnapshot = maxActiveLoansSnapshot;
        this.loanDaysSnapshot = loanDaysSnapshot;
        this.borrowedAt = borrowedAt;
        this.dueAt = dueAt;
        this.returnedAt = returnedAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public long getBookId() {
        return bookId;
    }

    public void setBookId(long bookId) {
        this.bookId = bookId;
    }

    public long getPolicyId() {
        return policyId;
    }

    public void setPolicyId(long policyId) {
        this.policyId = policyId;
    }

    public long getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(long policyVersion) {
        this.policyVersion = policyVersion;
    }

    public int getMaxActiveLoansSnapshot() {
        return maxActiveLoansSnapshot;
    }

    public void setMaxActiveLoansSnapshot(int maxActiveLoansSnapshot) {
        this.maxActiveLoansSnapshot = maxActiveLoansSnapshot;
    }

    public int getLoanDaysSnapshot() {
        return loanDaysSnapshot;
    }

    public void setLoanDaysSnapshot(int loanDaysSnapshot) {
        this.loanDaysSnapshot = loanDaysSnapshot;
    }

    public Instant getBorrowedAt() {
        return borrowedAt;
    }

    public void setBorrowedAt(Instant borrowedAt) {
        this.borrowedAt = borrowedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(Instant returnedAt) {
        this.returnedAt = returnedAt;
    }

    public static Loan borrow(long accountId, long bookId, BorrowingPolicy policy, Instant now) {
        Instant borrowedAt = now.truncatedTo(ChronoUnit.MICROS);
        return new Loan(0, accountId, bookId, policy.getId(), policy.getVersion(),
                policy.getMaxActiveLoans(), policy.getLoanDays(), borrowedAt,
                borrowedAt.plus(policy.getLoanDays(), ChronoUnit.DAYS), null);
    }

    public boolean isReturned() {
        return returnedAt != null;
    }

    public boolean isOverdue(Instant now) {
        return !isReturned() && !now.isBefore(dueAt);
    }

    public Loan returnAt(Instant now) {
        if (isReturned()) {
            return this;
        }
        return new Loan(id, accountId, bookId, policyId, policyVersion,
                maxActiveLoansSnapshot, loanDaysSnapshot, borrowedAt, dueAt,
                now.truncatedTo(ChronoUnit.MICROS));
    }

}
