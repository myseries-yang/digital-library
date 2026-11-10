package com.example.library.loan.application;

import java.time.Instant;

/** 借阅查询结果，包含账号、图书及逾期状态。 */
public class LoanView {
    private long id;
    private long accountId;
    private String accountNo;
    private String accountName;
    private long bookId;
    private String bookTitle;
    private Instant borrowedAt;
    private Instant dueAt;
    private Instant returnedAt;
    private boolean overdue;

    public LoanView() {
    }

    public LoanView(long id, long accountId, String accountNo, String accountName, long bookId, String bookTitle,
                    Instant borrowedAt, Instant dueAt, Instant returnedAt, boolean overdue) {
        this.id = id;
        this.accountId = accountId;
        this.accountNo = accountNo;
        this.accountName = accountName;
        this.bookId = bookId;
        this.bookTitle = bookTitle;
        this.borrowedAt = borrowedAt;
        this.dueAt = dueAt;
        this.returnedAt = returnedAt;
        this.overdue = overdue;
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

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public long getBookId() {
        return bookId;
    }

    public void setBookId(long bookId) {
        this.bookId = bookId;
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public void setBookTitle(String bookTitle) {
        this.bookTitle = bookTitle;
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

    public boolean isOverdue() {
        return overdue;
    }

    public void setOverdue(boolean overdue) {
        this.overdue = overdue;
    }

}
