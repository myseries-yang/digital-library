package com.example.library.infrastructure.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 多取一条判断下一页，保留一处必要的分页泛型。 */
public class SlicePage<T> {
    private final List<T> items;
    private final int page;
    private final int size;
    private final boolean hasNext;

    public SlicePage(List<T> items, int page, int size, boolean hasNext) {
        this.items = Collections.unmodifiableList(new ArrayList<T>(items));
        this.page = page;
        this.size = size;
        this.hasNext = hasNext;
    }

    public List<T> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public static <T> SlicePage<T> from(List<T> rows, PageQuery query) {
        List<T> items = rows.subList(0, Math.min(rows.size(), query.getSize()));
        return new SlicePage<T>(items, query.getPage(), query.getSize(), rows.size() > query.getSize());
    }
}
