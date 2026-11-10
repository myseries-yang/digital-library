package com.example.library.policy.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

/** 全局借阅规则，每次借阅保存完整规则快照。 */
public class BorrowingPolicy {
    private long id;
    private int maxActiveLoans;
    private int loanDays;
    private long version;

    public BorrowingPolicy() {
    }

    public BorrowingPolicy(long id, int maxActiveLoans, int loanDays, long version) {
        validate(maxActiveLoans, loanDays);
        if (id != 1 || version < 1) {
            throw new IllegalArgumentException("全局规则标识或版本不正确");
        }
        this.id = id;
        this.maxActiveLoans = maxActiveLoans;
        this.loanDays = loanDays;
        this.version = version;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getMaxActiveLoans() {
        return maxActiveLoans;
    }

    public void setMaxActiveLoans(int maxActiveLoans) {
        this.maxActiveLoans = maxActiveLoans;
    }

    public int getLoanDays() {
        return loanDays;
    }

    public void setLoanDays(int loanDays) {
        this.loanDays = loanDays;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public static void validate(int maxActiveLoans, int loanDays) {
        if (maxActiveLoans < 1 || loanDays < 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "借阅数量和借期必须大于零");
        }
    }

}
