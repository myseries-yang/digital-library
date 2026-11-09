package com.example.library;

import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

final class DatabaseAssertions {
    private DatabaseAssertions() {}

    static void consistent(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM library_account a
                WHERE a.active_loan_count <> (SELECT COUNT(*) FROM current_loan c WHERE c.account_id=a.id)
                """, Integer.class)).as("账号计数与当前关系一致").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM book b
                WHERE b.total_licenses-b.available_licenses <> (SELECT COUNT(*) FROM current_loan c WHERE c.book_id=b.id)
                """, Integer.class)).as("图书名额与当前关系一致").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM loan l LEFT JOIN current_loan c ON c.loan_id=l.id
                WHERE (l.returned_at IS NULL AND c.id IS NULL) OR (l.returned_at IS NOT NULL AND c.id IS NOT NULL)
                """, Integer.class)).as("当前关系与归还状态一致").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM current_loan c
                LEFT JOIN loan l ON l.id=c.loan_id
                LEFT JOIN library_account a ON a.id=c.account_id
                LEFT JOIN book b ON b.id=c.book_id
                WHERE l.id IS NULL OR a.id IS NULL OR b.id IS NULL OR l.account_id<>c.account_id OR l.book_id<>c.book_id
                """, Integer.class)).as("程序维护的逻辑关联存在且匹配").isZero();
    }

    static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM current_loan");
        jdbc.update("DELETE FROM loan");
        jdbc.update("UPDATE library_account SET active_loan_count=0");
        jdbc.update("UPDATE book SET available_licenses=total_licenses");
        jdbc.update("UPDATE borrowing_policy SET max_active_loans=10, loan_days=15, version=1 WHERE id=1");
    }
}
