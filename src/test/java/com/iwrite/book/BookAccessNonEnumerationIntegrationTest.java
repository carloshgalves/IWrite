package com.iwrite.book;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SqlStatementRecorder;
import com.iwrite.support.SwitchableCurrentUserProvider;
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

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Supplier;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Non-enumeration invariants of the Book authorization boundary (#145/#205).
 *
 * <p>Returning the same {@code Book not found} is not enough. A Workspace member holding a candidate
 * UUID must not be able to tell "this Book does not exist" from "this Book exists and I have no
 * access", and the statements a denial issues are as observable as its response: a denial that first
 * loads the Book and only then looks up a role leaks the distinction through query shape and latency.
 *
 * <p>So every denial of the boundary must issue the same statements, and a denied mutation must never
 * touch the Book row lock.
 */
@Import(BookAccessNonEnumerationIntegrationTest.RecordingTestConfiguration.class)
class BookAccessNonEnumerationIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private BookAccessService bookAccessService;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private SqlStatementRecorder sqlStatementRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetSeams() {
        currentUserProvider.reset();
        sqlStatementRecorder.reset();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anUnknownBookAndAnInaccessibleBookAreDeniedByTheSameStatements() {
        BookResponse book = createBook("Non-enumeration read probe");
        UUID strangerId = createMember("non-enumeration-read");
        currentUserProvider.switchTo(strangerId, DEFAULT_TENANT_ID, UTC);

        List<String> unknownBook = statementsOfDenial(() -> bookAccessService.resolveAccessContext(UUID.randomUUID()));
        List<String> inaccessibleBook = statementsOfDenial(() -> bookAccessService.resolveAccessContext(book.id()));

        assertThat(unknownBook).isNotEmpty();
        assertThat(inaccessibleBook).isEqualTo(unknownBook);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDeniedMutationIssuesTheSameStatementsAndNeverTakesTheBookRowLock() {
        BookResponse book = createBook("Non-enumeration mutation probe");
        UUID strangerId = createMember("non-enumeration-mutation");
        currentUserProvider.switchTo(strangerId, DEFAULT_TENANT_ID, UTC);

        List<String> unknownBook = statementsOfDenial(
                () -> bookAccessService.requireCapabilityForUpdate(UUID.randomUUID(), BookCapability.DELETE_BOOK)
        );
        List<String> inaccessibleBook = statementsOfDenial(
                () -> bookAccessService.requireCapabilityForUpdate(book.id(), BookCapability.DELETE_BOOK)
        );

        assertThat(unknownBook).isNotEmpty();
        assertThat(inaccessibleBook).isEqualTo(unknownBook);
        assertThat(inaccessibleBook).noneMatch(BookAccessNonEnumerationIntegrationTest::takesARowLock);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAuthorizedMutationStillProvesAccessBeforeAndAfterTakingTheBookRowLock() {
        BookResponse book = createBook("Non-enumeration owner probe");

        List<String> authorized = sqlStatementRecorder.recordStatementsOf(
                () -> bookAccessService.requireCapabilityForUpdate(book.id(), BookCapability.DELETE_BOOK)
        );

        // authorize -> lock -> re-authorize: the lock sits between two identical access proofs, so a
        // revocation committed while this transaction waited for the lock is still observed.
        List<String> lockStatements = authorized.stream().filter(BookAccessNonEnumerationIntegrationTest::takesARowLock).toList();
        assertThat(lockStatements).hasSize(1);
        int lockIndex = authorized.indexOf(lockStatements.get(0));
        assertThat(lockIndex).isGreaterThan(0);
        assertThat(authorized).hasSizeGreaterThan(lockIndex + 1);
        assertThat(authorized.get(lockIndex + 1)).isEqualTo(authorized.get(lockIndex - 1));
    }

    private static boolean takesARowLock(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        return normalized.contains("for update") || normalized.contains("for no key update");
    }

    private List<String> statementsOfDenial(Supplier<?> call) {
        return sqlStatementRecorder.recordStatementsOf(
                () -> assertThatThrownBy(call::get).isInstanceOf(ResourceNotFoundException.class)
        );
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

    private <T> T inNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }

        @Bean
        SqlStatementRecorder sqlStatementRecorder() {
            return new SqlStatementRecorder();
        }

        @Bean
        HibernatePropertiesCustomizer sqlStatementRecorderCustomizer(SqlStatementRecorder recorder) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, recorder);
        }
    }
}
