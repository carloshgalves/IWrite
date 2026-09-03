package com.iwrite.book.dto;

import com.iwrite.book.authorization.BookAccessContext;
import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.authorization.BookRelationship;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.entity.BookStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Book projection carrying the effective access derived by the backend.
 *
 * <p>The Personal Book Writing Goal is not projected here. A daily word target and planned writing
 * days belong to one User inside the Book, so they are read through
 * {@code /api/books/{bookId}/writing-goal} instead of being repeated as if they were shared Book data.
 *
 * <p>{@code relationship}, {@code role} and the capability sets replace the former binary
 * {@code OWNER | COLLABORATOR} contract. They exist so a consumer can present only the actions it may
 * attempt; every mutation is authorized again on the server, and a request that echoes them back is
 * not authority.
 *
 * @param capabilities capabilities authorized by Book scope alone
 * @param contextualCapabilities capabilities the User is eligible for that still depend on a
 *                               resource-scoped predicate evaluated by the operation itself
 */
public record BookResponse(
        UUID id,
        String title,
        String subtitle,
        String description,
        BookStatus status,
        Integer targetWordCount,
        BookRelationship relationship,
        BookRole role,
        List<BookCapability> capabilities,
        List<BookCapability> contextualCapabilities,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BookResponse fromEntity(Book book, BookAccessContext access) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getSubtitle(),
                book.getDescription(),
                book.getStatus(),
                book.getTargetWordCount(),
                access.relationship(),
                access.role(),
                sorted(access.capabilities()),
                sorted(access.contextualCapabilities()),
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }

    private static List<BookCapability> sorted(Set<BookCapability> capabilities) {
        return capabilities.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }
}
