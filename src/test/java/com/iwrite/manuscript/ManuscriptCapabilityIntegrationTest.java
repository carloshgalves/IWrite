package com.iwrite.manuscript;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.outline.service.OutlineService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.sceneversion.entity.SceneVersionSource;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Book Capability Policy applied to the living Manuscript, the outline and the Section/Chapter/
 * Scene hierarchy (#207).
 *
 * <p>Three rules are proven together at the service seam, because they only make sense together:
 * reading the Manuscript needs {@code READ_MANUSCRIPT}, every Manuscript Structure Mutation needs the
 * Owner-only {@code MUTATE_MANUSCRIPT_STRUCTURE} of this partition, and a canonical content save needs
 * more than the contextual {@code EDIT_AUTHORED_CONTRIBUTION} that Book scope can grant.
 *
 * <p>Every denial — a Reader, a role without the capability, a Workspace member with no Book Role,
 * another Workspace — is the same non-enumerable not-found, so no identifier reveals what it names.
 */
@Import(ManuscriptCapabilityIntegrationTest.CurrentUserTestConfiguration.class)
class ManuscriptCapabilityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OutlineService outlineService;

    @Autowired
    private BookCollaboratorRepository collaboratorRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void theBookOwnerKeepsTheWholeManuscriptSurface() {
        StoryWorld world = createStoryWorld("Owner manuscript");

        assertReadsTheManuscript(world);
        assertRestructuresTheManuscript(world);
        assertSavesSceneContent(world);
    }

    @ParameterizedTest
    @EnumSource(BookRole.class)
    void eachBookRoleReachesOnlyTheManuscriptSurfaceItsCapabilitiesAllow(BookRole role) {
        StoryWorld world = createStoryWorld("Manuscript matrix " + role);
        UUID collaboratorId = grantRole(world.book().id(), role);

        switchTo(collaboratorId);

        if (canReadManuscript(role)) {
            assertReadsTheManuscript(world);
        } else {
            assertCannotReadTheManuscript(world);
        }

        if (canMutateStructure(role)) {
            assertRestructuresTheManuscript(world);
        } else {
            assertCannotRestructureTheManuscript(world);
        }

        if (canSaveSceneContent(role)) {
            assertSavesSceneContent(world);
        } else {
            assertCannotSaveSceneContent(world);
        }
    }

    @Test
    void anAuthorIsEligibleForContentEditingWithoutHoldingAuthorityOverTheSceneYet() {
        StoryWorld world = createStoryWorld("Author eligibility");
        UUID authorId = grantRole(world.book().id(), BookRole.AUTHOR);

        switchTo(authorId);

        // #145 makes the Author eligible; the resource-scoped predicate is what still refuses, because
        // no Authored Contribution exists to attribute the text to before #184.
        assertThat(bookAccessCapabilities(world.book().id())).contains("EDIT_AUTHORED_CONTRIBUTION");
        assertCannotSaveSceneContent(world);
        // Eligibility must not have leaked into the structure surface either.
        assertCannotRestructureTheManuscript(world);
    }

    @Test
    void aSceneOrChapterIdentifierNeverRevealsABookTheCallerCannotReach() {
        StoryWorld world = createStoryWorld("Indirect reach");
        UUID strangerId = createMember(DEFAULT_TENANT_ID, "Stranger", "manuscript-stranger@iwrite.local");
        ForeignIdentity foreign = createForeignIdentity();

        switchTo(strangerId);
        assertSectionChapterAndSceneAreNotFound(world);

        switchTo(foreign.userId(), foreign.tenantId());
        assertSectionChapterAndSceneAreNotFound(world);
    }

    @Test
    void revokingAccessMidSessionClosesTheManuscriptSurfaceImmediately() {
        StoryWorld world = createStoryWorld("Revoked manuscript");
        UUID collaboratorId = grantRole(world.book().id(), BookRole.LEGACY_COLLABORATOR);

        switchTo(collaboratorId);
        assertReadsTheManuscript(world);

        currentUserProvider.reset();
        collaboratorRepository.delete(
                collaboratorRepository.findByBook_IdAndTenant_IdAndUser_Id(world.book().id(), DEFAULT_TENANT_ID, collaboratorId)
                        .orElseThrow()
        );
        collaboratorRepository.flush();

        switchTo(collaboratorId);
        assertCannotReadTheManuscript(world);
        assertCannotRestructureTheManuscript(world);
        assertCannotSaveSceneContent(world);
    }

    @Test
    void anAuthorizedContentSaveStillAdvancesTheRevisionAndStaysIdempotent() {
        StoryWorld world = createStoryWorld("Content regression");
        UUID operationId = UUID.randomUUID();

        SceneResponse saved = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "uma duas tres", SceneVersionSource.MANUAL_SAVE, 0L, operationId)
        );

        assertThat(saved.contentRevision()).isEqualTo(1L);
        assertThat(saved.wordCount()).isEqualTo(3);

        SceneResponse retried = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "uma duas tres", SceneVersionSource.MANUAL_SAVE, 0L, operationId)
        );

        assertThat(retried.contentRevision()).isEqualTo(1L);
    }

    // Mirrors BookCapabilityPolicy for the capabilities this partition applies.
    private static boolean canReadManuscript(BookRole role) {
        return role == BookRole.AUTHOR || role == BookRole.EDITOR || role == BookRole.LEGACY_COLLABORATOR;
    }

    private static boolean canMutateStructure(BookRole role) {
        return role == BookRole.LEGACY_COLLABORATOR;
    }

    /**
     * Only the legacy compatibility role is granted the capability outright. The Owner and an Author are
     * merely eligible, and before #184 only the Owner satisfies the resource-scoped predicate.
     */
    private static boolean canSaveSceneContent(BookRole role) {
        return role == BookRole.LEGACY_COLLABORATOR;
    }

    private void assertReadsTheManuscript(StoryWorld world) {
        assertThat(outlineService.getOutline(world.book().id()).sections()).isNotEmpty();
        assertThat(sceneService.findById(world.scene().id()).id()).isEqualTo(world.scene().id());
        assertThat(sectionService.getSection(world.section().id()).getId()).isEqualTo(world.section().id());
        assertThat(chapterService.getChapter(world.chapter().id()).getId()).isEqualTo(world.chapter().id());
    }

    private void assertCannotReadTheManuscript(StoryWorld world) {
        assertNotFound(() -> outlineService.getOutline(world.book().id()));
        assertSectionChapterAndSceneAreNotFound(world);
    }

    private void assertSectionChapterAndSceneAreNotFound(StoryWorld world) {
        assertNotFound(() -> sceneService.findById(world.scene().id()));
        assertNotFound(() -> sectionService.getSection(world.section().id()));
        assertNotFound(() -> chapterService.getChapter(world.chapter().id()));
    }

    private void assertRestructuresTheManuscript(StoryWorld world) {
        BookSectionResponse section = sectionService.create(
                world.book().id(),
                new BookSectionRequest("Structure " + UUID.randomUUID(), SectionType.PART, 1)
        );
        assertThat(sectionService.update(section.id(), new BookSectionUpdateRequest("Renamed", null, null)).title())
                .isEqualTo("Renamed");

        ChapterResponse chapter = chapterService.create(section.id(), new ChapterRequest("Chapter", null, 0));
        assertThat(chapterService.update(chapter.id(), new ChapterUpdateRequest("Renamed chapter", null, null)).title())
                .isEqualTo("Renamed chapter");

        SceneResponse scene = sceneService.create(
                chapter.id(),
                new SceneRequest("Scene", null, SceneStatus.IDEA, 0, "{\"type\":\"doc\"}", "")
        );
        assertThat(sceneService.update(scene.id(), new SceneUpdateRequest("Renamed scene", null, null, null)).title())
                .isEqualTo("Renamed scene");

        chapterService.reorder(section.id(), new ReorderRequest(List.of(chapter.id())));
        sceneService.reorder(chapter.id(), new ReorderRequest(List.of(scene.id())));
        sceneService.delete(scene.id());
        chapterService.delete(chapter.id());
        sectionService.delete(section.id());
    }

    private void assertCannotRestructureTheManuscript(StoryWorld world) {
        assertNotFound(() -> sectionService.create(
                world.book().id(),
                new BookSectionRequest("Denied", SectionType.PART, 1)
        ));
        assertNotFound(() -> sectionService.update(
                world.section().id(),
                new BookSectionUpdateRequest("Denied", null, null)
        ));
        assertNotFound(() -> sectionService.reorder(world.book().id(), new ReorderRequest(List.of(world.section().id()))));
        assertNotFound(() -> sectionService.delete(world.section().id()));

        assertNotFound(() -> chapterService.create(world.section().id(), new ChapterRequest("Denied", null, 1)));
        assertNotFound(() -> chapterService.update(
                world.chapter().id(),
                new ChapterUpdateRequest("Denied", null, null)
        ));
        assertNotFound(() -> chapterService.reorder(world.section().id(), new ReorderRequest(List.of(world.chapter().id()))));
        assertNotFound(() -> chapterService.delete(world.chapter().id()));

        assertNotFound(() -> sceneService.create(
                world.chapter().id(),
                new SceneRequest("Denied", null, SceneStatus.IDEA, 1, "{\"type\":\"doc\"}", "")
        ));
        assertNotFound(() -> sceneService.update(
                world.scene().id(),
                new SceneUpdateRequest("Denied", null, null, null)
        ));
        assertNotFound(() -> sceneService.reorder(world.chapter().id(), new ReorderRequest(List.of(world.scene().id()))));
        assertNotFound(() -> sceneService.delete(world.scene().id()));
    }

    private void assertSavesSceneContent(StoryWorld world) {
        SceneResponse saved = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        "{\"type\":\"doc\"}",
                        "conteudo autorizado",
                        SceneVersionSource.MANUAL_SAVE,
                        sceneService.findById(world.scene().id()).contentRevision(),
                        UUID.randomUUID()
                )
        );
        assertThat(saved.contentText()).isEqualTo("conteudo autorizado");
    }

    private void assertCannotSaveSceneContent(StoryWorld world) {
        assertNotFound(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "conteudo negado", SceneVersionSource.MANUAL_SAVE, 0L, UUID.randomUUID())
        ));
    }

    private List<String> bookAccessCapabilities(UUID bookId) {
        return bookService.findById(bookId).contextualCapabilities().stream().map(Enum::name).toList();
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(ResourceNotFoundException.class);
    }

    private UUID grantRole(UUID bookId, BookRole role) {
        UUID userId = createMember(
                DEFAULT_TENANT_ID,
                "Collaborator " + role,
                "manuscript-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@iwrite.local"
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
        tenant.setName("Foreign manuscript workspace");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "manuscript-foreign-" + UUID.randomUUID() + "@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private void switchTo(UUID userId) {
        switchTo(userId, DEFAULT_TENANT_ID);
    }

    private void switchTo(UUID userId, UUID tenantId) {
        currentUserProvider.switchTo(userId, tenantId, ZoneId.of("UTC"));
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
