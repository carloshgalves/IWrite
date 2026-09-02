package com.iwrite.book.dto;

import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A collaboration relationship as seen by the Book Owner. The role is the explicit, revocable
 * authority of the relationship; it is not a Persona, a Workspace Role or an authorship credit.
 */
public record BookCollaboratorResponse(
        UUID userId,
        String displayName,
        BookRole role,
        OffsetDateTime createdAt
) {

    public static BookCollaboratorResponse fromEntity(BookCollaborator collaborator) {
        return new BookCollaboratorResponse(
                collaborator.getUser().getId(),
                collaborator.getUser().getDisplayName(),
                collaborator.getRole(),
                collaborator.getCreatedAt()
        );
    }
}
