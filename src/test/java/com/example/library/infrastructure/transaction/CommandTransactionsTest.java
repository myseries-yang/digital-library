package com.example.library.infrastructure.transaction;

import com.example.library.infrastructure.config.LibraryProperties;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandTransactionsTest {
    private final LibraryProperties properties = new LibraryProperties(Duration.ofSeconds(5), null, null);

    @Test
    void businessFailureRollsBackAndRemainsTheOriginalError() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var transactions = new CommandTransactions(manager, properties);
        assertThatThrownBy(() -> transactions.execute(() -> { throw new BusinessException(ErrorCode.BOOK_UNAVAILABLE); }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo(ErrorCode.BOOK_UNAVAILABLE);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void commitCommunicationFailureIsNotMisreportedAsRollback() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        doThrow(new TransactionSystemException("connection lost at commit")).when(manager).commit(status);
        var transactions = new CommandTransactions(manager, properties);
        assertThatThrownBy(() -> transactions.execute(() -> "writes-completed"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo(ErrorCode.COMMIT_OUTCOME_UNKNOWN);
    }
}
