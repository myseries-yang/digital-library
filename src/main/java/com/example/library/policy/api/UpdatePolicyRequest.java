package com.example.library.policy.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class UpdatePolicyRequest {
    @NotNull
    @Positive
    private Integer maxActiveLoans;
    @NotNull
    @Positive
    private Integer loanDays;
    @NotNull
    @Positive
    private Long expectedVersion;

    public UpdatePolicyRequest() {
    }

    public UpdatePolicyRequest(Integer maxActiveLoans, Integer loanDays, Long expectedVersion) {
        this.maxActiveLoans = maxActiveLoans;
        this.loanDays = loanDays;
        this.expectedVersion = expectedVersion;
    }

    public Integer getMaxActiveLoans() {
        return maxActiveLoans;
    }

    public void setMaxActiveLoans(Integer maxActiveLoans) {
        this.maxActiveLoans = maxActiveLoans;
    }

    public Integer getLoanDays() {
        return loanDays;
    }

    public void setLoanDays(Integer loanDays) {
        this.loanDays = loanDays;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

}
