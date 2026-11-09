package com.example.library.infrastructure.web;

import java.util.List;

public record SlicePage<T>(List<T> items, int page, int size, boolean hasNext) {
    public SlicePage { items = List.copyOf(items); }

    public static <T> SlicePage<T> from(List<T> rows, PageQuery query) {
        return new SlicePage<>(rows.subList(0, Math.min(rows.size(), query.size())),
                query.page(), query.size(), rows.size() > query.size());
    }
}
