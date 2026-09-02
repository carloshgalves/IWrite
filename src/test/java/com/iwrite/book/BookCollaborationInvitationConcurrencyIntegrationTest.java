package com.iwrite.book;

import com.iwrite.book.dto.BookCollaborationInvitationRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.service.BookCollaborationInvitationService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency invariants of Book Collaboration Invitations.
 *
 * <p>Public creation is closed in the expand phase (#205), so the duplicate guarantee is proven where
 * it actually lives: the partial unique index over PENDING rows, exercised by two inserts racing on
 * independent connections. That is what a reopened creation in #213 will rely on, and it does not
 * depend on the service being callable today.
 */
class BookCollaborationInvitationConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookCollaborationInvitationService invitationService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEquivalentPendingInsertsProduceExactlyOnePendingInvitation() {
        BookResponse book = createBook("Concurrent invitation duplicates");

        List<Object> outcomes = runConcurrently(
                () -> insertPendingInvitation(book.id(), "race@example.com"),
                () -> insertPendingInvitation(book.id(), "race@example.com")
        );

        // The second insert blocks on the first and fails once it commits, so the slot is never doubled.
        assertThat(outcomes).filteredOn(UUID.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Exception.class::isInstance).hasSize(1);
        assertThat(pendingCount(book.id(), "race@example.com")).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPendingInsertsForDifferentRecipientsBothSucceed() {
        BookResponse book = createBook("Concurrent invitation distinct recipients");

        List<Object> outcomes = runConcurrently(
                () -> insertPendingInvitation(book.id(), "first@example.com"),
                () -> insertPendingInvitation(book.id(), "second@example.com")
        );

        assertThat(outcomes).filteredOn(UUID.class::isInstance).hasSize(2);
        assertThat(pendingCount(book.id(), "first@example.com")).isEqualTo(1L);
        assertThat(pendingCount(book.id(), "second@example.com")).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreationAttemptsPersistNoNewLegacyInvitation() {
        BookResponse book = createBook("Concurrent invitation creation closed");
        BookCollaborationInvitationRequest request =
                new BookCollaborationInvitationRequest("closed@example.com", "COLLABORATOR", null);

        List<Object> outcomes = runConcurrently(
                () -> invitationService.create(book.id(), request),
                () -> invitationService.create(book.id(), request)
        );

        assertThat(outcomes).allMatch(BadRequestException.class::isInstance);
        assertThat(pendingCount(book.id(), "closed@example.com")).isZero();
    }

    /**
     * Inserts one PENDING legacy invitation on its own connection and commits, so two of these race on
     * the partial unique index exactly as two independent requests would.
     */
    private UUID insertPendingInvitation(UUID bookId, String recipientEmail) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into book_collaboration_invitations (
                        id, tenant_id, book_id, inviter_user_id, recipient_email, requested_role,
                        token_hash, status, expires_at, created_at, updated_at, version
                    )
                    values (?, ?, ?, ?, ?, 'COLLABORATOR', ?, 'PENDING',
                            now() + interval '7 days', now(), now(), 0)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, DEFAULT_TENANT_ID);
                statement.setObject(3, bookId);
                statement.setObject(4, DEFAULT_USER_ID);
                statement.setString(5, recipientEmail);
                statement.setString(6, randomTokenHash());
                statement.executeUpdate();
            }
            connection.commit();
        }
        return id;
    }

    private static String randomTokenHash() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private long pendingCount(UUID bookId, String recipientEmail) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from book_collaboration_invitations
                        where book_id = :bookId
                          and recipient_email = :recipientEmail
                          and status = 'PENDING'
                        """)
                .setParameter("bookId", bookId)
                .setParameter("recipientEmail", recipientEmail)
                .getSingleResult();
        return count.longValue();
    }

    /**
     * Runs both callables in parallel and returns each outcome as either the
     * result or the thrown exception, so callers can assert on the mix.
     */
    private List<Object> runConcurrently(Callable<?> first, Callable<?> second) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<Object> firstFuture = CompletableFuture.supplyAsync(() -> outcomeOf(start, first), executor);
            CompletableFuture<Object> secondFuture = CompletableFuture.supplyAsync(() -> outcomeOf(start, second), executor);
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            outcomes.add(firstFuture.orTimeout(10, TimeUnit.SECONDS).join());
            outcomes.add(secondFuture.orTimeout(10, TimeUnit.SECONDS).join());
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private Object outcomeOf(CountDownLatch start, Callable<?> callable) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent workers");
            }
            return callable.call();
        } catch (Exception exception) {
            return exception;
        }
    }
}
