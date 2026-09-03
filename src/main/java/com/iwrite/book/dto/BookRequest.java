package com.iwrite.book.dto;

import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Shared Book settings supplied at creation (#206).
 *
 * <p>The Personal Book Writing Goal is deliberately absent: a daily word target and planned writing
 * days belong to one User inside the Book, not to the Book, and they are set through
 * {@code /api/books/{bookId}/writing-goal}. {@code targetWordCount} stays here because a Book-wide
 * target is a shared setting the Book Owner controls.
 */
public record BookRequest(
        @NotBlank String title,
        String subtitle,
        String description,
        BookStatus status,
        @Positive Integer targetWordCount
) {
}
