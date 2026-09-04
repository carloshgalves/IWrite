package com.iwrite.book;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.entity.Book;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pre-auth domain test: exercises tenant authorization via CurrentUserProvider, not the session.
@AutoConfigureMockMvc(addFilters = false)
@Import(BookTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
class BookTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookWritingScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantBId;
    private UUID tenantBUserId;

    @BeforeEach
    void setUpCurrentTenant() {
        currentUserProvider.reset();
        Tenant tenantB = new Tenant();
        tenantB.setName("Tenant B");
        tenantB.setDefaultTimeZoneId("UTC");
        Tenant savedTenantB = tenantRepository.save(tenantB);
        tenantBId = savedTenantB.getId();

        User tenantBUser = new User();
        tenantBUser.setDisplayName("Tenant B User");
        tenantBUser.setEmail("book-tenant-b@iwrite.local");
        tenantBUser.setTimeZoneId("UTC");
        entityManager.persist(tenantBUser);
        tenantBUserId = tenantBUser.getId();

        TenantMembership membership = new TenantMembership();
        membership.setTenant(savedTenantB);
        membership.setUser(tenantBUser);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
    }

    @AfterEach
    void resetCurrentTenant() {
        currentUserProvider.reset();
    }

    @Test
    void listsOnlyCurrentTenantBooksAndAssignsTenantOnCreate() throws Exception {
        var bookA = createBook("Book A");

        switchToTenantB();
        var bookB = createBook("Book B");

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bookB.id().toString()))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookA.id())).isEmpty());

        currentUserProvider.reset();
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookA.id())).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookB.id())).isEmpty());

        assertThat(bookRepository.findById(bookA.id()).orElseThrow().getTenant().getId())
                .isEqualTo(SwitchableCurrentUserProvider.DEFAULT_TENANT_ID);
        assertThat(bookRepository.findById(bookB.id()).orElseThrow().getTenant().getId())
                .isEqualTo(tenantBId);

        String response = mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Client tenant ignored",
                                "tenantId", tenantBId
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID createdId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        assertThat(bookRepository.findById(createdId).orElseThrow().getTenant().getId())
                .isEqualTo(SwitchableCurrentUserProvider.DEFAULT_TENANT_ID);
    }

    @Test
    void inaccessibleReadUpdateAndDeleteReturnNotFoundWithoutChangingBook() throws Exception {
        var bookA = createBook("Protected Book A");
        switchToTenantB();

        assertBookNotFound(get("/api/books/{bookId}", bookA.id()));
        assertBookNotFound(patch("/api/books/{bookId}", bookA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Compromised\"}"));

        Book unchanged = bookRepository.findById(bookA.id()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Protected Book A");

        assertBookNotFound(delete("/api/books/{bookId}", bookA.id()));
        assertThat(bookRepository.findById(bookA.id())).isPresent();

        assertBookNotFound(get("/api/books/{bookId}", UUID.randomUUID()));
    }

    @Test
    void inaccessibleManuscriptAndNotebookExportsReturnNotFoundForEveryRequiredFormat() throws Exception {
        var bookA = createBook("Private exports");
        switchToTenantB();

        for (String format : new String[]{"txt", "md", "docx"}) {
            assertBookNotFound(get("/api/books/{bookId}/exports/manuscript", bookA.id())
                    .param("format", format));
        }
        assertBookNotFound(get("/api/books/{bookId}/exports/notebook", bookA.id())
                .param("format", "md"));
    }

    @Test
    void owningTenantRetainsReadUpdateDeleteAndExportBehavior() throws Exception {
        var bookA = createBook("Owned Book A");

        mockMvc.perform(get("/api/books/{bookId}", bookA.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Owned Book A"));
        mockMvc.perform(patch("/api/books/{bookId}", bookA.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated Book A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Book A"));

        for (String format : new String[]{"txt", "md", "docx"}) {
            mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", bookA.id())
                            .param("format", format))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", bookA.id())
                        .param("format", "md"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/books/{bookId}", bookA.id()))
                .andExpect(status().isNoContent());
        assertThat(bookRepository.findById(bookA.id())).isEmpty();
    }

    @Test
    void sameTenantMemberLazilyReceivesIndependentDefaultScheduleThroughTheirOwnWritingGoal() throws Exception {
        var book = createBook("Shared same-tenant book");
        mockMvc.perform(patch("/api/books/{bookId}/writing-goal", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"plannedWritingDays":["MONDAY","WEDNESDAY"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedWritingDays", contains("MONDAY", "WEDNESDAY")));

        UUID sameTenantUserId = createMember(DEFAULT_TENANT_ID, "same-tenant-reader@iwrite.local");
        addCollaborator(book.id(), sameTenantUserId);
        currentUserProvider.switchTo(sameTenantUserId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));

        // Reading the shared Book no longer fabricates a personal routine, because the routine left the
        // Book contract with the Personal Book Writing Goal: only the goal surface creates one lazily.
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/books/{bookId}", book.id()))
                .andExpect(status().isOk());
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(sameTenantUserId, book.id()))
                .isZero();

        // Their own goal, not the owner's: the default routine and no target at all, never the
        // Monday/Wednesday routine the owner chose for themselves.
        mockMvc.perform(get("/api/books/{bookId}/writing-goal", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTargetWordCount").value(nullValue()))
                .andExpect(jsonPath("$.plannedWritingDays", contains(
                        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
                )));
        mockMvc.perform(get("/api/books/{bookId}/writing-goal", book.id()))
                .andExpect(status().isOk());

        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(sameTenantUserId, book.id()))
                .isEqualTo(1);

        currentUserProvider.reset();
        assertThat(scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()))
                .hasValueSatisfying(schedule -> assertThat(schedule.getPlannedDays())
                        .containsExactlyInAnyOrder(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY));

        UUID sameTenantPatchUserId = createMember(DEFAULT_TENANT_ID, "same-tenant-patcher@iwrite.local");
        currentUserProvider.reset();
        addCollaborator(book.id(), sameTenantPatchUserId);
        currentUserProvider.switchTo(sameTenantPatchUserId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(patch("/api/books/{bookId}/writing-goal", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"plannedWritingDays":["FRIDAY"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedWritingDays", contains("FRIDAY")));

        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(sameTenantPatchUserId, book.id()))
                .isEqualTo(1);
        assertThat(scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(sameTenantPatchUserId, book.id()))
                .hasValueSatisfying(schedule -> assertThat(schedule.getPlannedDays())
                        .containsExactly(java.time.DayOfWeek.FRIDAY));

        currentUserProvider.reset();
        assertThat(scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()))
                .hasValueSatisfying(schedule -> assertThat(schedule.getPlannedDays())
                        .containsExactlyInAnyOrder(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY));
    }

    private void switchToTenantB() {
        currentUserProvider.switchTo(tenantBUserId, tenantBId, ZoneId.of("UTC"));
    }

    private UUID createMember(UUID tenantId, String email) {
        User user = new User();
        user.setDisplayName(email);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, tenantId));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    private void addCollaborator(UUID bookId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/books/{bookId}/collaborators", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", userId))))
                .andExpect(status().isCreated());
    }

    private void assertBookNotFound(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }

    }
}
