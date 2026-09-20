package com.iwrite.dashboard.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.dashboard.dto.BookContributionDashboardResponse;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SqlStatementRecorder;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@Import(BookContributorProgressIntegrationTest.FixedWritingProgressClockConfig.class)
class BookContributorProgressIntegrationTest extends PostgresIntegrationTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-24T03:30:00Z");

    @Autowired
    private UserDashboardService dashboardService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookCollaboratorRepository collaboratorRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private SqlStatementRecorder sqlStatementRecorder;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUser() {
        currentUserProvider.reset();
        sqlStatementRecorder.reset();
    }

    @Test
    void contributorProgressRequiresItsMinimumBookCapabilityAtServiceAndHttpSeams() throws Exception {
        var book = createBook("Contributor progress role matrix");
        UUID authorId = grantRole(book.id(), "Author", BookRole.AUTHOR);
        UUID editorId = grantRole(book.id(), "Editor", BookRole.EDITOR);
        UUID readerId = grantRole(book.id(), "Reader", BookRole.READER);
        UUID legacyId = grantRole(book.id(), "Legacy", BookRole.LEGACY_COLLABORATOR);

        for (UUID allowedUserId : new UUID[]{DEFAULT_USER_ID, authorId, editorId, legacyId}) {
            switchTo(allowedUserId);
            assertThatCode(() -> dashboardService.getBookContributions(
                    book.id(), WritingProgressPeriod.SEVEN_DAYS, null
            )).doesNotThrowAnyException();
            mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.id()))
                    .andExpect(status().isOk());
        }

        switchTo(readerId);
        assertThatThrownBy(() -> dashboardService.getBookContributions(
                book.id(), WritingProgressPeriod.SEVEN_DAYS, null
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void contributorFilterAcceptsCurrentAndProvableHistoricalBookRelationshipsOnly() {
        Book book = bookService.getBook(createBook("Contributor eligibility").id());
        UUID currentWithoutActivity = grantRole(book.getId(), "Current no activity", BookRole.AUTHOR);
        UUID readOnlyWithoutActivity = grantRole(book.getId(), "Reader no activity", BookRole.READER);
        UUID historicalContributor = grantRole(book.getId(), "Historical writer", BookRole.EDITOR);
        UUID unrelatedMember = createMember("Unrelated member");
        Book otherBook = bookService.getBook(createBook("Other contribution book").id());
        for (int index = 0; index < 24; index++) {
            createMember("Unrelated installation user " + index);
        }

        saveProgress(book, historicalContributor, 12, -2);
        saveProgress(otherBook, unrelatedMember, 99, 0);
        collaboratorRepository.delete(collaboratorRepository
                .findByBook_IdAndTenant_IdAndUser_Id(book.getId(), DEFAULT_TENANT_ID, historicalContributor)
                .orElseThrow());
        collaboratorRepository.flush();
        entityManager.clear();

        AtomicReference<BookContributionDashboardResponse> response = new AtomicReference<>();
        List<String> statements = sqlStatementRecorder.recordStatementsOf(() -> response.set(
                dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null)
        ));
        var all = response.get();

        assertThat(all.availableContributors())
                .extracting(contributor -> contributor.userId())
                .containsExactlyInAnyOrder(DEFAULT_USER_ID, currentWithoutActivity, historicalContributor)
                .doesNotContain(readOnlyWithoutActivity, unrelatedMember);

        assertThat(statements.stream()
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .map(statement -> statement.replaceAll("\\s+", " ").trim())
                .filter(statement -> statement.contains("book_daily_writing_progress")
                        && statement.contains("book_word_count_events")
                        && statement.contains("book_collaborators"))
                .toList())
                .singleElement()
                .satisfies(candidateDiscovery -> {
                    assertThat(candidateDiscovery.stripLeading()).startsWith("with candidate_ids as");
                    assertThat(candidateDiscovery).contains(" union ", " join users ");
                    assertThat(candidateDiscovery).doesNotContain("from users");
                });

        var currentZero = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, currentWithoutActivity
        );
        assertThat(currentZero.selectedContributor().userId()).isEqualTo(currentWithoutActivity);
        assertThat(currentZero.summary().productiveWords()).isZero();

        var historical = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, historicalContributor
        );
        assertThat(historical.selectedContributor().userId()).isEqualTo(historicalContributor);
        assertThat(historical.summary().productiveWords()).isEqualTo(12);
        assertThat(historical.summary().manuscriptAdjustments()).isEqualTo(-2);

        assertThatThrownBy(() -> dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, unrelatedMember
        )).isInstanceOf(ResourceNotFoundException.class);

        switchTo(historicalContributor);
        assertThatThrownBy(() -> dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, historicalContributor
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
    }

    @Test
    void selectedContributorMetricsUseOnlyAuthenticatedEventsAndKeepSceneChapterOrigins() {
        var book = createBook("Contributor event origins");
        var section = createSection(book, "Part one");
        var firstChapter = createChapter(section, "First chapter");
        var secondChapter = createChapter(section, "Second chapter");
        var firstScene = createScene(firstChapter, "Shared opening", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, wordText(10));
        var secondScene = createScene(secondChapter, "Shared ending", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, wordText(7));
        // Until #184 attributes Scene text, AUTHOR is only CONTEXTUALLY eligible to edit canonical
        // content and SceneContentAuthority resolves that to the Owner alone; the legacy compatibility
        // role is the collaborator identity that still holds EDIT_AUTHORED_CONTRIBUTION outright (#207).
        UUID contributorId = grantRole(book.id(), "Attributed contributor", BookRole.LEGACY_COLLABORATOR);

        switchTo(contributorId);
        sceneService.updateContent(firstScene.id(), new SceneContentRequest(
                "{}", wordText(12), SceneVersionSource.MANUAL_SAVE, firstScene.contentRevision()
        ));
        sceneService.updateContent(secondScene.id(), new SceneContentRequest(
                "{}", wordText(10), SceneVersionSource.MANUAL_SAVE, secondScene.contentRevision()
        ));

        currentUserProvider.reset();
        sceneService.delete(firstScene.id());
        entityManager.flush();
        entityManager.clear();

        var progress = dashboardService.getBookContributions(
                book.id(), WritingProgressPeriod.SEVEN_DAYS, contributorId
        );

        // The contributor added 2 + 3 words. The pre-existing 10 + 7 words in these shared Scenes
        // belong to the Owner's authenticated events and must never be copied into this contributor's metrics.
        assertThat(progress.summary().productiveWords()).isEqualTo(5);
        assertThat(progress.summary().writingDays()).isEqualTo(1);
        assertThat(progress.summary().distinctScenes()).isEqualTo(2);
        assertThat(progress.summary().distinctChapters()).isEqualTo(2);
        assertThat(progress.origins())
                .extracting(origin -> origin.sceneTitle())
                .containsExactly("Shared opening", "Shared ending");
        assertThat(progress.origins())
                .extracting(origin -> origin.chapterTitle())
                .containsExactly("First chapter", "Second chapter");
        assertThat(progress.origins())
                .extracting(origin -> origin.productiveWords())
                .containsExactly(2L, 3L);
        assertThat(progress.origins())
                .extracting(origin -> origin.writingDays())
                .containsExactly(1L, 1L);
        assertThat(progress.dailySeries())
                .filteredOn(day -> day.date().equals(java.time.LocalDate.of(2026, 6, 24)))
                .singleElement()
                .satisfies(day -> assertThat(day.productiveWords()).isEqualTo(5));
    }

    @Test
    void ledgerOnlyEventsKeepSummaryDailySeriesAndOriginsConsistent() {
        Book book = bookService.getBook(createBook("Legacy ledger-only contribution").id());
        UUID sceneId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();

        BookWordCountEvent event = new BookWordCountEvent();
        event.setBook(book);
        event.setActorUser(entityManager.getReference(User.class, DEFAULT_USER_ID));
        event.setOriginalSceneId(sceneId);
        event.setSceneTitleSnapshot("Legacy scene");
        event.setProgressDate(java.time.LocalDate.of(2026, 6, 24));
        event.setOriginalChapterId(chapterId);
        event.setChapterTitleSnapshot("Legacy chapter");
        event.setEventType(BookWordCountEventType.CONTENT_SAVE);
        event.setProductiveWordDelta(7);
        event.setManuscriptWordDelta(4);
        event.setOperationId(UUID.randomUUID());
        event.setIdempotencyKey(UUID.randomUUID());
        eventRepository.saveAndFlush(event);
        entityManager.clear();

        var progress = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, null
        );

        assertThat(progress.summary().productiveWords()).isEqualTo(7);
        assertThat(progress.summary().manuscriptAdjustments()).isEqualTo(-3);
        assertThat(progress.summary().writingDays()).isEqualTo(1);
        assertThat(progress.summary().contributorsCount()).isEqualTo(1);
        assertThat(progress.summary().distinctScenes()).isEqualTo(1);
        assertThat(progress.summary().distinctChapters()).isEqualTo(1);
        assertThat(progress.dailySeries())
                .filteredOn(day -> day.date().equals(java.time.LocalDate.of(2026, 6, 24)))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.productiveWords()).isEqualTo(7);
                    assertThat(day.manuscriptAdjustments()).isEqualTo(-3);
                });
        assertThat(progress.origins()).singleElement().satisfies(origin -> {
            assertThat(origin.sceneId()).isEqualTo(sceneId);
            assertThat(origin.productiveWords()).isEqualTo(7);
            assertThat(origin.manuscriptAdjustments()).isEqualTo(-3);
            assertThat(origin.writingDays()).isEqualTo(1);
        });
    }

    @Test
    void contributionEventQueryAggregatesAutosavesByContributorOriginAndDate() {
        Book book = bookService.getBook(createBook("Aggregated contribution events").id());
        UUID sceneId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        java.time.LocalDate progressDate = java.time.LocalDate.of(2026, 6, 24);

        saveLedgerEvent(book, DEFAULT_USER_ID, sceneId, chapterId, progressDate, 10);
        saveLedgerEvent(book, DEFAULT_USER_ID, sceneId, chapterId, progressDate, -3);
        entityManager.clear();

        var aggregates = eventRepository.findBookContributionEventAggregatesBetween(
                book.getId(), progressDate, progressDate
        );

        assertThat(aggregates).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.getActorUserId()).isEqualTo(DEFAULT_USER_ID);
            assertThat(aggregate.getProgressDate()).isEqualTo(progressDate);
            assertThat(aggregate.getOriginalSceneId()).isEqualTo(sceneId);
            assertThat(aggregate.getOriginalChapterId()).isEqualTo(chapterId);
            assertThat(aggregate.getProductiveWordDelta()).isEqualTo(7);
            assertThat(aggregate.getManuscriptWordDelta()).isEqualTo(7);
        });
    }

    @Test
    void originWritingDaysUseContributorOriginDailyNet() {
        Book book = bookService.getBook(createBook("Origin writing-day net").id());
        UUID sceneId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        java.time.LocalDate progressDate = java.time.LocalDate.of(2026, 6, 24);

        saveLedgerEvent(book, DEFAULT_USER_ID, sceneId, chapterId, progressDate, 10);
        saveLedgerEvent(book, DEFAULT_USER_ID, sceneId, chapterId, progressDate, -10);
        entityManager.clear();

        var progress = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, DEFAULT_USER_ID
        );

        assertThat(progress.summary().productiveWords()).isZero();
        assertThat(progress.summary().writingDays()).isZero();
        assertThat(progress.origins()).singleElement().satisfies(origin -> {
            assertThat(origin.sceneId()).isEqualTo(sceneId);
            assertThat(origin.productiveWords()).isZero();
            assertThat(origin.writingDays()).isZero();
        });
    }

    @Test
    void contributorCountPreservesRecordedActivityWhenEventDeltasNetToZero() {
        Book book = bookService.getBook(createBook("Net-zero contributor activity").id());
        UUID activeContributorId = grantRole(
                book.getId(), "Positive contributor", BookRole.LEGACY_COLLABORATOR
        );
        UUID inactiveContributorId = grantRole(
                book.getId(), "Contributor without activity", BookRole.AUTHOR
        );
        UUID chapterId = UUID.randomUUID();
        java.time.LocalDate progressDate = java.time.LocalDate.of(2026, 6, 24);

        saveLedgerEvent(book, DEFAULT_USER_ID, UUID.randomUUID(), chapterId, progressDate, 10);
        saveLedgerEvent(book, DEFAULT_USER_ID, UUID.randomUUID(), chapterId, progressDate, -10);
        saveLedgerEvent(book, activeContributorId, UUID.randomUUID(), chapterId, progressDate, 5);
        saveProgress(book, DEFAULT_USER_ID, 0, 0);
        entityManager.clear();

        var allContributors = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, null
        );
        var netZeroContributor = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, DEFAULT_USER_ID
        );
        var inactiveContributor = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, inactiveContributorId
        );

        assertThat(allContributors.summary().contributorsCount()).isEqualTo(2);
        assertThat(allContributors.summary().productiveWords()).isEqualTo(5);
        assertThat(allContributors.summary().writingDays()).isEqualTo(1);
        assertThat(netZeroContributor.summary().contributorsCount()).isEqualTo(1);
        assertThat(netZeroContributor.summary().productiveWords()).isZero();
        assertThat(netZeroContributor.summary().writingDays()).isZero();
        assertThat(netZeroContributor.origins()).hasSize(2);
        assertThat(inactiveContributor.summary().contributorsCount()).isZero();
    }

    @Test
    void originWritingDaysKeepPositiveNetsSeparatedByContributorAndOrigin() {
        Book book = bookService.getBook(createBook("Separated origin writing days").id());
        UUID otherContributorId = grantRole(book.getId(), "Other origin contributor", BookRole.LEGACY_COLLABORATOR);
        UUID chapterId = UUID.randomUUID();
        UUID netPositiveSceneId = UUID.randomUUID();
        UUID crossContributorSceneId = UUID.randomUUID();
        UUID negativeSceneId = UUID.randomUUID();
        java.time.LocalDate progressDate = java.time.LocalDate.of(2026, 6, 24);

        saveLedgerEvent(book, DEFAULT_USER_ID, netPositiveSceneId, chapterId, progressDate, 10);
        saveLedgerEvent(book, DEFAULT_USER_ID, netPositiveSceneId, chapterId, progressDate, -3);
        saveLedgerEvent(book, DEFAULT_USER_ID, crossContributorSceneId, chapterId, progressDate, 10);
        saveLedgerEvent(book, otherContributorId, crossContributorSceneId, chapterId, progressDate, -10);
        saveLedgerEvent(book, DEFAULT_USER_ID, negativeSceneId, chapterId, progressDate, -10);
        entityManager.clear();

        var progress = dashboardService.getBookContributions(
                book.getId(), WritingProgressPeriod.SEVEN_DAYS, null
        );

        assertThat(progress.summary().writingDays()).isEqualTo(1);
        assertThat(progress.origins())
                .filteredOn(origin -> origin.sceneId().equals(netPositiveSceneId))
                .singleElement()
                .satisfies(origin -> {
                    assertThat(origin.productiveWords()).isEqualTo(7);
                    assertThat(origin.writingDays()).isEqualTo(1);
                });
        assertThat(progress.origins())
                .filteredOn(origin -> origin.sceneId().equals(crossContributorSceneId))
                .singleElement()
                .satisfies(origin -> {
                    assertThat(origin.productiveWords()).isZero();
                    assertThat(origin.writingDays()).isEqualTo(1);
                });
        assertThat(progress.origins())
                .filteredOn(origin -> origin.sceneId().equals(negativeSceneId))
                .singleElement()
                .satisfies(origin -> {
                    assertThat(origin.productiveWords()).isEqualTo(-10);
                    assertThat(origin.writingDays()).isZero();
                });
    }

    private void saveLedgerEvent(
            Book book,
            UUID contributorId,
            UUID sceneId,
            UUID chapterId,
            java.time.LocalDate progressDate,
            int productiveWordDelta
    ) {
        BookWordCountEvent event = new BookWordCountEvent();
        event.setBook(book);
        event.setActorUser(entityManager.getReference(User.class, contributorId));
        event.setOriginalSceneId(sceneId);
        event.setSceneTitleSnapshot("Scene " + sceneId);
        event.setProgressDate(progressDate);
        event.setOriginalChapterId(chapterId);
        event.setChapterTitleSnapshot("Chapter " + chapterId);
        event.setEventType(BookWordCountEventType.CONTENT_SAVE);
        event.setProductiveWordDelta(productiveWordDelta);
        event.setManuscriptWordDelta(productiveWordDelta);
        event.setOperationId(UUID.randomUUID());
        event.setIdempotencyKey(UUID.randomUUID());
        eventRepository.saveAndFlush(event);
    }

    private UUID grantRole(UUID bookId, String displayName, BookRole role) {
        UUID userId = createMember(displayName);
        User user = entityManager.getReference(User.class, userId);

        BookCollaborator collaborator = new BookCollaborator();
        collaborator.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        collaborator.setBook(entityManager.getReference(com.iwrite.book.entity.Book.class, bookId));
        collaborator.setUser(user);
        collaborator.setCreatedBy(entityManager.getReference(User.class, DEFAULT_USER_ID));
        collaborator.setRole(role);
        collaboratorRepository.saveAndFlush(collaborator);
        return user.getId();
    }

    private UUID createMember(String displayName) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(displayName.toLowerCase() + "." + UUID.randomUUID() + "@iwrite.local");
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        return user.getId();
    }

    private void saveProgress(Book book, UUID userId, int productiveWords, int manuscriptAdjustments) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(entityManager.getReference(Book.class, book.getId()));
        progress.setUser(entityManager.getReference(User.class, userId));
        progress.setProgressDate(java.time.LocalDate.of(2026, 6, 24));
        progress.setStartingManuscriptWordCount(0);
        progress.setEndingManuscriptWordCount(productiveWords + manuscriptAdjustments);
        progress.setProductiveWordCountChange(productiveWords);
        progress.setManuscriptAdjustmentWordCount(manuscriptAdjustments);
        progressRepository.save(progress);
        entityManager.flush();
    }

    private void switchTo(UUID userId) {
        currentUserProvider.switchTo(userId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        @Bean
        SqlStatementRecorder sqlStatementRecorder() {
            return new SqlStatementRecorder();
        }

        @Bean
        HibernatePropertiesCustomizer sqlStatementRecorderCustomizer(SqlStatementRecorder recorder) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, recorder);
        }
    }
}
