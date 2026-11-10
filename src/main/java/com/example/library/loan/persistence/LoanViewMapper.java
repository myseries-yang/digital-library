package com.example.library.loan.persistence;

import com.example.library.loan.application.LoanView;
import com.example.library.loan.model.LoanScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface LoanViewMapper {
    LoanView selectById(@Param("id") long id);

    List<LoanView> search(@Param("accountId") Long accountId, @Param("bookId") Long bookId,
                          @Param("scope") LoanScope scope, @Param("now") Instant now,
                          @Param("limit") int limit, @Param("offset") long offset);
}
