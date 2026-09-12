package com.iwrite.writingprogress.ledger.repository;

import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookWordCountEventRepository extends JpaRepository<BookWordCountEvent, UUID> {

    boolean existsByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);

    Optional<BookWordCountEvent> findByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);

    long countByBookId(UUID bookId);

    @Query("""
            select event
            from BookWordCountEvent event
            where event.book.id = :bookId
              and event.progressDate between :startDate and :endDate
              and (event.productiveWordDelta <> 0 or event.manuscriptWordDelta <> 0)
            order by event.createdAt asc, event.id asc
            """)
    List<BookWordCountEvent> findBookContributionEventsBetween(
            @Param("bookId") UUID bookId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            select event
            from BookWordCountEvent event
            where event.book.id = :bookId
              and event.actorUser.id = :actorUserId
              and event.progressDate between :startDate and :endDate
              and (event.productiveWordDelta <> 0 or event.manuscriptWordDelta <> 0)
            order by event.createdAt asc, event.id asc
            """)
    List<BookWordCountEvent> findBookContributorEventsBetween(
            @Param("bookId") UUID bookId,
            @Param("actorUserId") UUID actorUserId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Modifying
    @Query(value = """
            insert into book_word_count_events (
                id,
                book_id,
                scene_id,
                actor_user_id,
                original_scene_id,
                scene_title_snapshot,
                progress_date,
                original_chapter_id,
                chapter_title_snapshot,
                event_type,
                productive_word_delta,
                manuscript_word_delta,
                operation_id,
                idempotency_key,
                content_revision_before,
                content_revision_after,
                request_fingerprint,
                created_at
            )
            values (
                :id,
                :bookId,
                :sceneId,
                :actorUserId,
                :originalSceneId,
                :sceneTitleSnapshot,
                :progressDate,
                :originalChapterId,
                :chapterTitleSnapshot,
                :eventType,
                :productiveWordDelta,
                :manuscriptWordDelta,
                :operationId,
                :idempotencyKey,
                :contentRevisionBefore,
                :contentRevisionAfter,
                :requestFingerprint,
                :createdAt
            )
            on conflict (book_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("bookId") UUID bookId,
            @Param("sceneId") UUID sceneId,
            @Param("actorUserId") UUID actorUserId,
            @Param("originalSceneId") UUID originalSceneId,
            @Param("sceneTitleSnapshot") String sceneTitleSnapshot,
            @Param("progressDate") LocalDate progressDate,
            @Param("originalChapterId") UUID originalChapterId,
            @Param("chapterTitleSnapshot") String chapterTitleSnapshot,
            @Param("eventType") String eventType,
            @Param("productiveWordDelta") int productiveWordDelta,
            @Param("manuscriptWordDelta") int manuscriptWordDelta,
            @Param("operationId") UUID operationId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("contentRevisionBefore") Long contentRevisionBefore,
            @Param("contentRevisionAfter") Long contentRevisionAfter,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("createdAt") OffsetDateTime createdAt
    );
}
