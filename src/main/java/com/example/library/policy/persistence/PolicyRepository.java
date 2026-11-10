package com.example.library.policy.persistence;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.policy.model.BorrowingPolicy;
import org.springframework.stereotype.Repository;
import java.time.Instant;

@Repository
public class PolicyRepository {
    private final PolicyMapper mapper;

    public PolicyRepository(PolicyMapper mapper) {
        this.mapper = mapper;
    }

    public BorrowingPolicy current() {
        BorrowingPolicy policy = mapper.selectCurrent();
        if (policy == null) {
            throw new BusinessException(ErrorCode.DATA_INCONSISTENCY, "全局借阅规则不存在");
        }
        return policy;
    }

    public int update(int maxActiveLoans, int loanDays, long expectedVersion, Instant now) {
        return mapper.update(maxActiveLoans, loanDays, expectedVersion, now);
    }
}
