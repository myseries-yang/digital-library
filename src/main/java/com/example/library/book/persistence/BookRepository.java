package com.example.library.book.persistence;

import com.example.library.book.model.Book;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.persistence.JdbcTime;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Repository
public class BookRepository {
    private static final String COLUMNS = "id, title, author, description, total_licenses, available_licenses";
    private static final RowMapper<Book> MAPPER = (rs, row) -> new Book(rs.getLong("id"),
            rs.getString("title"), rs.getString("author"), rs.getString("description"),
            rs.getInt("total_licenses"), rs.getInt("available_licenses"));
    private final NamedParameterJdbcTemplate jdbc;

    public BookRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Book require(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM book WHERE id=:id", Map.of("id", id), MAPPER)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));
    }

    public SlicePage<Book> search(String keyword, PageQuery page) {
        String value = keyword == null ? "" : keyword.strip();
        if (value.length() > 255) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "搜索关键词不能超过 255 个字符");
        }
        // A keyword is a literal substring, not a SQL wildcard expression.
        String pattern = "%" + value.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var parameters = new MapSqlParameterSource("keyword", pattern)
                .addValue("limit", page.fetchSize()).addValue("offset", page.offset());
        var rows = jdbc.query("SELECT " + COLUMNS + """
                 FROM book
                 WHERE LOWER(title) LIKE :keyword ESCAPE '!' OR LOWER(author) LIKE :keyword ESCAPE '!'
                 ORDER BY id LIMIT :limit OFFSET :offset
                """, parameters, MAPPER);
        return SlicePage.from(rows, page);
    }

    public int reserveOne(long id, Instant now) {
        return jdbc.update("""
                UPDATE book SET available_licenses=available_licenses-1, updated_at=:now
                WHERE id=:id AND available_licenses > 0
                """, new MapSqlParameterSource("id", id).addValue("now", JdbcTime.write(now)));
    }

    public int releaseOne(long id, Instant now) {
        return jdbc.update("""
                UPDATE book SET available_licenses=available_licenses+1, updated_at=:now
                WHERE id=:id AND available_licenses < total_licenses
                """, new MapSqlParameterSource("id", id).addValue("now", JdbcTime.write(now)));
    }
}
