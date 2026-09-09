package com.iwrite.manuscript;

import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.sceneversion.entity.SceneVersionSource;
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
import java.util.UUID;
import java.util.function.Supplier;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency invariant of the canonical content save (#207).
 *
 * <p>A canonical save proves Book-scoped eligibility for {@code EDIT_AUTHORED_CONTRIBUTION}, then takes
 * the Book row lock and only afterwards evaluates the resource-scoped predicate that decides whether
 * this access has authority over the Scene text. A revocation can commit inside that window, so the
 * guard proves the eligibility a second time under the lock.
 *
 * <p>Nothing else pins that second proof. The sequential revocation cases re-enter the surface after
 * the revocation is already visible, so they would stay green if the guard trusted its own preflight —
 * and a save decided against an access that no longer exists would still rewrite the Manuscript, past
 * the very predicate this partition installed.
 */
@Import(ManuscriptContentSaveConcurrencyIntegrationTest.ConcurrencyTestConfiguration.class)
class ManuscriptContentSaveConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private SqlInterleaveHook sqlInterleaveHook;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetSeams() {
        currentUserProvider.reset();
        sqlInterleaveHook.disarm();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRevocationCommittedBetweenTheEligibilityProofAndTheBookRowLockStillRefusesTheContentSave() {
        StoryWorld world = createStoryWorld("Content save revocation");
        UUID collaboratorId = createMember("content-save-revoked");
        grantLegacyCollaboration(world.book().id(), collaboratorId);
        SceneResponse before = sceneService.findById(world.scene().id());

        // The revocation commits after the eligibility proof and immediately before the Book row lock,
        // which is the window the mutation guard must re-check instead of trusting its own preflight.
        // PostgreSQL takes the row lock as "for no key update" for a pessimistic write lock.
        sqlInterleaveHook.armOn(
                sql -> sql.contains("from books")
                        && (sql.contains("for update") || sql.contains("for no key update")),
                () -> revokeCollaboration(world.book().id(), collaboratorId)
        );
        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, UTC);

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        "{\"type\":\"doc\"}",
                        "texto salvo depois da revogacao",
                        SceneVersionSource.MANUAL_SAVE,
                        before.contentRevision(),
                        UUID.randomUUID()
                )
        )).isInstanceOf(ResourceNotFoundException.class);
        assertThat(sqlInterleaveHook.hasFired()).isTrue();

        // The refusal is only worth anything if the Manuscript is still the text the Owner had.
        currentUserProvider.reset();
        SceneResponse after = sceneService.findById(world.scene().id());
        assertThat(after.contentText()).isEqualTo(before.contentText());
        assertThat(after.contentRevision()).isEqualTo(before.contentRevision());
        assertThat(after.wordCount()).isEqualTo(before.wordCount());
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
     * The compatibility role is the one that is granted the capability outright, so the save is refused
     * by the revocation alone and not by the resource-scoped predicate that already refuses an Author.
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
