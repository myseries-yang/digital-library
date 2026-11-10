package com.example.library.loan.application;

import com.example.library.account.persistence.AccountRepository;
import com.example.library.book.persistence.BookRepository;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.locking.LockCoordinator;
import com.example.library.infrastructure.locking.LockCoordinator.LockHandle;
import com.example.library.infrastructure.transaction.CommandTransactions;
import com.example.library.loan.model.Loan;
import com.example.library.loan.persistence.LoanRepository;
import com.example.library.policy.model.BorrowingPolicy;
import com.example.library.policy.persistence.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
public class LoanService {
    private static final Logger log = LoggerFactory.getLogger(LoanService.class);
    private final AccountRepository accounts;
    private final BookRepository books;
    private final LoanRepository loans;
    private final PolicyRepository policies;
    private final LoanQueries queries;
    private final LockCoordinator locks;
    private final CommandTransactions transactions;
    private final Clock clock;

    public LoanService(AccountRepository accounts, BookRepository books, LoanRepository loans,
                       PolicyRepository policies, LoanQueries queries, LockCoordinator locks,
                       CommandTransactions transactions, Clock clock) {
        this.accounts = accounts;
        this.books = books;
        this.loans = loans;
        this.policies = policies;
        this.queries = queries;
        this.locks = locks;
        this.transactions = transactions;
        this.clock = clock;
    }

    public LoanView borrow(long accountId, long bookId) {
        // 无效资源不进入本地锁注册表。
        accounts.require(accountId);
        books.require(bookId);
        long started = System.nanoTime();
        LoanView result;
        try (LockHandle handle = locks.acquire(accountId, bookId)) {
            TransactionStatus transaction = transactions.begin();
            try {
                result = borrowInTransaction(accountId, bookId);
            } catch (RuntimeException | Error ex) {
                transactions.rollback(transaction);
                throw ex;
            }
            // 提交异常单独处理，提交结果未知时不能再次尝试回滚或重放借阅。
            transactions.commit(transaction);
        }
        log.info("borrow_committed accountId={} bookId={} loanId={} elapsedMs={}",
                accountId, bookId, result.getId(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private LoanView borrowInTransaction(long accountId, long bookId) {
        BorrowingPolicy policy = policies.current();
        if (loans.isCurrentlyBorrowed(accountId, bookId)) {
            throw new BusinessException(ErrorCode.BOOK_ALREADY_BORROWED);
        }
        accounts.require(accountId).requireBorrowingCapacity(policy);
        books.require(bookId).requireAvailableLicense();
        Loan loan = Loan.borrow(accountId, bookId, policy, clock.instant());
        if (accounts.reserveOne(accountId, policy.getMaxActiveLoans(), loan.getBorrowedAt()) != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED);
        }
        if (books.reserveOne(bookId, loan.getBorrowedAt()) != 1) {
            throw new BusinessException(ErrorCode.BOOK_UNAVAILABLE);
        }
        long loanId = loans.insert(loan);
        loans.insertCurrent(loanId, accountId, bookId, loan.getBorrowedAt());
        return queries.require(loanId);
    }

    public LoanView returnLoan(long accountId, long loanId) {
        accounts.require(accountId);
        Loan existing = loans.require(loanId);
        if (existing.getAccountId() != accountId) {
            throw new BusinessException(ErrorCode.LOAN_OWNERSHIP_MISMATCH);
        }
        long started = System.nanoTime();
        LoanView result;
        try (LockHandle handle = locks.acquire(accountId, existing.getBookId())) {
            TransactionStatus transaction = transactions.begin();
            try {
                result = returnInTransaction(accountId, loanId);
            } catch (RuntimeException | Error ex) {
                transactions.rollback(transaction);
                throw ex;
            }
            transactions.commit(transaction);
        }
        log.info("return_committed accountId={} bookId={} loanId={} elapsedMs={}",
                accountId, result.getBookId(), result.getId(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private LoanView returnInTransaction(long accountId, long loanId) {
        Loan current = loans.require(loanId);
        Loan returned = current.returnAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        int changed = loans.markReturned(loanId, accountId, returned.getReturnedAt());
        if (changed == 0) {
            return queries.require(loanId); // 已归还时不重复释放额度。
        }
        BusinessException.requireOne(changed, "归还借阅状态");
        BusinessException.requireOne(accounts.releaseOne(accountId, returned.getReturnedAt()), "释放账号额度");
        BusinessException.requireOne(books.releaseOne(current.getBookId(), returned.getReturnedAt()), "释放图书名额");
        BusinessException.requireOne(loans.deleteCurrent(loanId, accountId, current.getBookId()), "删除当前借阅关系");
        return queries.require(loanId);
    }
}
