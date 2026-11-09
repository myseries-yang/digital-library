package com.example.library.book.model;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;

/** Book / 图书信息及可借授权名额。 */
public record Book(long id, String title, String author, String description,
                   int totalLicenses, int availableLicenses) {
    public void requireAvailableLicense() {
        if (availableLicenses <= 0) {
            throw new BusinessException(ErrorCode.BOOK_UNAVAILABLE);
        }
    }
}
