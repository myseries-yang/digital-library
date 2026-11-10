package com.example.library.book.api;

import com.example.library.book.model.Book;
import com.example.library.book.persistence.BookRepository;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/books")
@Tag(name = "Books / 图书")
public class BookController {
    private final BookRepository books;
    public BookController(BookRepository books) {
        this.books = books;
    }

    @GetMapping
    @Operation(summary = "分页浏览图书", description = "按书名或作者包含匹配；page 从零开始，size 为 1–100。")
    public SlicePage<Book> search(@RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return books.search(keyword, new PageQuery(page, size));
    }

    @GetMapping("/{bookId}")
    @Operation(summary = "查询图书详情")
    public Book detail(@PathVariable @Positive long bookId) {
        return books.require(bookId);
    }
}
