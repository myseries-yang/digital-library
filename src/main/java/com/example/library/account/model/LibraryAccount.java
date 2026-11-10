package com.example.library.account.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.policy.model.BorrowingPolicy;

/** 图书馆账号及当前借阅数量。 */
public class LibraryAccount {
    private long id;
    private String accountNo;
    private String accountName;
    private int activeLoanCount;

    public LibraryAccount() {
    }

    public LibraryAccount(long id, String accountNo, String accountName, int activeLoanCount) {
        this.id = id;
        this.accountNo = accountNo;
        this.accountName = accountName;
        this.activeLoanCount = activeLoanCount;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
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

    public int getActiveLoanCount() {
        return activeLoanCount;
    }

    public void setActiveLoanCount(int activeLoanCount) {
        this.activeLoanCount = activeLoanCount;
    }

    public void requireBorrowingCapacity(BorrowingPolicy policy) {
        if (activeLoanCount >= policy.getMaxActiveLoans()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED);
        }
    }

}
