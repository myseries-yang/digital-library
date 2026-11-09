package com.example.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MigrationTest {
    @TempDir Path directory;

    @Test
    void fileDatabaseSeedsOnceKeepsChangesAndContinuesGeneratingIds() {
        String url = "jdbc:h2:file:" + directory.resolve("library") + ";DB_CLOSE_ON_EXIT=FALSE";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        var migration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").load();
        migration.migrate();
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM library_account", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT active_loan_count FROM library_account WHERE id=2", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT available_licenses FROM book WHERE id=9", Integer.class)).isZero();
        DatabaseAssertions.consistent(jdbc);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_type='FOREIGN KEY' AND table_schema='PUBLIC'
                """, Integer.class)).as("不建立数据库外键约束").isZero();
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT remarks FROM information_schema.columns
                WHERE table_schema='PUBLIC' AND table_name='CURRENT_LOAN' AND column_name='LOAN_ID'
                """);
        assertThat(column.get("REMARKS").toString()).contains("逻辑关联");
        jdbc.update("UPDATE borrowing_policy SET max_active_loans=7, version=2 WHERE id=1");
        jdbc.update("INSERT INTO book(title,author,total_licenses,available_licenses) VALUES('新书','编辑组',1,1)");
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM book", Long.class)).isGreaterThan(15);
        var reopened = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(reopened).locations("classpath:db/migration/h2").load().migrate();
        assertThat(new JdbcTemplate(reopened).queryForObject(
                "SELECT max_active_loans FROM borrowing_policy WHERE id=1", Integer.class)).isEqualTo(7);
        assertThat(new JdbcTemplate(reopened).queryForObject("SELECT COUNT(*) FROM book", Integer.class)).isEqualTo(16);
        new JdbcTemplate(reopened).execute("SHUTDOWN");
    }
}
