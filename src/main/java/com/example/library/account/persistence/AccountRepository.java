package com.example.library.account.persistence;

import com.example.library.account.model.LibraryAccount;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public class AccountRepository {
    private final AccountMapper mapper;

    public AccountRepository(AccountMapper mapper) {
        this.mapper = mapper;
    }

    public List<LibraryAccount> all() {
        return mapper.selectAll();
    }

    public LibraryAccount require(long id) {
        LibraryAccount account = mapper.selectById(id);
        if (account == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    public int reserveOne(long id, int maxActiveLoans, Instant now) {
        return mapper.reserveOne(id, maxActiveLoans, now);
    }

    public int releaseOne(long id, Instant now) {
        return mapper.releaseOne(id, now);
    }
}
