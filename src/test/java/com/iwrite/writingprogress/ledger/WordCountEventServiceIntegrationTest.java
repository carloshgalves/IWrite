package com.iwrite.writingprogress.ledger;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.service.BookCollaboratorService;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventConflictException;
import com.iwrite.writingprogress.ledger.service.WordCountEventRecordResult;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(WordCountEventServiceIntegrationTest.CurrentUserTestConfiguration.class)
class WordCountEventServiceIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 2);
    private static final Instant DEFAULT_INSTANT = Instant.parse("2026-06-02T12:00:00Z");

    @Autowired
    private WordCountEventService eventService;

    @Autowired
    private BookCollaboratorService collaboratorService;

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private MutableClock writingProgressClock;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUser() {
        currentUserProvider.reset();
        writingProgressClock.reset();
    }

    @Test
    void firstContentSaveEventCreatesLedgerRowAndDailyRollup() {
        var book = bookService.create(new BookRequest("ledger first event", null, null, null, null));
        setPersonalDailyTarget(book.id(), 400);
        var scene = createEmptyScene(book.id(), "First scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        WordCountEventRecordResult result = eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                5,
                5,
                idempotencyKey,
                15
        ));

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY).orElseThrow();
        assertThat(result).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(1);
        assertThat(eventRepository.findByBookIdAndIdempotencyKey(book.id(), idempotencyKey))
                .hasValueSatisfying(event -> assertThat(event.getActorUser().getId()).isEqualTo(DEFAULT_USER_ID));
        assertThat(progress.getUser().getId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(progress.getDailyTargetWordCount()).isEqualTo(400);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(15);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void zeroDeltaEventRecordsLedgerRowWithoutCreatingDailyRollup() {
        var book = createBook("ledger zero delta");
        var scene = createEmptyScene(book.id(), "Zero delta scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        WordCountEventRecordResult result = eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                0,
                0,
                idempotencyKey,
                10
        ));

        assertThat(result).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(eventRepository.findByBookIdAndIdempotencyKey(book.id(), idempotencyKey)).isPresent();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)).isEmpty();
    }

    @Test
    void laterContentSaveEventIncrementsProductiveAndEndingValues() {
        var book = createBook("ledger later event");
        var scene = createEmptyScene(book.id(), "Later scene");
        entityManager.flush();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 5, 5, UUID.randomUUID(), 15));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 3, 3, UUID.randomUUID(), 18));

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY).orElseThrow();
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(2);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(18);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(8);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void versionRestoreEventUpdatesAdjustmentAndEndingWithoutProductiveGrowth() {
        var book = createBook("ledger restore event");
        var scene = createEmptyScene(book.id(), "Restore scene");
        entityManager.flush();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 2, 2, UUID.randomUUID(), 12));
        eventService.record(command(book.id(), scene, BookWordCountEventType.VERSION_RESTORE, 0, 8, UUID.randomUUID(), 20));

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY).orElseThrow();
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(2);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(20);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(2);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(8);
    }

    @Test
    void interleavedUserEventsKeepPersonalDeltasButSetExactGlobalEndingTotals() {
        var book = createBook("ledger interleaved users");
        var scene = createEmptyScene(book.id(), "Interleaved scene");
        UUID userBId = createMember("ledger-user-b@iwrite.local");
        collaboratorService.grantInternal(book.id(), userBId, DEFAULT_USER_ID);
        entityManager.flush();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 5, 5, UUID.randomUUID(), 15));

        currentUserProvider.switchTo(userBId, DEFAULT_TENANT_ID, ZoneOffset.UTC);
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 3, 3, UUID.randomUUID(), 18));

        currentUserProvider.reset();
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 2, 2, UUID.randomUUID(), 20));

        DailyWritingProgress userAProgress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        DailyWritingProgress userBProgress = progressRepository.findByUser_IdAndBookIdAndProgressDate(userBId, book.id(), TODAY)
                .orElseThrow();
        assertThat(userAProgress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(userAProgress.getProductiveWordCountChange()).isEqualTo(7);
        assertThat(userAProgress.getEndingManuscriptWordCount()).isEqualTo(20);
        assertThat(userBProgress.getStartingManuscriptWordCount()).isEqualTo(15);
        assertThat(userBProgress.getProductiveWordCountChange()).isEqualTo(3);
        assertThat(userBProgress.getEndingManuscriptWordCount()).isEqualTo(18);

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, -4, -4, UUID.randomUUID(), 16));

        entityManager.flush();
        entityManager.clear();
        DailyWritingProgress userAAfterNegativeDelta = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        assertThat(userAAfterNegativeDelta.getProductiveWordCountChange()).isEqualTo(3);
        assertThat(userAAfterNegativeDelta.getEndingManuscriptWordCount()).isEqualTo(16);
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(4);
    }

    @Test
    void duplicateMatchingIdempotencyKeyReturnsAlreadyRecordedWithoutUpdatingRollupAgain() {
        var book = createBook("ledger duplicate same");
        var scene = createEmptyScene(book.id(), "Duplicate scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();
        WordCountEventCommand command = command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                4,
                4,
                idempotencyKey,
                14
        );

        WordCountEventRecordResult firstResult = eventService.record(command);
        WordCountEventRecordResult secondResult = eventService.record(command);

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY).orElseThrow();
        assertThat(firstResult).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(secondResult).isEqualTo(WordCountEventRecordResult.ALREADY_RECORDED);
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(1);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(14);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(4);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void reusedIdempotencyKeyWithConflictingIdentityIsRejected() {
        var book = createBook("ledger duplicate conflict");
        var scene = createEmptyScene(book.id(), "Conflict scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, idempotencyKey, 14));

        assertThatThrownBy(() -> eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                5,
                5,
                idempotencyKey,
                15
        ))).isInstanceOf(WordCountEventConflictException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEventsForDifferentScenesDoNotLoseRollupIncrements() {
        var book = createBook("ledger concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            var sceneA = createEmptyScene(book.id(), "Concurrent A");
            var sceneB = createEmptyScene(book.id(), "Concurrent B");

            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            int eventCount = 12;

            for (int index = 0; index < eventCount; index++) {
                SceneResponse scene = index % 2 == 0 ? sceneA : sceneB;
                futures.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    eventService.record(command(
                            book.id(),
                            scene,
                            BookWordCountEventType.CONTENT_SAVE,
                            1,
                            1,
                            UUID.randomUUID(),
                            eventCount
                    ));
                }, executor));
            }

            start.countDown();
            futures.forEach(CompletableFuture::join);

            DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY).orElseThrow();
            assertThat(eventRepository.countByBookId(book.id())).isEqualTo(eventCount);
            assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(eventCount);
            assertThat(progress.getProductiveWordCountChange()).isEqualTo(eventCount);
            assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
        } finally {
            executor.shutdownNow();
            bookService.delete(book.id());
        }
    }

    @Test
    void eventProgressDateUsesEventInstantAndCurrentUserTimezone() {
        var book = createBook("ledger timezone event");
        var scene = createEmptyScene(book.id(), "Timezone scene");
        entityManager.flush();
        Instant eventInstant = Instant.parse("2026-06-02T01:30:00Z");
        UUID idempotencyKey = UUID.randomUUID();
        writingProgressClock.setInstant(eventInstant);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, idempotencyKey, 14));

        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 1)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(4));
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 2)))
                .isEmpty();
        assertThat(eventRepository.findByBookIdAndIdempotencyKey(book.id(), idempotencyKey))
                .hasValueSatisfying(event -> assertThat(event.getCreatedAt().toInstant()).isEqualTo(eventInstant));
    }

    @Test
    void sameBookSameInstantCanRollUpToDifferentLocalDatesForDifferentUsers() {
        var book = createBook("ledger same instant zones");
        var scene = createEmptyScene(book.id(), "Same instant scene");
        UUID userBId = createMember("ledger-zoned-user-b@iwrite.local");
        collaboratorService.grantInternal(book.id(), userBId, DEFAULT_USER_ID);
        entityManager.flush();
        writingProgressClock.setInstant(Instant.parse("2026-06-02T23:30:00Z"));

        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 5, 5, UUID.randomUUID(), 15));

        currentUserProvider.switchTo(userBId, DEFAULT_TENANT_ID, ZoneId.of("Asia/Tokyo"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 3, 3, UUID.randomUUID(), 18));

        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 2)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(5));
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(userBId, book.id(), LocalDate.of(2026, 6, 3)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(3));
    }

    @Test
    void jvmDefaultZoneDoesNotDetermineProgressDate() {
        var book = createBook("ledger jvm zone ignored");
        var scene = createEmptyScene(book.id(), "Jvm zone scene");
        entityManager.flush();
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            writingProgressClock.setInstant(Instant.parse("2026-06-02T01:30:00Z"));
            currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));

            eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, UUID.randomUUID(), 14));

            assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 1)))
                    .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(4));
            assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 2)))
                    .isEmpty();
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    void fallBackRepeatedHourInstantsUpdateOneLocalDateRow() {
        var book = createBook("ledger fall back");
        var scene = createEmptyScene(book.id(), "Fall back scene");
        entityManager.flush();
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/New_York"));

        writingProgressClock.setInstant(Instant.parse("2026-11-01T05:30:00Z"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 2, 2, UUID.randomUUID(), 12));
        writingProgressClock.setInstant(Instant.parse("2026-11-01T06:30:00Z"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 3, 3, UUID.randomUUID(), 15));

        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 11, 1)))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
                    assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(15);
                });
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 10, 31)))
                .isEmpty();
    }

    @Test
    void springForwardInstantMapsToValidLocalDate() {
        var book = createBook("ledger spring forward");
        var scene = createEmptyScene(book.id(), "Spring forward scene");
        entityManager.flush();
        writingProgressClock.setInstant(Instant.parse("2026-03-08T07:30:00Z"));
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/New_York"));

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, UUID.randomUUID(), 14));

        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 3, 8)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(4));
    }

    @Test
    void timezoneChangeKeepsHistoricalRowAndFutureEventUsesNewZone() {
        var book = createBook("ledger timezone change");
        var scene = createEmptyScene(book.id(), "Timezone change scene");
        entityManager.flush();

        writingProgressClock.setInstant(Instant.parse("2026-06-02T06:30:00Z"));
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, UUID.randomUUID(), 14));

        writingProgressClock.setInstant(Instant.parse("2026-06-02T15:30:00Z"));
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("Asia/Tokyo"));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 6, 6, UUID.randomUUID(), 20));

        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 1)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(4));
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 3)))
                .hasValueSatisfying(progress -> assertThat(progress.getProductiveWordCountChange()).isEqualTo(6));
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), LocalDate.of(2026, 6, 2)))
                .isEmpty();
    }

    private SceneResponse createEmptyScene(UUID bookId, String title) {
        var section = createSection(bookService.findById(bookId), "Part " + title);
        var chapter = createChapter(section, "Chapter " + title);
        return createScene(chapter, title, SceneStatus.DRAFT, 0, "");
    }

    private WordCountEventCommand command(
            UUID bookId,
            SceneResponse scene,
            BookWordCountEventType eventType,
            int productiveWordDelta,
            int manuscriptWordDelta,
            UUID idempotencyKey,
            int knownManuscriptTotalAfterOperation
    ) {
        String requestFingerprint = WordCountRequestFingerprint.contentSave(
                currentUserProvider.userId(),
                bookId,
                scene.id(),
                0L,
                null,
                eventType.name(),
                productiveWordDelta + ":" + manuscriptWordDelta
        );
        return new WordCountEventCommand(
                bookId,
                scene.id(),
                scene.id(),
                scene.title(),
                eventType,
                productiveWordDelta,
                manuscriptWordDelta,
                UUID.randomUUID(),
                idempotencyKey,
                0L,
                1L,
                requestFingerprint,
                knownManuscriptTotalAfterOperation
        );
    }

    private void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private UUID createMember(String email) {
        User user = new User();
        user.setDisplayName(email);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    @TestConfiguration
    static class MutableWritingProgressClockConfig {

        @Bean
        @Primary
        MutableClock mutableWritingProgressClock() {
            return new MutableClock(DEFAULT_INSTANT, ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }

    static class MutableClock extends Clock {

        private final ZoneId zone;
        private volatile Instant instant;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void reset() {
            this.instant = DEFAULT_INSTANT;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
