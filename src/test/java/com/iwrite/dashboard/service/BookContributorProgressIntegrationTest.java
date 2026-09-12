package com.iwrite.dashboard.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

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
    private SwitchableCurrentUserProvider currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUser() {
        currentUserProvider.reset();
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

        saveProgress(book, historicalContributor, 12, -2);
        saveProgress(otherBook, unrelatedMember, 99, 0);
        collaboratorRepository.delete(collaboratorRepository
                .findByBook_IdAndTenant_IdAndUser_Id(book.getId(), DEFAULT_TENANT_ID, historicalContributor)
                .orElseThrow());
        collaboratorRepository.flush();
        entityManager.clear();

        var all = dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null);

        assertThat(all.availableContributors())
                .extracting(contributor -> contributor.userId())
                .containsExactlyInAnyOrder(DEFAULT_USER_ID, currentWithoutActivity, historicalContributor)
                .doesNotContain(readOnlyWithoutActivity, unrelatedMember);

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
        assertThat(progress.dailySeries())
                .filteredOn(day -> day.date().equals(java.time.LocalDate.of(2026, 6, 24)))
                .singleElement()
                .satisfies(day -> assertThat(day.productiveWords()).isEqualTo(5));
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
    }
}
