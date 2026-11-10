package com.example.library.book.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

/** 图书信息及可借授权名额。 */
public class Book {
    private long id;
    private String title;
    private String author;
    private String description;
    private int totalLicenses;
    private int availableLicenses;

    public Book() {
    }

    public Book(long id, String title, String author, String description, int totalLicenses, int availableLicenses) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.description = description;
        this.totalLicenses = totalLicenses;
        this.availableLicenses = availableLicenses;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getTotalLicenses() {
        return totalLicenses;
    }

    public void setTotalLicenses(int totalLicenses) {
        this.totalLicenses = totalLicenses;
    }

    public int getAvailableLicenses() {
        return availableLicenses;
    }

    public void setAvailableLicenses(int availableLicenses) {
        this.availableLicenses = availableLicenses;
    }

    public void requireAvailableLicense() {
        if (availableLicenses <= 0) {
            throw new BusinessException(ErrorCode.BOOK_UNAVAILABLE);
        }
    }

}
