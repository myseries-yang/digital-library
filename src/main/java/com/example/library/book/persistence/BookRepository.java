package com.example.library.book.persistence;

import com.example.library.book.model.Book;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Repository
public class BookRepository {
    private final BookMapper mapper;

    public BookRepository(BookMapper mapper) {
        this.mapper = mapper;
    }

    public Book require(long id) {
        Book book = mapper.selectById(id);
        if (book == null) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book;
    }

    public SlicePage<Book> search(String keyword, PageQuery page) {
        String value = trimKeyword(keyword);
        if (value.length() > 255) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "搜索关键词不能超过 255 个字符");
        }
        // 关键词按字面匹配，输入中的 %、_ 和 ! 都需要转义。
        String pattern = "%" + value.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Book> rows = mapper.search(pattern, page.getFetchSize(), page.getOffset());
        return SlicePage.from(rows, page);
    }

    private String trimKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        // 与原先 strip 的 Unicode 空白处理一致，写法兼容 Java 8。
        int start = 0;
        int end = keyword.length();
        while (start < end && Character.isWhitespace(keyword.codePointAt(start))) {
            start += Character.charCount(keyword.codePointAt(start));
        }
        while (start < end && Character.isWhitespace(keyword.codePointBefore(end))) {
            end -= Character.charCount(keyword.codePointBefore(end));
        }
        return keyword.substring(start, end);
    }

    public int reserveOne(long id, Instant now) {
        return mapper.reserveOne(id, now);
    }

    public int releaseOne(long id, Instant now) {
        return mapper.releaseOne(id, now);
    }
}
