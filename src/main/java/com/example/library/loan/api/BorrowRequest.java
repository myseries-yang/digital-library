package com.example.library.loan.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class BorrowRequest {
    @NotNull
    @Positive
    private Long bookId;

    public BorrowRequest() {
    }

    public BorrowRequest(Long bookId) {
        this.bookId = bookId;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

}
