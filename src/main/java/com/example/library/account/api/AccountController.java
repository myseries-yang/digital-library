package com.example.library.account.api;

import com.example.library.account.model.LibraryAccount;
import com.example.library.account.persistence.AccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@Tag(name = "Accounts / 图书馆账号")
public class AccountController {
    private final AccountRepository accounts;
    public AccountController(AccountRepository accounts) { this.accounts = accounts; }

    @GetMapping("/api/accounts")
    @Operation(summary = "列出预置图书馆账号", description = "演示身份选择，不代表真实认证。")
    public List<LibraryAccount> all() { return accounts.all(); }
}
