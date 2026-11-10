package com.example.library.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.time.LocalDateTime;

/** 测试专用 SQL：独立校验状态、准备数据及制造数据库竞争。 */
@Mapper
public interface DatabaseTestMapper {
    int inconsistentAccountCounts();
    int inconsistentBookLicenses();
    int inconsistentLoanStates();
    int inconsistentRelationships();

    int deleteCurrentLoans();
    int deleteLoans();
    int resetAccounts();
    int resetBooks();
    int resetPolicy();

    int countBooks();
    int countAccounts();
    int countLoans();
    int countCurrentLoansForBook(@Param("bookId") long bookId);
    int accountLoanCount(@Param("id") long id);
    int bookAvailableLicenses(@Param("id") long id);

    int setLoanDates(@Param("id") long id, @Param("borrowedAt") Instant borrowedAt, @Param("dueAt") Instant dueAt);
    LocalDateTime borrowedLocalDateTime(@Param("id") long id);
    int updateBookText(@Param("id") long id, @Param("title") String title, @Param("author") String author);

    int h2ForeignKeyCount();
    int mysqlForeignKeyCount();
    String h2LoanReferenceComment();
    String mysqlLoanReferenceComment();
    int changePolicyForRestart();
    int insertExtraBook();
    long maxBookId();
    int policyLimit();

    Long lockAccountRow(@Param("id") long id);
}
