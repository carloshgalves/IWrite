package com.iwrite.book.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.common.exception.BadRequestException;
import jakarta.validation.constraints.Positive;

/**
 * Partial update of the shared Book settings the Book Owner controls (#206).
 *
 * <p>Only shared data lives here: metadata, {@link BookStatus} and the optional Book-wide
 * {@code targetWordCount}. The Personal Book Writing Goal is not part of this contract, so this
 * request can never change one collaborator's daily target — let alone everybody's.
 *
 * <p>Unknown fields are rejected instead of being ignored. A request that still carries
 * {@code dailyTargetWordCount} or {@code plannedWritingDays} fails loudly rather than appearing to
 * save a personal goal it no longer owns, and no hidden field can be mass assigned here.
 */
public class BookUpdateRequest {

    private String title;
    private String subtitle;
    private String description;
    private BookStatus status;

    @Positive
    private Integer targetWordCount;
    private boolean targetWordCountPresent;

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public String description() {
        return description;
    }

    public BookStatus status() {
        return status;
    }

    public Integer targetWordCount() {
        return targetWordCount;
    }

    public boolean isTargetWordCountPresent() {
        return targetWordCountPresent;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStatus(BookStatus status) {
        this.status = status;
    }

    @JsonSetter("targetWordCount")
    public void setTargetWordCount(Integer targetWordCount) {
        this.targetWordCount = targetWordCount;
        this.targetWordCountPresent = true;
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object ignoredValue) {
        throw new BadRequestException("Unknown book setting: " + name);
    }
}
