package com.iwrite.book;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pre-auth domain test: exercises tenant authorization via CurrentUserProvider, not the session.
@AutoConfigureMockMvc(addFilters = false)
@Import(BookCollaborationAccessIntegrationTest.CurrentUserTestConfiguration.class)
class BookCollaborationAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void ownerCollaboratorUnrelatedAndForeignUsersGetExpectedBookAccess() throws Exception {
        BookResponse book = createBook("Shared C1");
        BookSectionResponse section = createSection(book, "Part");
        ChapterResponse chapter = createChapter(section, "Chapter");
        SceneResponse scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "initial words");
        CharacterResponse character = createCharacter(book, "Hero");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "B Collaborator", "c1-collab@iwrite.local");
        UUID unrelatedId = createMember(DEFAULT_TENANT_ID, "C Unrelated", "c1-unrelated@iwrite.local");
        ForeignIdentity foreign = createForeignIdentity();

        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", collaboratorId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(collaboratorId.toString()))
                .andExpect(jsonPath("$.displayName").value("B Collaborator"))
                .andExpect(jsonPath("$.role").value("LEGACY_COLLABORATOR"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/books/{bookId}/collaborators", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(collaboratorId.toString()));

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(book.id().toString()))
                .andExpect(jsonPath("$[0].relationship").value("COLLABORATOR"))
                .andExpect(jsonPath("$[0].role").value("LEGACY_COLLABORATOR"));
        mockMvc.perform(get("/api/books/{bookId}", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship").value("COLLABORATOR"))
                .andExpect(jsonPath("$.role").value("LEGACY_COLLABORATOR"))
                .andExpect(jsonPath("$.accessLevel").doesNotExist())
                .andExpect(jsonPath("$.capabilities", hasItem("READ_MANUSCRIPT")))
                .andExpect(jsonPath("$.capabilities", not(hasItem("MANAGE_COLLABORATORS"))))
                .andExpect(jsonPath("$.capabilities", not(hasItem("DELETE_BOOK"))));
        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Collaborator update\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Collaborator update"));
        mockMvc.perform(delete("/api/books/{bookId}", book.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
        mockMvc.perform(get("/api/books/{bookId}/collaborators", book.id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), collaboratorId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id()).param("format", "md"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/books/{bookId}/dashboard", book.id()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenes/{sceneId}", scene.id()))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/characters/{characterId}", character.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Brave\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/scenes/{sceneId}/content", scene.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentJson":"{}","contentText":"collaborator words today","expectedContentRevision":0,
                                 "operationId":"25000000-0000-0000-0000-000000000901"}
                                """))
                .andExpect(status().isOk());

        currentUserProvider.switchTo(unrelatedId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(book.id())).isEmpty());
        assertBookNotFound(get("/api/books/{bookId}", book.id()));
        assertBookNotFound(get("/api/books/{bookId}/dashboard", book.id()));
        assertBookNotFound(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), collaboratorId));
        mockMvc.perform(get("/api/scenes/{sceneId}", scene.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));

        currentUserProvider.switchTo(foreign.userId(), foreign.tenantId(), ZoneId.of("UTC"));
        assertBookNotFound(get("/api/books/{bookId}", book.id()));
        assertBookNotFound(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), collaboratorId));

        currentUserProvider.reset();
        mockMvc.perform(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), collaboratorId))
                .andExpect(status().isNoContent());

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(book.id())).isEmpty());
        assertBookNotFound(get("/api/books/{bookId}", book.id()));
        mockMvc.perform(get("/api/dashboard/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.productiveWords").value(0))
                .andExpect(jsonPath("$.summary.writingDays").value(0))
                .andExpect(jsonPath("$.summary.booksWrittenIn").value(0))
                .andExpect(jsonPath("$.summary.currentGlobalWritingStreak").value(0))
                .andExpect(jsonPath("$.summary.bestGlobalWritingStreak").value(0))
                .andExpect(jsonPath("$.bookContributions[?(@.bookId == '%s')]".formatted(book.id())).isEmpty());
        assertThat(progressRowCount(book.id(), collaboratorId)).isEqualTo(1L);
    }

    /**
     * Effective access is derived by the backend. A request that echoes relationship, role or
     * capabilities back must change nothing: they are a projection for the UI, never an assertion.
     */
    @Test
    void bookRequestsCannotAssertTheirOwnRelationshipRoleOrCapabilities() throws Exception {
        BookResponse book = createBook("Derived access contract");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "Derived Collaborator", "c1-derived@iwrite.local");

        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", collaboratorId, "role", "AUTHOR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("LEGACY_COLLABORATOR"));

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Still a collaborator","relationship":"OWNER","role":"AUTHOR",
                                 "capabilities":["MANAGE_COLLABORATORS","DELETE_BOOK"],
                                 "contextualCapabilities":["MANAGE_COLLABORATORS"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship").value("COLLABORATOR"))
                .andExpect(jsonPath("$.role").value("LEGACY_COLLABORATOR"))
                .andExpect(jsonPath("$.capabilities", not(hasItem("MANAGE_COLLABORATORS"))))
                .andExpect(jsonPath("$.capabilities", not(hasItem("DELETE_BOOK"))));
        mockMvc.perform(delete("/api/books/{bookId}", book.id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/books/{bookId}/collaborators", book.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void collaboratorApiRejectsOwnerDuplicateMissingAndForeignTargets() throws Exception {
        BookResponse book = createBook("Collaborator API");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "API Collaborator", "c1-api-collab@iwrite.local");
        ForeignIdentity foreign = createForeignIdentity();

        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", SwitchableCurrentUserProvider.DEFAULT_USER_ID))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", foreign.userId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", collaboratorId))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", collaboratorId))))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/books/{bookId}/collaborators/{userId}", book.id(), collaboratorId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/books/{bookId}/collaborators", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", collaboratorId))))
                .andExpect(status().isCreated());
    }

    private UUID createMember(UUID tenantId, String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
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

    private ForeignIdentity createForeignIdentity() {
        Tenant tenant = new Tenant();
        tenant.setName("Foreign C1");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "c1-foreign@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private long progressRowCount(UUID bookId, UUID userId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from book_daily_writing_progress
                        where book_id = :bookId
                          and user_id = :userId
                        """)
                .setParameter("bookId", bookId)
                .setParameter("userId", userId)
                .getSingleResult();
        return count.longValue();
    }

    private void assertBookNotFound(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    private record ForeignIdentity(UUID userId, UUID tenantId) {
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
