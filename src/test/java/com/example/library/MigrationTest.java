package com.example.library;

import com.example.library.support.DatabaseTestMapper;
import com.example.library.support.MyBatisTestSupport;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationTest {
    @TempDir Path directory;

    @Test
    void fileDatabaseSeedsOnceKeepsChangesAndContinuesGeneratingIds() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("library") + ";DB_CLOSE_ON_EXIT=FALSE";
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").load().migrate();
        try (SqlSession session = MyBatisTestSupport.sessionFactory(dataSource).openSession(true)) {
            DatabaseTestMapper database = session.getMapper(DatabaseTestMapper.class);
            assertThat(database.countBooks()).isEqualTo(15);
            assertThat(database.countAccounts()).isEqualTo(4);
            assertThat(database.accountLoanCount(2)).isEqualTo(9);
            assertThat(database.bookAvailableLicenses(9)).isZero();
            DatabaseAssertions.consistent(database);
            assertThat(database.h2ForeignKeyCount()).as("不建立数据库外键约束").isZero();
            assertThat(database.h2LoanReferenceComment()).contains("逻辑关联");
            database.changePolicyForRestart();
            database.insertExtraBook();
            assertThat(database.maxBookId()).isGreaterThan(15);
        }
        UnpooledDataSource reopened = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        Flyway.configure().dataSource(reopened).locations("classpath:db/migration/h2").load().migrate();
        try (SqlSession session = MyBatisTestSupport.sessionFactory(reopened).openSession(true)) {
            DatabaseTestMapper database = session.getMapper(DatabaseTestMapper.class);
            assertThat(database.policyLimit()).isEqualTo(7);
            assertThat(database.countBooks()).isEqualTo(16);
        }
    }
}
