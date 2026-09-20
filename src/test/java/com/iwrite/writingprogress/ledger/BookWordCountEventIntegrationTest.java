package com.iwrite.writingprogress.ledger;

import com.iwrite.book.entity.Book;
import com.iwrite.scene.entity.Scene;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookWordCountEventIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void v18SchemaAppliesAndDailyProgressDefaultsAdjustmentToZero() {
        var book = createBook("v18 daily progress");
        UUID progressId = UUID.randomUUID();
        entityManager.flush();

        jdbcTemplate.update(
                """
                        insert into book_daily_writing_progress (
                            id,
                            user_id,
                            book_id,
                            progress_date,
                            daily_target_word_count,
                            starting_manuscript_word_count,
                            ending_manuscript_word_count,
                            productive_word_count_change,
                            created_at,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                        """,
                progressId,
                DEFAULT_USER_ID,
                book.id(),
                Date.valueOf(LocalDate.of(2026, 6, 1)),
                500,
                12,
                20,
                8
        );

        Integer ledgerTableCount = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.tables
                        where table_schema = 'public'
                            and table_name = 'book_word_count_events'
                        """,
                Integer.class
        );

        var row = jdbcTemplate.queryForMap(
                """
                        select
                            starting_manuscript_word_count,
                            ending_manuscript_word_count,
                            productive_word_count_change,
                            manuscript_adjustment_word_count
                        from book_daily_writing_progress
                        where id = ?
                        """,
                progressId
        );

        assertThat(ledgerTableCount).isEqualTo(1);
        assertThat(row.get("starting_manuscript_word_count")).isEqualTo(12);
        assertThat(row.get("ending_manuscript_word_count")).isEqualTo(20);
        assertThat(row.get("productive_word_count_change")).isEqualTo(8);
        assertThat(row.get("manuscript_adjustment_word_count")).isEqualTo(0);
    }

    @Test
    void ledgerRowPersistsWithOperationIdAndIdempotencyKey() {
        var world = createStoryWorld("ledger row");
        Book book = entityManager.find(Book.class, world.book().id());
        Scene scene = entityManager.find(Scene.class, world.scene().id());
        UUID operationId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        String requestFingerprint = "0".repeat(64);

        BookWordCountEvent event = new BookWordCountEvent();
        event.setBook(book);
        event.setScene(scene);
        event.setActorUser(entityManager.getReference(User.class, DEFAULT_USER_ID));
        event.setOriginalSceneId(scene.getId());
        event.setSceneTitleSnapshot(scene.getTitle());
        event.setProgressDate(LocalDate.of(2026, 6, 1));
        event.setOriginalChapterId(world.chapter().id());
        event.setChapterTitleSnapshot(world.chapter().title());
        event.setEventType(BookWordCountEventType.CONTENT_SAVE);
        event.setProductiveWordDelta(7);
        event.setManuscriptWordDelta(7);
        event.setOperationId(operationId);
        event.setIdempotencyKey(idempotencyKey);
        event.setContentRevisionBefore(3L);
        event.setContentRevisionAfter(4L);
        event.setRequestFingerprint(requestFingerprint);

        BookWordCountEvent saved = eventRepository.saveAndFlush(event);
        entityManager.clear();

        BookWordCountEvent loaded = eventRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getBook().getId()).isEqualTo(book.getId());
        assertThat(loaded.getScene().getId()).isEqualTo(scene.getId());
        assertThat(loaded.getActorUser().getId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(loaded.getOriginalSceneId()).isEqualTo(scene.getId());
        assertThat(loaded.getSceneTitleSnapshot()).isEqualTo(scene.getTitle());
        assertThat(loaded.getProgressDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(loaded.getOriginalChapterId()).isEqualTo(world.chapter().id());
        assertThat(loaded.getChapterTitleSnapshot()).isEqualTo(world.chapter().title());
        assertThat(loaded.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
        assertThat(loaded.getProductiveWordDelta()).isEqualTo(7);
        assertThat(loaded.getManuscriptWordDelta()).isEqualTo(7);
        assertThat(loaded.getOperationId()).isEqualTo(operationId);
        assertThat(loaded.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(loaded.getContentRevisionBefore()).isEqualTo(3L);
        assertThat(loaded.getContentRevisionAfter()).isEqualTo(4L);
        assertThat(loaded.getRequestFingerprint()).isEqualTo(requestFingerprint);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateBookAndIdempotencyKeyIsRejected() {
        var world = createStoryWorld("ledger duplicate");
        Book book = entityManager.find(Book.class, world.book().id());
        UUID idempotencyKey = UUID.randomUUID();

        eventRepository.saveAndFlush(event(book, idempotencyKey, 1));

        assertThatThrownBy(() -> eventRepository.saveAndFlush(event(book, idempotencyKey, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private BookWordCountEvent event(Book book, UUID idempotencyKey, int delta) {
        BookWordCountEvent event = new BookWordCountEvent();
        event.setBook(book);
        event.setActorUser(entityManager.getReference(User.class, DEFAULT_USER_ID));
        event.setOriginalSceneId(UUID.randomUUID());
        event.setProgressDate(LocalDate.of(2026, 6, 1));
        event.setEventType(BookWordCountEventType.CONTENT_SAVE);
        event.setProductiveWordDelta(delta);
        event.setManuscriptWordDelta(delta);
        event.setOperationId(UUID.randomUUID());
        event.setIdempotencyKey(idempotencyKey);
        return event;
    }
}
