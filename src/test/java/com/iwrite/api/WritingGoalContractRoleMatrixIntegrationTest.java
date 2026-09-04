package com.iwrite.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP role matrix for the shared-settings / Personal Book Writing Goal split of #206.
 *
 * <p>The service seam already proves the Owner/AUTHOR/EDITOR/READER/LEGACY_COLLABORATOR matrix.
 * #206 also requires it at the HTTP contract, so this exercises {@code /api/books/{id}/writing-goal}
 * and the shared {@code PATCH /api/books/{id}} for every relationship: the roles the capability
 * policy grants succeed, and the roles it denies get the same non-enumerable {@code 404 Book not
 * found} as someone who cannot see the Book at all.
 */
@AutoConfigureMockMvc(addFilters = false)
@Import(WritingGoalContractRoleMatrixIntegrationTest.CurrentUserTestConfiguration.class)
class WritingGoalContractRoleMatrixIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private BookCollaboratorRepository collaboratorRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void ownerManagesBothTheWritingGoalAndTheSharedBookSettings() throws Exception {
        BookResponse book = createBook("HTTP owner matrix");

        assertGoalReadable(book.id());
        assertGoalWritable(book.id());
        assertSettingsWritable(book.id());
    }

    @ParameterizedTest
    @EnumSource(BookRole.class)
    void collaboratorRolesGetTheSameHttpSurfaceAsTheServiceMatrix(BookRole role) throws Exception {
        BookResponse book = createBook("HTTP matrix " + role);
        UUID collaboratorId = grantRole(book.id(), role);

        switchTo(collaboratorId);

        if (canManageOwnGoal(role)) {
            assertGoalReadable(book.id());
            assertGoalWritable(book.id());
        } else {
            assertGoalNotFound(book.id());
        }

        if (canEditBookSettings(role)) {
            assertSettingsWritable(book.id());
        } else {
            assertSettingsNotFound(book.id());
        }
    }

    // Mirrors BookCapabilityPolicy: MANAGE_OWN_PERSONAL_WRITING_GOAL is granted to Owner, AUTHOR and
    // the legacy compatibility role; EDIT_BOOK_SETTINGS only to Owner and the legacy role.
    private static boolean canManageOwnGoal(BookRole role) {
        return role == BookRole.AUTHOR || role == BookRole.LEGACY_COLLABORATOR;
    }

    private static boolean canEditBookSettings(BookRole role) {
        return role == BookRole.LEGACY_COLLABORATOR;
    }

    private void assertGoalReadable(UUID bookId) throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/writing-goal", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedWritingDays", hasSize(7)));
    }

    private void assertGoalWritable(UUID bookId) throws Exception {
        mockMvc.perform(patch("/api/books/{bookId}/writing-goal", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedRevision", 0, "dailyTargetWordCount", 640))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTargetWordCount").value(640));
    }

    private void assertSettingsWritable(UUID bookId) throws Exception {
        mockMvc.perform(patch("/api/books/{bookId}", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetWordCount", 90000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetWordCount").value(90000));
    }

    private void assertGoalNotFound(UUID bookId) throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/writing-goal", bookId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));

        mockMvc.perform(patch("/api/books/{bookId}/writing-goal", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedRevision", 0, "dailyTargetWordCount", 640))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    private void assertSettingsNotFound(UUID bookId) throws Exception {
        mockMvc.perform(patch("/api/books/{bookId}", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetWordCount", 90000))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private UUID grantRole(UUID bookId, BookRole role) {
        UUID userId = createMember("Collaborator " + role, "matrix-" + role.name().toLowerCase() + "@iwrite.local");
        BookCollaborator collaborator = new BookCollaborator();
        collaborator.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        collaborator.setBook(entityManager.getReference(Book.class, bookId));
        collaborator.setUser(entityManager.getReference(User.class, userId));
        collaborator.setCreatedBy(entityManager.getReference(User.class, DEFAULT_USER_ID));
        collaborator.setRole(role);
        collaboratorRepository.saveAndFlush(collaborator);
        return userId;
    }

    private UUID createMember(String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
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

    private void switchTo(UUID userId) {
        currentUserProvider.switchTo(userId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
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
