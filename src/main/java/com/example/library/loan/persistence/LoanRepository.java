package com.example.library.loan.persistence;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.persistence.JdbcTime;
import com.example.library.loan.model.Loan;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Repository
public class LoanRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LoanRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Loan require(long id) {
        return jdbc.query("""
                SELECT id, account_id, book_id, policy_id, policy_version,
                       max_active_loans_snapshot, loan_days_snapshot, borrowed_at, due_at, returned_at
                FROM loan WHERE id=:id
                """, Map.of("id", id),
                (rs, row) -> new Loan(rs.getLong("id"), rs.getLong("account_id"), rs.getLong("book_id"),
                        rs.getLong("policy_id"), rs.getLong("policy_version"),
                        rs.getInt("max_active_loans_snapshot"), rs.getInt("loan_days_snapshot"),
                        JdbcTime.read(rs, "borrowed_at"), JdbcTime.read(rs, "due_at"), JdbcTime.read(rs, "returned_at")))
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.LOAN_NOT_FOUND));
    }

    public boolean isCurrentlyBorrowed(long accountId, long bookId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM current_loan WHERE account_id=:accountId AND book_id=:bookId
                """, Map.of("accountId", accountId, "bookId", bookId), Integer.class);
        return count != null && count > 0;
    }

    public long insert(Loan loan) {
        var key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO loan (account_id, book_id, policy_id, policy_version,
                  max_active_loans_snapshot, loan_days_snapshot, borrowed_at, due_at, created_at, updated_at)
                VALUES (:accountId, :bookId, :policyId, :policyVersion, :limit, :days, :borrowedAt, :dueAt, :borrowedAt, :borrowedAt)
                """, new MapSqlParameterSource("accountId", loan.accountId()).addValue("bookId", loan.bookId())
                .addValue("policyId", loan.policyId()).addValue("policyVersion", loan.policyVersion())
                .addValue("limit", loan.maxActiveLoansSnapshot()).addValue("days", loan.loanDaysSnapshot())
                .addValue("borrowedAt", JdbcTime.write(loan.borrowedAt())).addValue("dueAt", JdbcTime.write(loan.dueAt())),
                key, new String[]{"id"});
        Number id = key.getKey();
        if (id == null) throw new BusinessException(ErrorCode.DATA_INCONSISTENCY, "未取得借阅记录标识");
        return id.longValue();
    }

    public void insertCurrent(long loanId, long accountId, long bookId, Instant now) {
        try {
            int rows = jdbc.update("""
                    INSERT INTO current_loan (loan_id, account_id, book_id, created_at, updated_at)
                    SELECT id, account_id, book_id, :now, :now
                    FROM loan
                    WHERE id=:loanId AND account_id=:accountId AND book_id=:bookId AND returned_at IS NULL
                    """, new MapSqlParameterSource("loanId", loanId).addValue("accountId", accountId)
                    .addValue("bookId", bookId).addValue("now", JdbcTime.write(now)));
            // Logical references are enforced by the write protocol, not database foreign keys.
            BusinessException.requireOne(rows, "建立与借阅记录匹配的当前关系");
        } catch (DuplicateKeyException ex) {
            String constraint = ex.getMostSpecificCause().getMessage().toLowerCase(Locale.ROOT);
            if (constraint.contains("uk_current_account_book")) {
                throw new BusinessException(ErrorCode.BOOK_ALREADY_BORROWED, ErrorCode.BOOK_ALREADY_BORROWED.message(), ex);
            }
            throw ex;
        }
    }

    public int markReturned(long id, long accountId, Instant returnedAt) {
        return jdbc.update("""
                UPDATE loan SET returned_at=:now, updated_at=:now
                WHERE id=:id AND account_id=:accountId AND returned_at IS NULL
                """, new MapSqlParameterSource("id", id).addValue("accountId", accountId).addValue("now", JdbcTime.write(returnedAt)));
    }

    public int deleteCurrent(long loanId, long accountId, long bookId) {
        return jdbc.update("""
                DELETE FROM current_loan WHERE loan_id=:loanId AND account_id=:accountId AND book_id=:bookId
                """, Map.of("loanId", loanId, "accountId", accountId, "bookId", bookId));
    }
}
