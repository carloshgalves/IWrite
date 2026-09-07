package com.iwrite.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.section.dto.BookSectionResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The #207 capability rules at the HTTP contract.
 *
 * <p>The service seam already proves the matrix; what this adds is that the routes agree with it and
 * that a denial reaches the client as the same not-found it would get for a Book, Section, Chapter or
 * Scene that does not exist, with no hint that the identifier is real.
 */
@AutoConfigureMockMvc(addFilters = false)
@Import(ManuscriptContractRoleMatrixIntegrationTest.CurrentUserTestConfiguration.class)
class ManuscriptContractRoleMatrixIntegrationTest extends PostgresIntegrationTest {

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
    void theOwnerReadsAndRestructuresTheManuscriptOverHttp() throws Exception {
        StoryWorld world = createStoryWorld("HTTP owner manuscript");

        assertOutlineAndSceneReadable(world, true);
        assertStructureRoutesAccepted(world);
        assertContentSaveAccepted(world);
    }

    @ParameterizedTest
    @EnumSource(BookRole.class)
    void bookRoutesAnswerEachRoleExactlyAsTheCapabilityMatrixDoes(BookRole role) throws Exception {
        StoryWorld world = createStoryWorld("HTTP manuscript " + role);
        UUID collaboratorId = grantRole(world.book().id(), role);

        switchTo(collaboratorId);

        if (canReadManuscript(role)) {
            assertOutlineAndSceneReadable(world, canSaveSceneContent(role));
        } else {
            assertOutlineAndSceneNotFound(world);
        }

        if (canMutateStructure(role)) {
            assertStructureRoutesAccepted(world);
            assertContentSaveAccepted(world);
        } else {
            assertStructureRoutesNotFound(world);
            assertContentSaveNotFound(world);
        }
    }

    @Test
    void aWorkspaceMemberWithoutABookRoleCannotProbeTheHierarchyByIdentifier() throws Exception {
        StoryWorld world = createStoryWorld("HTTP stranger manuscript");
        UUID strangerId = createMember("Stranger", "http-manuscript-stranger@iwrite.local");

        switchTo(strangerId);

        assertOutlineAndSceneNotFound(world);
        assertStructureRoutesNotFound(world);
        assertContentSaveNotFound(world);
    }

    // Mirrors BookCapabilityPolicy: READ_MANUSCRIPT for Author, Editor and the legacy role;
    // MUTATE_MANUSCRIPT_STRUCTURE and an effective content save only for the legacy role.
    private static boolean canReadManuscript(BookRole role) {
        return role == BookRole.AUTHOR || role == BookRole.EDITOR || role == BookRole.LEGACY_COLLABORATOR;
    }

    private static boolean canMutateStructure(BookRole role) {
        return role == BookRole.LEGACY_COLLABORATOR;
    }

    /**
     * The effective authority over the canonical text, which the Scene route projects as
     * {@code canEditContent}. An Author is eligible for the capability and still not authorized here,
     * so the projection has to answer the resource-scoped rule and not the Book-scoped eligibility.
     */
    private static boolean canSaveSceneContent(BookRole role) {
        return role == BookRole.LEGACY_COLLABORATOR;
    }

    private void assertOutlineAndSceneReadable(StoryWorld world, boolean canEditContent) throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/outline", world.book().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].id").value(world.section().id().toString()));

        mockMvc.perform(get("/api/scenes/{sceneId}", world.scene().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(world.scene().id().toString()))
                .andExpect(jsonPath("$.canEditContent").value(canEditContent));
    }

    private void assertOutlineAndSceneNotFound(StoryWorld world) throws Exception {
        assertNotFound(get("/api/books/{bookId}/outline", world.book().id()), "Book not found");
        assertNotFound(get("/api/scenes/{sceneId}", world.scene().id()), "Scene not found");
    }

    private void assertStructureRoutesAccepted(StoryWorld world) throws Exception {
        String sectionId = mockMvc.perform(post("/api/books/{bookId}/sections", world.book().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "HTTP section", "type", "PART", "sortOrder", 1))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID createdSectionId = objectMapper.readValue(sectionId, BookSectionResponse.class).id();

        mockMvc.perform(patch("/api/sections/{sectionId}", createdSectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "HTTP section renamed"))))
                .andExpect(status().isOk());

        String chapterBody = mockMvc.perform(post("/api/sections/{sectionId}/chapters", createdSectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "HTTP chapter"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID createdChapterId = objectMapper.readValue(chapterBody, ChapterResponse.class).id();

        String sceneBody = mockMvc.perform(post("/api/chapters/{chapterId}/scenes", createdChapterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "HTTP scene", "status", "IDEA"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID createdSceneId = objectMapper.readValue(sceneBody, SceneResponse.class).id();

        mockMvc.perform(patch("/api/scenes/{sceneId}", createdSceneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "HTTP scene renamed"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/chapters/{chapterId}/scenes/reorder", createdChapterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderedIds", List.of(createdSceneId.toString())))))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/scenes/{sceneId}", createdSceneId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/chapters/{chapterId}", createdChapterId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/sections/{sectionId}", createdSectionId)).andExpect(status().isNoContent());
    }

    private void assertStructureRoutesNotFound(StoryWorld world) throws Exception {
        assertNotFound(
                post("/api/books/{bookId}/sections", world.book().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied section"))),
                "Book not found"
        );
        assertNotFound(
                patch("/api/sections/{sectionId}", world.section().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied rename"))),
                "Section not found"
        );
        assertNotFound(
                post("/api/sections/{sectionId}/chapters", world.section().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied chapter"))),
                "Section not found"
        );
        assertNotFound(
                patch("/api/chapters/{chapterId}", world.chapter().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied rename"))),
                "Chapter not found"
        );
        assertNotFound(
                post("/api/chapters/{chapterId}/scenes", world.chapter().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied scene"))),
                "Chapter not found"
        );
        assertNotFound(
                patch("/api/scenes/{sceneId}", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Denied rename"))),
                "Scene not found"
        );
        assertNotFound(delete("/api/scenes/{sceneId}", world.scene().id()), "Scene not found");
    }

    private void assertContentSaveAccepted(StoryWorld world) throws Exception {
        long currentRevision = objectMapper.readValue(
                mockMvc.perform(get("/api/scenes/{sceneId}", world.scene().id()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                SceneResponse.class
        ).contentRevision();

        mockMvc.perform(patch("/api/scenes/{sceneId}/content", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "contentJson", "{\"type\":\"doc\"}",
                                "contentText", "texto autorizado",
                                "source", "MANUAL_SAVE",
                                "expectedContentRevision", currentRevision,
                                "operationId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentText").value("texto autorizado"))
                .andExpect(jsonPath("$.canEditContent").value(true));
    }

    private void assertContentSaveNotFound(StoryWorld world) throws Exception {
        assertNotFound(
                patch("/api/scenes/{sceneId}/content", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "contentJson", "{\"type\":\"doc\"}",
                                "contentText", "texto negado",
                                "source", "MANUAL_SAVE",
                                "expectedContentRevision", 0,
                                "operationId", UUID.randomUUID().toString()
                        ))),
                "Scene not found"
        );
    }

    private void assertNotFound(
            org.springframework.test.web.servlet.RequestBuilder request,
            String expectedMessage
    ) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString(expectedMessage))));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private UUID grantRole(UUID bookId, BookRole role) {
        UUID userId = createMember(
                "Collaborator " + role,
                "http-manuscript-" + role.name().toLowerCase() + "@iwrite.local"
        );
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
