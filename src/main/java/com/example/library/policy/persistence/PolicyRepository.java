package com.example.library.policy.persistence;

import com.example.library.infrastructure.persistence.JdbcTime;
import com.example.library.policy.model.BorrowingPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;

@Repository
public class PolicyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PolicyRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public BorrowingPolicy current() {
        return jdbc.queryForObject("""
                SELECT id, max_active_loans, loan_days, version FROM borrowing_policy WHERE id=1
                """, new MapSqlParameterSource(), (rs, row) -> new BorrowingPolicy(
                rs.getLong("id"), rs.getInt("max_active_loans"), rs.getInt("loan_days"), rs.getLong("version")));
    }

    public int update(int maxActiveLoans, int loanDays, long expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE borrowing_policy
                SET max_active_loans=:limit, loan_days=:days, version=version+1, updated_at=:now
                WHERE id=1 AND version=:version
                """, new MapSqlParameterSource("limit", maxActiveLoans).addValue("days", loanDays)
                .addValue("version", expectedVersion).addValue("now", JdbcTime.write(now)));
    }
}
