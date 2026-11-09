package com.example.library.infrastructure.web;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

public record PageQuery(int page, int size) {
    public PageQuery {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "page 必须不小于零，size 必须在 1 到 100 之间");
        }
    }
    public long offset() { return (long) page * size; }
    public int fetchSize() { return size + 1; }
}
