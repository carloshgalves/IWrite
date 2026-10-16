package com.iwrite.dashboard.controller;

import com.iwrite.book.entity.Book;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pre-auth domain test: exercises tenant authorization via CurrentUserProvider, not the session.
@AutoConfigureMockMvc(addFilters = false)
@Import(BookDashboardControllerIntegrationTest.FixedWritingProgressClockConfig.class)
class BookDashboardControllerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-24T03:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void invalidProgressPeriodReturnsBadRequest() throws Exception {
        var book = createBook("invalid progress period");

        mockMvc.perform(get("/api/books/{bookId}/dashboard", book.id())
                        .param("progressPeriod", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("progressPeriod"))));
    }

    @Test
    void contributionsDefaultToAllRecordedContributors() throws Exception {
        Book book = bookService.getBook(createBook("HTTP contributions all").id());
        User contributor = createUser("HTTP Team Writer", "http.team.writer@iwrite.local");
        addDefaultTenantMembership(contributor);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 24), 10, 0);
        saveProgress(book, contributor.getId(), LocalDate.of(2026, 6, 24), 5, 2);
        entityManager.flush();

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ALL_CONTRIBUTORS"))
                .andExpect(jsonPath("$.selectedContributor").value(nullValue()))
                .andExpect(jsonPath("$.availableContributors", hasSize(2)))
                .andExpect(jsonPath("$.availableContributors[0].userId").value(DEFAULT_USER_ID.toString()))
                .andExpect(jsonPath("$.availableContributors[0].displayName").value("Carlos"))
                .andExpect(jsonPath("$.availableContributors[0].email").doesNotExist())
                .andExpect(jsonPath("$.summary.productiveWords").value(15))
                .andExpect(jsonPath("$.summary.manuscriptAdjustments").value(2))
                .andExpect(jsonPath("$.summary.contributorsCount").value(2))
                .andExpect(jsonPath("$.summary.distinctScenes").value(0))
                .andExpect(jsonPath("$.summary.distinctChapters").value(0))
                .andExpect(jsonPath("$.origins", hasSize(0)))
                .andExpect(jsonPath("$.dailyTargetWordCount").doesNotExist())
                .andExpect(jsonPath("$.plannedWritingDays").doesNotExist())
                .andExpect(jsonPath("$.dailySeries[?(@.date == '2026-06-24')].productiveWords").value(hasItem(15)));
    }

    @Test
    void contributionsAcceptCurrentUserFilterWithZeroActivity() throws Exception {
        Book book = bookService.getBook(createBook("HTTP contributions current zero").id());

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.getId())
                        .param("contributorId", DEFAULT_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SINGLE_CONTRIBUTOR"))
                .andExpect(jsonPath("$.selectedContributor.userId").value(DEFAULT_USER_ID.toString()))
                .andExpect(jsonPath("$.selectedContributor.displayName").value("Carlos"))
                .andExpect(jsonPath("$.selectedContributor.email").doesNotExist())
                .andExpect(jsonPath("$.summary.productiveWords").value(0))
                .andExpect(jsonPath("$.summary.contributorsCount").value(0))
                .andExpect(jsonPath("$.summary.distinctScenes").value(0))
                .andExpect(jsonPath("$.summary.distinctChapters").value(0))
                .andExpect(jsonPath("$.origins", hasSize(0)));
    }

    @Test
    void contributionsSelectRecordedContributor() throws Exception {
        Book book = bookService.getBook(createBook("HTTP contributions selected").id());
        User contributor = createUser("HTTP Selected Writer", "http.selected.writer@iwrite.local");
        addDefaultTenantMembership(contributor);
        saveProgress(book, contributor.getId(), LocalDate.of(2026, 6, 24), 7, -1);
        entityManager.flush();

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.getId())
                        .param("contributorId", contributor.getId().toString())
                        .param("progressPeriod", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.value").value("30d"))
                .andExpect(jsonPath("$.scope").value("SINGLE_CONTRIBUTOR"))
                .andExpect(jsonPath("$.selectedContributor.userId").value(contributor.getId().toString()))
                .andExpect(jsonPath("$.selectedContributor.displayName").value("HTTP Selected Writer"))
                .andExpect(jsonPath("$.summary.productiveWords").value(7))
                .andExpect(jsonPath("$.summary.manuscriptAdjustments").value(-1));
    }

    @Test
    void contributionsRejectUnrelatedContributorIdsWithoutExposingUserDetails() throws Exception {
        Book book = bookService.getBook(createBook("HTTP contributions rejected").id());
        User sameTenantUser = createUser("HTTP Same Tenant", "http.same.tenant@iwrite.local");
        addDefaultTenantMembership(sameTenantUser);
        User foreignTenantUser = createForeignTenantUser();

        assertRejectedContributor(book.getId(), sameTenantUser.getId());
        assertRejectedContributor(book.getId(), foreignTenantUser.getId());
    }

    @Test
    void contributionsRetainNotFoundForMissingAndForeignBooks() throws Exception {
        Book foreignBook = createForeignTenantBook("HTTP foreign contributions book");

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", foreignBook.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    @Test
    void contributionsInvalidPeriodAndMalformedContributorFollowApiErrorSemantics() throws Exception {
        Book book = bookService.getBook(createBook("HTTP contributions invalid params").id());

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.getId())
                        .param("progressPeriod", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("progressPeriod"))));

        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", book.getId())
                        .param("contributorId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Invalid value for contributorId"))));
    }

    private void assertRejectedContributor(UUID bookId, UUID contributorId) throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/dashboard/contributions", bookId)
                        .param("contributorId", contributorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Recorded contributor not found"))))
                .andExpect(jsonPath("$.messages", hasItem(not(containsString(contributorId.toString())))));
    }

    private void saveProgress(Book book, UUID userId, LocalDate progressDate, int productiveWords, int manuscriptAdjustments) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(entityManager.getReference(Book.class, book.getId()));
        progress.setUser(entityManager.getReference(User.class, userId));
        progress.setProgressDate(progressDate);
        progress.setStartingManuscriptWordCount(0);
        progress.setEndingManuscriptWordCount(productiveWords + manuscriptAdjustments);
        progress.setProductiveWordCountChange(productiveWords);
        progress.setManuscriptAdjustmentWordCount(manuscriptAdjustments);
        progressRepository.save(progress);
    }

    private User createUser(String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId("America/Sao_Paulo");
        entityManager.persist(user);
        return user;
    }

    private void addDefaultTenantMembership(User user) {
        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
    }

    private User createForeignTenantUser() {
        Tenant tenant = createForeignTenant("HTTP foreign user tenant");
        User user = createUser("HTTP Foreign Tenant", "http.foreign.tenant@iwrite.local");
        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        return user;
    }

    private Book createForeignTenantBook(String title) {
        Tenant tenant = createForeignTenant("HTTP foreign book tenant");
        User owner = createUser("HTTP Foreign Book Owner", "http.foreign.book.owner@iwrite.local");
        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(owner);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);

        Book book = new Book();
        book.setTenant(tenant);
        book.setOwner(owner);
        book.setTitle(title);
        entityManager.persist(book);
        return book;
    }

    private Tenant createForeignTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("UTC");
        entityManager.persist(tenant);
        return tenant;
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
