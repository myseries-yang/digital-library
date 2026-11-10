package com.example.library.loan.persistence;

import com.example.library.loan.model.Loan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;

@Mapper
public interface LoanMapper {
    Loan selectById(@Param("id") long id);

    int countCurrent(@Param("accountId") long accountId, @Param("bookId") long bookId);

    int insert(Loan loan);

    int insertCurrent(@Param("loanId") long loanId, @Param("accountId") long accountId,
                      @Param("bookId") long bookId, @Param("now") Instant now);

    int markReturned(@Param("id") long id, @Param("accountId") long accountId, @Param("now") Instant now);

    int deleteCurrent(@Param("loanId") long loanId, @Param("accountId") long accountId, @Param("bookId") long bookId);
}
