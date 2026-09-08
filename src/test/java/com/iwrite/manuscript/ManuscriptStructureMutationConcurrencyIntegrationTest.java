package com.iwrite.manuscript;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.outline.dto.BookOutlineResponse;
import com.iwrite.outline.service.OutlineService;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SqlInterleaveHook;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency invariant of Manuscript Structure Mutation (#207).
 *
 * <p>{@code ManuscriptContentSaveConcurrencyIntegrationTest} pins that invariant for the canonical
 * content save: prove, take the Book row lock, prove again, so a revocation committing inside that
 * window cannot be straddled. The structure surfaces this partition installed carry the same risk and
 * the same capability, but several of them resolved {@code MUTATE_MANUSCRIPT_STRUCTURE} without ever
 * taking the Book row lock, so nothing stood between the proof and the write that followed it.
 *
 * <p>The existing revocation cases are sequential: they re-enter the surface once the revocation is
 * already visible, so they stay green whether or not the mutation holds a lock. These cases close that
 * gap the way the content-save test does — the revocation commits on an independent connection inside
 * the guard window, and the mutation must be refused with the Manuscript left untouched.
 */
@Import(ManuscriptStructureMutationConcurrencyIntegrationTest.ConcurrencyTestConfiguration.class)
class ManuscriptStructureMutationConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private SqlInterleaveHook sqlInterleaveHook;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutlineService outlineService;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetSeams() {
        currentUserProvider.reset();
        sqlInterleaveHook.disarm();
    }

    /**
     * Every Manuscript Structure Mutation of this partition, against a revocation that commits inside
     * the guard window. Each one must refuse and leave the Manuscript exactly as the Owner left it.
     *
     * <p>Sweeping the whole surface rather than a single operation is deliberate: they share one
     * capability and one risk, so a structure surface that forgets the second proof fails here instead
     * of shipping the gap that only the canonical content save was protected against.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "create Section",
            "rename Section",
            "reorder Sections",
            "delete Section",
            "create Chapter",
            "rename Chapter",
            "reorder Chapters",
            "delete Chapter",
            "create Scene",
            "rename Scene",
            "reorder Scenes",
            "delete Scene"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRevocationCommittedInsideTheGuardWindowRefusesEveryStructureMutation(String surface) {
        StoryWorld world = createStoryWorld("Structure " + surface);
        UUID collaboratorId = createMember("structure-revoked");
        grantLegacyCollaboration(world.book().id(), collaboratorId);
        BookOutlineResponse before = readOutlineAsOwner(world.book().id());

        // Same window as the content save: the revocation commits after the capability proof and
        // immediately before the Book row lock. PostgreSQL takes that lock as "for no key update".
        sqlInterleaveHook.armOn(
                sql -> sql.contains("from books")
                        && (sql.contains("for update") || sql.contains("for no key update")),
                () -> revokeCollaboration(world.book().id(), collaboratorId)
        );
        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, UTC);

        assertThatThrownBy(() -> mutate(surface, world)).isInstanceOf(ResourceNotFoundException.class);

        // A surface that never takes the Book row lock never matches the trigger, so the revocation
        // never happens and the mutation is not actually raced. Without this the case could pass while
        // proving nothing at all.
        assertThat(sqlInterleaveHook.hasFired()).isTrue();

        // A refusal is only worth something if the Manuscript still reads the way the Owner left it.
        currentUserProvider.reset();
        assertThat(readOutlineAsOwner(world.book().id())).isEqualTo(before);
    }

    /**
     * Negative control. The same renames, by the same role, with no revocation armed, must still be
     * applied — otherwise the sweep above could be satisfied by a surface that simply stopped working
     * rather than by authority being re-proven under the lock.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSameStructureMutationsStillSucceedWhenTheCollaborationIsNotRevoked() {
        StoryWorld world = createStoryWorld("Structure intact");
        UUID collaboratorId = createMember("structure-intact");
        grantLegacyCollaboration(world.book().id(), collaboratorId);
        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, UTC);

        mutate("rename Section", world);
        mutate("rename Chapter", world);
        mutate("rename Scene", world);

        currentUserProvider.reset();
        BookOutlineResponse outline = readOutlineAsOwner(world.book().id());
        assertThat(outline.sections()).singleElement().satisfies(section -> {
            assertThat(section.title()).isEqualTo("secao renomeada");
            assertThat(section.chapters()).singleElement().satisfies(chapter -> {
                assertThat(chapter.title()).isEqualTo("capitulo renomeado");
                assertThat(chapter.scenes()).singleElement()
                        .satisfies(scene -> assertThat(scene.title()).isEqualTo("cena renomeada"));
            });
        });
    }

    private void mutate(String surface, StoryWorld world) {
        switch (surface) {
            case "create Section" -> sectionService.create(
                    world.book().id(), new BookSectionRequest("secao nova", SectionType.PART, 1));
            case "rename Section" -> sectionService.update(
                    world.section().id(), new BookSectionUpdateRequest("secao renomeada", null, null));
            case "reorder Sections" -> sectionService.reorder(
                    world.book().id(), new ReorderRequest(List.of(world.section().id())));
            case "delete Section" -> sectionService.delete(world.section().id());
            case "create Chapter" -> chapterService.create(
                    world.section().id(), new ChapterRequest("capitulo novo", null, 1));
            case "rename Chapter" -> chapterService.update(
                    world.chapter().id(), new ChapterUpdateRequest("capitulo renomeado", null, null));
            case "reorder Chapters" -> chapterService.reorder(
                    world.section().id(), new ReorderRequest(List.of(world.chapter().id())));
            case "delete Chapter" -> chapterService.delete(world.chapter().id());
            case "create Scene" -> sceneService.create(
                    world.chapter().id(),
                    new SceneRequest("cena nova", null, SceneStatus.DRAFT, 1, "{\"type\":\"doc\"}", "texto"));
            case "rename Scene" -> sceneService.update(
                    world.scene().id(), new SceneUpdateRequest("cena renomeada", null, null, null));
            case "reorder Scenes" -> sceneService.reorder(
                    world.chapter().id(), new ReorderRequest(List.of(world.scene().id())));
            case "delete Scene" -> sceneService.delete(world.scene().id());
            default -> throw new IllegalArgumentException("Unknown structure surface: " + surface);
        }
    }

    /** The structure the Owner can still see; a refused mutation must leave it identical. */
    private BookOutlineResponse readOutlineAsOwner(UUID bookId) {
        return inNewTransaction(() -> outlineService.getOutline(bookId));
    }

    private UUID createMember(String emailPrefix) {
        return inNewTransaction(() -> {
            User user = new User();
            user.setDisplayName(emailPrefix);
            user.setEmail(emailPrefix + "-" + UUID.randomUUID() + "@iwrite.local");
            user.setTimeZoneId("UTC");
            entityManager.persist(user);

            TenantMembership membership = new TenantMembership();
            membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
            membership.setUser(user);
            membership.setRole(TenantMembershipRole.OWNER);
            entityManager.persist(membership);
            entityManager.flush();
            return user.getId();
        });
    }

    /**
     * The compatibility role is granted the capability outright, so the refusal comes from the
     * revocation itself rather than from a role that this partition already denies.
     */
    private void grantLegacyCollaboration(UUID bookId, UUID userId) {
        executeOnAnIndependentConnection("""
                insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role)
                values ('%s', '%s', '%s', '%s', current_timestamp, '%s', 'LEGACY_COLLABORATOR')
                """.formatted(UUID.randomUUID(), DEFAULT_TENANT_ID, bookId, userId, DEFAULT_USER_ID));
    }

    private void revokeCollaboration(UUID bookId, UUID userId) {
        executeOnAnIndependentConnection(
                "delete from book_collaborators where book_id = '" + bookId + "' and user_id = '" + userId + "'"
        );
    }

    private void executeOnAnIndependentConnection(String sql) {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to commit the concurrent change", exception);
        }
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }

        @Bean
        SqlInterleaveHook sqlInterleaveHook() {
            return new SqlInterleaveHook();
        }

        @Bean
        HibernatePropertiesCustomizer sqlInterleaveHookCustomizer(SqlInterleaveHook hook) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, hook);
        }
    }
}
