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

    // Only explicit FALSE proves a ledger event was not included in a daily rollup.
    @Query(value = """
            select event.actor_user_id as "actorUserId",
                   event.progress_date as "progressDate",
                   event.original_scene_id as "originalSceneId",
                   (array_agg(event.scene_title_snapshot order by event.created_at desc, event.id desc))[1]
                       as "sceneTitleSnapshot",
                   event.original_chapter_id as "originalChapterId",
                   (array_agg(event.chapter_title_snapshot order by event.created_at desc, event.id desc))[1]
                       as "chapterTitleSnapshot",
                   sum(event.productive_word_delta) as "productiveWordDelta",
                   sum(event.manuscript_word_delta) as "manuscriptWordDelta",
                   sum(case when event.daily_rollup_applied = false
                            then event.productive_word_delta else 0 end)
                       as "unrepresentedProductiveWordDelta",
                   sum(case when event.daily_rollup_applied = false
                            then event.manuscript_word_delta else 0 end)
                       as "unrepresentedManuscriptWordDelta"
            from book_word_count_events event
            where event.book_id = :bookId
              and event.progress_date between :startDate and :endDate
              and (event.productive_word_delta <> 0 or event.manuscript_word_delta <> 0)
            group by event.actor_user_id, event.progress_date, event.original_scene_id, event.original_chapter_id
            order by max(event.created_at) asc,
                     (array_agg(event.id order by event.created_at desc, event.id desc))[1] asc
            """, nativeQuery = true)
    List<BookContributionEventAggregate> findBookContributionEventAggregatesBetween(
            @Param("bookId") UUID bookId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    // Only explicit FALSE proves a ledger event was not included in a daily rollup.
    @Query(value = """
            select event.actor_user_id as "actorUserId",
                   event.progress_date as "progressDate",
                   event.original_scene_id as "originalSceneId",
                   (array_agg(event.scene_title_snapshot order by event.created_at desc, event.id desc))[1]
                       as "sceneTitleSnapshot",
                   event.original_chapter_id as "originalChapterId",
                   (array_agg(event.chapter_title_snapshot order by event.created_at desc, event.id desc))[1]
                       as "chapterTitleSnapshot",
                   sum(event.productive_word_delta) as "productiveWordDelta",
                   sum(event.manuscript_word_delta) as "manuscriptWordDelta",
                   sum(case when event.daily_rollup_applied = false
                            then event.productive_word_delta else 0 end)
                       as "unrepresentedProductiveWordDelta",
                   sum(case when event.daily_rollup_applied = false
                            then event.manuscript_word_delta else 0 end)
                       as "unrepresentedManuscriptWordDelta"
            from book_word_count_events event
            where event.book_id = :bookId
              and event.actor_user_id = :actorUserId
              and event.progress_date between :startDate and :endDate
              and (event.productive_word_delta <> 0 or event.manuscript_word_delta <> 0)
            group by event.actor_user_id, event.progress_date, event.original_scene_id, event.original_chapter_id
            order by max(event.created_at) asc,
                     (array_agg(event.id order by event.created_at desc, event.id desc))[1] asc
            """, nativeQuery = true)
    List<BookContributionEventAggregate> findBookContributorEventAggregatesBetween(
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
                daily_rollup_applied,
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
                :dailyRollupApplied,
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
            @Param("dailyRollupApplied") boolean dailyRollupApplied,
            @Param("createdAt") OffsetDateTime createdAt
    );
}
