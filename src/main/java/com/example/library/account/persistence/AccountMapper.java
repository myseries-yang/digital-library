package com.example.library.account.persistence;

import com.example.library.account.model.LibraryAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface AccountMapper {
    List<LibraryAccount> selectAll();

    LibraryAccount selectById(@Param("id") long id);

    int reserveOne(@Param("id") long id, @Param("maxActiveLoans") int maxActiveLoans, @Param("now") Instant now);

    int releaseOne(@Param("id") long id, @Param("now") Instant now);
}
