package com.iwrite.writingprogress.entity;

import com.iwrite.book.entity.Book;
import com.iwrite.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The optional daily word target one User chose inside one Book (#206).
 *
 * <p>It is User + Book scoped on purpose: the target is not a property of the Book and is never
 * shared with the other collaborators. A {@code null} {@code dailyTargetWordCount} — like the absence
 * of the row itself — means no target was chosen, which is not zero progress, failure or a lesser
 * contribution.
 *
 * <p>The planned writing days of the same goal live in {@link BookWritingSchedule}, which already
 * versions them by period so past progress keeps the routine that was actually in effect. They are
 * still the same goal, so {@code revision} versions both halves together.
 */
@Entity
@Table(name = "book_personal_writing_goals")
public class PersonalBookWritingGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    private Integer dailyTargetWordCount;

    /**
     * Versions the whole goal, both halves together, so a save can declare the state it was made
     * against and a stale one is refused instead of silently replacing a newer choice.
     */
    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public Integer getDailyTargetWordCount() {
        return dailyTargetWordCount;
    }

    public void setDailyTargetWordCount(Integer dailyTargetWordCount) {
        this.dailyTargetWordCount = dailyTargetWordCount;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
