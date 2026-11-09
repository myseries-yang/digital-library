package com.example.library.infrastructure.transaction;

import com.example.library.infrastructure.config.LibraryProperties;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns the actual commit boundary, so locks can be released after execute returns. */
@Component
public class CommandTransactions {
    private final TransactionTemplate template;

    public CommandTransactions(PlatformTransactionManager manager, LibraryProperties properties) {
        template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(Math.toIntExact(properties.transactionTimeout().toSeconds()));
    }

    public <T> T execute(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("借还用例不能被外部未提交事务包裹");
        }
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        try {
            return template.execute(status -> {
                T result = action.get();
                callbackCompleted.set(true);
                return result;
            });
        } catch (UnexpectedRollbackException ex) {
            throw new BusinessException(ErrorCode.TRANSACTION_RETRYABLE, ErrorCode.TRANSACTION_RETRYABLE.message(), ex);
        } catch (RuntimeException ex) {
            if (callbackCompleted.get()) {
                // The callback finished: this error belongs to commit/completion, not a rejected write.
                throw new BusinessException(ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN.message(), ex);
            }
            throw ex;
        }
    }
}
