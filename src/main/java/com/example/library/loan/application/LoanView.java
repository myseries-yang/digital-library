package com.example.library.loan.application;

import java.time.Instant;

/** Query view / 账号和图书借阅信息共用的查询结果。 */
public record LoanView(long id, long accountId, String accountNo, String accountName,
                       long bookId, String bookTitle, Instant borrowedAt, Instant dueAt,
                       Instant returnedAt, boolean overdue) {}
