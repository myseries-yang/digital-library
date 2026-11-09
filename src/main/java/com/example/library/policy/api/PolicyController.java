package com.example.library.policy.api;

import com.example.library.policy.application.PolicyService;
import com.example.library.policy.model.BorrowingPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Borrowing Policy / 借阅规则")
public class PolicyController {
    private final PolicyService service;
    public PolicyController(PolicyService service) { this.service = service; }

    public record UpdatePolicyRequest(@NotNull @Positive Integer maxActiveLoans,
                                      @NotNull @Positive Integer loanDays,
                                      @NotNull @Positive Long expectedVersion) {}

    @GetMapping("/api/borrowing-policy")
    @Operation(summary = "读取全局借阅规则")
    public BorrowingPolicy current() { return service.current(); }

    @PutMapping("/api/admin/borrowing-policy")
    @Operation(summary = "修改全局规则", description = "提交读取时的版本；不追溯已有借阅和已读取旧规则的请求。")
    public BorrowingPolicy update(@RequestBody @Valid UpdatePolicyRequest request) {
        return service.update(request.maxActiveLoans(), request.loanDays(), request.expectedVersion());
    }
}
