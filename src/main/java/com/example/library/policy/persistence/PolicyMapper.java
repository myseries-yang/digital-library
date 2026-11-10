package com.example.library.policy.persistence;

import com.example.library.policy.model.BorrowingPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;

@Mapper
public interface PolicyMapper {
    BorrowingPolicy selectCurrent();

    int update(@Param("maxActiveLoans") int maxActiveLoans, @Param("loanDays") int loanDays,
               @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);
}
