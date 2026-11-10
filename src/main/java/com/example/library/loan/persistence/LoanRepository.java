package com.example.library.loan.persistence;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.loan.model.Loan;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Locale;

@Repository
public class LoanRepository {
    private final LoanMapper mapper;

    public LoanRepository(LoanMapper mapper) {
        this.mapper = mapper;
    }

    public Loan require(long id) {
        Loan loan = mapper.selectById(id);
        if (loan == null) {
            throw new BusinessException(ErrorCode.LOAN_NOT_FOUND);
        }
        return loan;
    }

    public boolean isCurrentlyBorrowed(long accountId, long bookId) {
        return mapper.countCurrent(accountId, bookId) > 0;
    }

    public long insert(Loan loan) {
        BusinessException.requireOne(mapper.insert(loan), "保存借阅记录");
        if (loan.getId() <= 0) {
            throw new BusinessException(ErrorCode.DATA_INCONSISTENCY, "未取得借阅记录标识");
        }
        return loan.getId();
    }

    public void insertCurrent(long loanId, long accountId, long bookId, Instant now) {
        try {
            BusinessException.requireOne(mapper.insertCurrent(loanId, accountId, bookId, now),
                    "建立与借阅记录匹配的当前关系");
        } catch (DuplicateKeyException ex) {
            String detail = ex.getMostSpecificCause().getMessage();
            if (detail != null && detail.toLowerCase(Locale.ROOT).contains("uk_current_account_book")) {
                throw new BusinessException(ErrorCode.BOOK_ALREADY_BORROWED,
                        ErrorCode.BOOK_ALREADY_BORROWED.getMessage(), ex);
            }
            throw ex;
        }
    }

    public int markReturned(long id, long accountId, Instant returnedAt) {
        return mapper.markReturned(id, accountId, returnedAt);
    }

    public int deleteCurrent(long loanId, long accountId, long bookId) {
        return mapper.deleteCurrent(loanId, accountId, bookId);
    }
}
