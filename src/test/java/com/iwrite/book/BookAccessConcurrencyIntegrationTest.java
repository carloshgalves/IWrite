package com.iwrite.book;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.authorization.BookRelationship;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.exception.ResourceNotFoundException;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency invariants of the Book authorization boundary (#205).
 *
 * <p>Book access is derived from persistence while other transactions grant and revoke it. Deriving it
 * from two statements that see different snapshots, or taking the Book row lock before proving access,
 * are both observable defects: the first breaks a request that should have succeeded, the second lets
 * an unauthorized caller contend with authorized mutations.
 */
@Import(BookAccessConcurrencyIntegrationTest.ConcurrencyTestConfiguration.class)
class BookAccessConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private BookAccessService bookAccessService;

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
    void aCollaborationCommittedWhileTheLibraryIsListedNeverListsABookWithoutItsRole() {
        BookResponse book = createBook("Library listing race");
        UUID collaboratorId = createMember("listing-race");

        // The grant commits from an independent connection immediately before the statement that lists
        // accessible Books, so under READ COMMITTED that statement is the first one to observe it.
        sqlInterleaveHook.armOn(
                sql -> sql.contains("from books") && sql.contains("order by"),
                () -> grantLegacyCollaboration(book.id(), collaboratorId)
        );
        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, UTC);

        List<BookResponse> library = bookService.findAll();

        assertThat(sqlInterleaveHook.hasFired()).isTrue();
        BookResponse listed = library.stream()
                .filter(entry -> entry.id().equals(book.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The accessible Book was not listed"));
        assertThat(listed.relationship()).isEqualTo(BookRelationship.COLLABORATOR);
        assertThat(listed.role()).isEqualTo(BookRole.LEGACY_COLLABORATOR);
        assertThat(listed.capabilities()).contains(BookCapability.READ_MANUSCRIPT);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCallerWithoutAccessIsDeniedWithoutQueueingOnTheBookRowLock() throws Exception {
        BookResponse book = createBook("Lock contention");
        UUID strangerId = createMember("lock-stranger");
        currentUserProvider.switchTo(strangerId, DEFAULT_TENANT_ID, UTC);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockHolder = TestDatabaseInitializer.openDirectConnection()) {
            lockHolder.setAutoCommit(false);
            try (Statement statement = lockHolder.createStatement()) {
                statement.execute("select id from books where id = '" + book.id() + "' for update");
            }

            CompletableFuture<Object> denial = CompletableFuture.supplyAsync(
                    () -> outcomeOf(() -> bookAccessService.requireCapabilityForUpdate(book.id(), BookCapability.DELETE_BOOK)),
                    executor
            );

            // A caller that Book scope never authorizes must be denied by the access proof alone, so it
            // never waits behind — nor delays — a mutation that is authorized.
            Object outcome = denial.get(10, TimeUnit.SECONDS);

            assertThat(outcome).isInstanceOf(ResourceNotFoundException.class);
            lockHolder.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRevocationCommittedBetweenTheAccessProofAndTheBookRowLockStillDeniesTheMutation() {
        BookResponse book = createBook("Revoked while locking");
        UUID collaboratorId = createMember("revoked-while-locking");
        grantLegacyCollaboration(book.id(), collaboratorId);

        // The revocation commits after the access proof and immediately before the row lock, which is
        // exactly the window a mutation guard must re-check instead of trusting its own preflight.
        // PostgreSQL takes the row lock as "for no key update" for a pessimistic write lock.
        sqlInterleaveHook.armOn(
                sql -> sql.contains("for update") || sql.contains("for no key update"),
                () -> revokeCollaboration(book.id(), collaboratorId)
        );
        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, UTC);

        assertThatThrownBy(() -> bookAccessService.requireCapabilityForUpdate(
                book.id(),
                BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
        )).isInstanceOf(ResourceNotFoundException.class);
        assertThat(sqlInterleaveHook.hasFired()).isTrue();
    }

    private Object outcomeOf(Callable<?> callable) {
        try {
            return callable.call();
        } catch (Exception exception) {
            return exception;
        }
    }

    private UUID createMember(String emailPrefix) {
        return inNewTransaction(() -> {
            String email = emailPrefix + "-" + UUID.randomUUID() + "@iwrite.local";
            User user = new User();
            user.setDisplayName(emailPrefix);
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
        });
    }

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
