package com.example.library;

import com.example.library.support.DatabaseTestMapper;
import static org.assertj.core.api.Assertions.assertThat;

final class DatabaseAssertions {
    private DatabaseAssertions() {
    }

    static void consistent(DatabaseTestMapper database) {
        assertThat(database.inconsistentAccountCounts()).as("账号计数与当前关系一致").isZero();
        assertThat(database.inconsistentBookLicenses()).as("图书名额与当前关系一致").isZero();
        assertThat(database.inconsistentLoanStates()).as("当前关系与归还状态一致").isZero();
        assertThat(database.inconsistentRelationships()).as("程序维护的逻辑关联存在且匹配").isZero();
    }

    static void reset(DatabaseTestMapper database) {
        database.deleteCurrentLoans();
        database.deleteLoans();
        database.resetAccounts();
        database.resetBooks();
        database.resetPolicy();
    }
}
