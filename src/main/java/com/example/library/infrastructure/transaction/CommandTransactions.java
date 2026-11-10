package com.example.library.infrastructure.transaction;

import com.example.library.infrastructure.config.LibraryProperties;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 显式管理真实提交边界，调用方在提交或回滚完成后再释放业务锁。 */
@Component
public class CommandTransactions {
    private final PlatformTransactionManager manager;
    private final DefaultTransactionDefinition definition;

    public CommandTransactions(PlatformTransactionManager manager, LibraryProperties properties) {
        this.manager = manager;
        this.definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        definition.setTimeout(Math.toIntExact(properties.getTransactionTimeout().getSeconds()));
    }

    public TransactionStatus begin() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("借还用例不能被外部未提交事务包裹");
        }
        return manager.getTransaction(definition);
    }

    public void commit(TransactionStatus transaction) {
        try {
            manager.commit(transaction);
        } catch (UnexpectedRollbackException ex) {
            throw new BusinessException(ErrorCode.TRANSACTION_RETRYABLE,
                    ErrorCode.TRANSACTION_RETRYABLE.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                    ErrorCode.COMMIT_OUTCOME_UNKNOWN.getMessage(), ex);
        }
    }

    public void rollback(TransactionStatus transaction) {
        if (!transaction.isCompleted()) {
            manager.rollback(transaction);
        }
    }
}
