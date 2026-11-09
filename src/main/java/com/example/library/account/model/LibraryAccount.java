package com.example.library.account.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.policy.model.BorrowingPolicy;

/** Library account / 图书馆账号，不关联自然人身份。 */
public record LibraryAccount(long id, String accountNo, String accountName, int activeLoanCount) {
    public void requireBorrowingCapacity(BorrowingPolicy policy) {
        if (activeLoanCount >= policy.maxActiveLoans()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED);
        }
    }
}
