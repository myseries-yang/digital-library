package com.example.library.infrastructure.web;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

/** 从零开始的分页参数，构造时完成边界校验。 */
public class PageQuery {
    private final int page;
    private final int size;

    public PageQuery(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "page 必须不小于零，size 必须在 1 到 100 之间");
        }
        this.page = page;
        this.size = size;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getOffset() {
        return (long) page * size;
    }

    public int getFetchSize() {
        return size + 1;
    }
}
