package com.example.library.loan.application;

import com.example.library.account.persistence.AccountRepository;
import com.example.library.book.persistence.BookRepository;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.locking.LockCoordinator;
import com.example.library.infrastructure.transaction.CommandTransactions;
import com.example.library.loan.model.Loan;
import com.example.library.loan.persistence.LoanRepository;
import com.example.library.policy.persistence.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
        this.accounts = accounts; this.books = books; this.loans = loans; this.policies = policies;
        this.queries = queries; this.locks = locks; this.transactions = transactions; this.clock = clock;
    }

    public LoanView borrow(long accountId, long bookId) {
        // Reject unknown resource IDs before they can enter the local lock registry.
        accounts.require(accountId);
        books.require(bookId);
        long started = System.nanoTime();
        LoanView result = locks.withLocks(accountId, bookId, () -> transactions.execute(() -> {
            var policy = policies.current(); // Exactly one complete policy snapshot per borrowing.
            if (loans.isCurrentlyBorrowed(accountId, bookId)) {
                throw new BusinessException(ErrorCode.BOOK_ALREADY_BORROWED);
            }
            accounts.require(accountId).requireBorrowingCapacity(policy);
            books.require(bookId).requireAvailableLicense();
            Loan loan = Loan.borrow(accountId, bookId, policy, clock.instant());
            if (accounts.reserveOne(accountId, policy.maxActiveLoans(), loan.borrowedAt()) != 1) {
                throw new BusinessException(ErrorCode.ACCOUNT_LIMIT_REACHED);
            }
            if (books.reserveOne(bookId, loan.borrowedAt()) != 1) {
                throw new BusinessException(ErrorCode.BOOK_UNAVAILABLE);
            }
            long loanId = loans.insert(loan);
            loans.insertCurrent(loanId, accountId, bookId, loan.borrowedAt());
            return queries.require(loanId);
        }));
        log.info("borrow_committed accountId={} bookId={} loanId={} elapsedMs={}",
                accountId, bookId, result.id(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    public LoanView returnLoan(long accountId, long loanId) {
        accounts.require(accountId);
        Loan existing = loans.require(loanId);
        if (existing.accountId() != accountId) throw new BusinessException(ErrorCode.LOAN_OWNERSHIP_MISMATCH);
        long started = System.nanoTime();
        LoanView result = locks.withLocks(accountId, existing.bookId(), () -> transactions.execute(() -> {
            Loan current = loans.require(loanId);
            Loan returned = current.returnAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
            int changed = loans.markReturned(loanId, accountId, returned.returnedAt());
            if (changed == 0) return queries.require(loanId); // Already returned: do not release twice.
            BusinessException.requireOne(changed, "归还借阅状态");
            BusinessException.requireOne(accounts.releaseOne(accountId, returned.returnedAt()), "释放账号额度");
            BusinessException.requireOne(books.releaseOne(current.bookId(), returned.returnedAt()), "释放图书名额");
            BusinessException.requireOne(loans.deleteCurrent(loanId, accountId, current.bookId()), "删除当前借阅关系");
            return queries.require(loanId);
        }));
        log.info("return_committed accountId={} bookId={} loanId={} elapsedMs={}",
                accountId, result.bookId(), result.id(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }
}
