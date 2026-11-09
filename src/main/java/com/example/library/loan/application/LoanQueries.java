package com.example.library.loan.application;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.persistence.JdbcTime;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import com.example.library.loan.model.LoanScope;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class LoanQueries {
    private static final String SELECT = """
            SELECT l.id, l.account_id, a.account_no, a.account_name, l.book_id, b.title,
                   l.borrowed_at, l.due_at, l.returned_at
            FROM loan l JOIN library_account a ON a.id=l.account_id JOIN book b ON b.id=l.book_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public LoanQueries(NamedParameterJdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public LoanView require(long id) {
        return jdbc.query(SELECT + " WHERE l.id=:id", Map.of("id", id), mapper(clock.instant()))
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.LOAN_NOT_FOUND));
    }

    public SlicePage<LoanView> search(Long accountId, Long bookId, LoanScope scope, PageQuery page) {
        Instant now = clock.instant();
        var parameters = new MapSqlParameterSource("now", JdbcTime.write(now))
                .addValue("limit", page.fetchSize()).addValue("offset", page.offset());
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        if (accountId != null) { sql.append(" AND l.account_id=:accountId"); parameters.addValue("accountId", accountId); }
        if (bookId != null) { sql.append(" AND l.book_id=:bookId"); parameters.addValue("bookId", bookId); }
        sql.append(switch (scope) {
            case ALL -> "";
            case CURRENT -> " AND l.returned_at IS NULL";
            case OVERDUE -> " AND l.returned_at IS NULL AND l.due_at <= :now";
            case RETURNED -> " AND l.returned_at IS NOT NULL";
        });
        sql.append(" ORDER BY l.id DESC LIMIT :limit OFFSET :offset");
        return SlicePage.from(jdbc.query(sql.toString(), parameters, mapper(now)), page);
    }

    private RowMapper<LoanView> mapper(Instant now) {
        return (rs, row) -> {
            Instant dueAt = JdbcTime.read(rs, "due_at");
            Instant returnedAt = JdbcTime.read(rs, "returned_at");
            return new LoanView(rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_no"),
                    rs.getString("account_name"), rs.getLong("book_id"), rs.getString("title"),
                    JdbcTime.read(rs, "borrowed_at"), dueAt, returnedAt,
                    returnedAt == null && !now.isBefore(dueAt));
        };
    }
}
