package com.example.library.infrastructure.web;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

public final class CurrentAccount {
    public static final String HEADER = "X-Library-Account-Id";
    private CurrentAccount() {}

    public static long require(String header) {
        try {
            long id = Long.parseLong(header == null ? "" : header);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "请通过 " + HEADER + " 选择一个有效的图书馆账号");
        }
    }
}
