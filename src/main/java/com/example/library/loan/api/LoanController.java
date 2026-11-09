package com.example.library.loan.api;

import com.example.library.account.persistence.AccountRepository;
import com.example.library.infrastructure.web.CurrentAccount;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import com.example.library.loan.application.LoanQueries;
import com.example.library.loan.application.LoanService;
import com.example.library.loan.application.LoanView;
import com.example.library.loan.model.LoanScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Loans / 借阅")
public class LoanController {
    private final LoanService service;
    private final LoanQueries queries;
    private final AccountRepository accounts;

    public LoanController(LoanService service, LoanQueries queries, AccountRepository accounts) {
        this.service = service; this.queries = queries; this.accounts = accounts;
    }

    public record BorrowRequest(@NotNull @Positive Long bookId) {}

    @PostMapping("/api/loans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "借阅图书", description = "每个账号同书最多一笔未归还记录；失败不会占用数量。")
    public LoanView borrow(
            @Parameter(description = "当前预置图书馆账号 ID", required = true)
            @RequestHeader(name = CurrentAccount.HEADER, required = false) String account,
            @RequestBody @Valid BorrowRequest request) {
        return service.borrow(CurrentAccount.require(account), request.bookId());
    }

    @PostMapping("/api/loans/{loanId}/return")
    @Operation(summary = "归还指定借阅", description = "按 loanId 幂等，重复请求保留原归还时间且不会再次释放数量。")
    public LoanView returnLoan(
            @Parameter(description = "当前预置图书馆账号 ID", required = true)
            @RequestHeader(name = CurrentAccount.HEADER, required = false) String account,
            @PathVariable @Positive long loanId) {
        return service.returnLoan(CurrentAccount.require(account), loanId);
    }

    @GetMapping("/api/loans/current")
    @Operation(summary = "查看当前账号的未归还借阅", description = "包含逾期记录，按借阅 ID 降序。")
    public SlicePage<LoanView> current(
            @Parameter(description = "当前预置图书馆账号 ID", required = true)
            @RequestHeader(name = CurrentAccount.HEADER, required = false) String account,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        long accountId = CurrentAccount.require(account);
        accounts.require(accountId);
        return queries.search(accountId, null, LoanScope.CURRENT, new PageQuery(page, size));
    }

    @GetMapping("/api/admin/loans")
    @Operation(summary = "按账号、图书或状态查询借阅", description = "CURRENT 包含逾期；本次管理入口不包含真实权限系统。")
    public SlicePage<LoanView> adminSearch(@RequestParam(required = false) @Positive Long accountId,
                                          @RequestParam(required = false) @Positive Long bookId,
                                          @RequestParam(defaultValue = "ALL") LoanScope scope,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return queries.search(accountId, bookId, scope, new PageQuery(page, size));
    }
}
