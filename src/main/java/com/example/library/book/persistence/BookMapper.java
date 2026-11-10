package com.example.library.book.persistence;

import com.example.library.book.model.Book;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface BookMapper {
    Book selectById(@Param("id") long id);

    List<Book> search(@Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);

    int reserveOne(@Param("id") long id, @Param("now") Instant now);

    int releaseOne(@Param("id") long id, @Param("now") Instant now);
}
