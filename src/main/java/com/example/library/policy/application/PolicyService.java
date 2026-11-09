package com.example.library.policy.application;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.transaction.CommandTransactions;
import com.example.library.policy.model.BorrowingPolicy;
import com.example.library.policy.persistence.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private final PolicyRepository policies;
    private final CommandTransactions transactions;
    private final Clock clock;

    public PolicyService(PolicyRepository policies, CommandTransactions transactions, Clock clock) {
        this.policies = policies; this.transactions = transactions; this.clock = clock;
    }

    public BorrowingPolicy current() { return policies.current(); }

    public BorrowingPolicy update(int limit, int days, long expectedVersion) {
        BorrowingPolicy.validate(limit, days);
        try {
            if (clock.instant().plus(days, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC).getYear() > 9999) {
                throw new DateTimeException("MySQL DATETIME range exceeded");
            }
        } catch (DateTimeException | ArithmeticException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "借期超出数据库支持的日期范围");
        }
        BorrowingPolicy updated = transactions.execute(() -> {
            if (policies.update(limit, days, expectedVersion, clock.instant()) != 1) {
                throw new BusinessException(ErrorCode.POLICY_VERSION_CONFLICT);
            }
            return policies.current();
        });
        log.info("policy_updated version={} maxActiveLoans={} loanDays={}", updated.version(), limit, days);
        return updated;
    }
}
