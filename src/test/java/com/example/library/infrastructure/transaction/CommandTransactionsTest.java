package com.example.library.infrastructure.transaction;

import com.example.library.infrastructure.config.LibraryProperties;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandTransactionsTest {
    private final LibraryProperties properties = new LibraryProperties(Duration.ofSeconds(5), null, null);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final CommandTransactions transactions = new CommandTransactions(manager, properties);

    @Test
    void beginsOwnedReadCommittedTransactionWithConfiguredTimeout() {
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transactions.begin()).isSameAs(status);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
    }

    @Test
    void rejectsOuterUncommittedTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transactions.begin()).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(manager);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void commitCommunicationFailureIsNotMisreportedAsRollback() {
        TransactionStatus status = new SimpleTransactionStatus();
        doThrow(new TransactionSystemException("connection lost at commit")).when(manager).commit(status);
        assertThatThrownBy(() -> transactions.commit(status))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.COMMIT_OUTCOME_UNKNOWN);
        verify(manager, never()).rollback(any());
    }

    @Test
    void unexpectedRollbackIsReportedAsRetryable() {
        TransactionStatus status = new SimpleTransactionStatus();
        doThrow(new UnexpectedRollbackException("rolled back")).when(manager).commit(status);
        assertThatThrownBy(() -> transactions.commit(status))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.TRANSACTION_RETRYABLE);
    }
}
