package com.example.library.policy.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

/** Global borrowing policy / 全局借阅规则。每次借阅保留其快照。 */
public record BorrowingPolicy(long id, int maxActiveLoans, int loanDays, long version) {
    public BorrowingPolicy {
        validate(maxActiveLoans, loanDays);
        if (id != 1 || version < 1) {
            throw new IllegalArgumentException("全局规则标识或版本不正确");
        }
    }

    public static void validate(int maxActiveLoans, int loanDays) {
        if (maxActiveLoans < 1 || loanDays < 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "借阅数量和借期必须大于零");
        }
    }
}
