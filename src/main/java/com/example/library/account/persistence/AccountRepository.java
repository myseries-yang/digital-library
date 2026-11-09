package com.example.library.account.persistence;

import com.example.library.account.model.LibraryAccount;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.persistence.JdbcTime;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AccountRepository {
    private static final RowMapper<LibraryAccount> MAPPER = (rs, row) -> new LibraryAccount(
            rs.getLong("id"), rs.getString("account_no"), rs.getString("account_name"), rs.getInt("active_loan_count"));
    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<LibraryAccount> all() {
        return jdbc.query("SELECT id, account_no, account_name, active_loan_count FROM library_account ORDER BY id", MAPPER);
    }

    public LibraryAccount require(long id) {
        return jdbc.query("SELECT id, account_no, account_name, active_loan_count FROM library_account WHERE id=:id",
                Map.of("id", id), MAPPER).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    public int reserveOne(long id, int maxActiveLoans, Instant now) {
        return jdbc.update("""
                UPDATE library_account
                SET active_loan_count=active_loan_count+1, updated_at=:now
                WHERE id=:id AND active_loan_count < :limit
                """, new MapSqlParameterSource("id", id).addValue("limit", maxActiveLoans).addValue("now", JdbcTime.write(now)));
    }

    public int releaseOne(long id, Instant now) {
        return jdbc.update("""
                UPDATE library_account
                SET active_loan_count=active_loan_count-1, updated_at=:now
                WHERE id=:id AND active_loan_count > 0
                """, new MapSqlParameterSource("id", id).addValue("now", JdbcTime.write(now)));
    }
}
