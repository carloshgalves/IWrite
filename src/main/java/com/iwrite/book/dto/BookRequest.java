package com.iwrite.book.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.common.exception.BadRequestException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Set;

/**
 * Shared Book settings supplied at creation (#206).
 *
 * <p>The Personal Book Writing Goal is deliberately absent: a daily word target and planned writing
 * days belong to one User inside the Book, not to the Book, and they are set through
 * {@code /api/books/{bookId}/writing-goal}. {@code targetWordCount} stays here because a Book-wide
 * target is a shared setting the Book Owner controls.
 *
 * <p>The two fields this contract just gave up are refused rather than dropped, exactly as
 * {@link BookUpdateRequest} refuses them. Ignoring them would be the worse failure: the caller would be
 * answered {@code 201} for a Book created with the daily target it asked for, while no goal was ever
 * stored and the target it believes it set does not exist.
 *
 * <p>Any other unknown field keeps being ignored, which is the established behavior of this endpoint
 * and is proven by the tenant/identity isolation tests: a body that carries {@code tenantId},
 * {@code userId} or {@code role} must be answered by creating the Book under the authenticated
 * identity, never by letting the client believe those fields participated. Refusing them here would
 * also diverge from the header and query-parameter paths of the same attempt, which remain ignored.
 */
public record BookRequest(
        @NotBlank String title,
        String subtitle,
        String description,
        BookStatus status,
        @Positive Integer targetWordCount
) {

    private static final Set<String> PERSONAL_WRITING_GOAL_FIELDS =
            Set.of("dailyTargetWordCount", "plannedWritingDays");

    @JsonAnySetter
    void rejectPersonalWritingGoalField(String name, Object ignoredValue) {
        if (PERSONAL_WRITING_GOAL_FIELDS.contains(name)) {
            throw new BadRequestException("Unknown book setting: " + name);
        }
    }
}
