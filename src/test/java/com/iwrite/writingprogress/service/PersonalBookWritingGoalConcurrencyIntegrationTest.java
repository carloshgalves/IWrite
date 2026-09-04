package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency invariant of the Personal Book Writing Goal (#206).
 *
 * <p>A goal is created lazily on the first save, so two saves racing for the same User + Book could
 * both observe no goal and both insert one — a unique-key violation, and a 500 on a request the User
 * was authorized to make. It cannot happen because the goal surface mutates through
 * {@code requireCapabilityForUpdate}, which takes the Book row lock before the goal is read, so every
 * goal write for a Book serializes behind it.
 *
 * <p>That guarantee is a property of the guard, not of this service, and nothing else proves it: a
 * refactor that moved the goal surface off the locking guard would silently reintroduce the race. These
 * tests pin it at the seam where it is observable.
 *
 * <p>Serialization alone is not enough. Two tabs that read the same goal and then save divergent
 * choices would both be serialized and both succeed, and whichever ran second would silently replace
 * the other — a lost update of a persistent user choice. Each save therefore quotes the revision it
 * was decided against, compared and advanced under the same Book row lock, so exactly one of the two
 * wins and the other is told its state is stale.
 */
class PersonalBookWritingGoalConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theFirstGoalWriteWaitsForTheBookRowLockInsteadOfRacingAnotherInsert() throws Exception {
        BookResponse book = createBook("Goal lock contention");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockHolder = TestDatabaseInitializer.openDirectConnection()) {
            lockHolder.setAutoCommit(false);
            try (Statement statement = lockHolder.createStatement()) {
                statement.execute("select id from books where id = '" + book.id() + "' for update");
            }

            CompletableFuture<Integer> save = CompletableFuture.supplyAsync(
                    () -> setPersonalDailyTarget(book.id(), 700).dailyTargetWordCount(),
                    executor
            );

            // Blocked on the Book row lock: the goal has not been read yet, so no second writer can be
            // inside the create-if-absent window at the same time.
            assertThatThrownBy(() -> save.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(goalCount(book.id())).isZero();

            lockHolder.commit();

            assertThat(save.get(10, TimeUnit.SECONDS)).isEqualTo(700);
            assertThat(goalCount(book.id())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoDivergentSavesFromTheSameRevisionLeaveOneWinnerAndOneStaleRefusal() throws Exception {
        BookResponse book = createBook("Goal creation race");
        // Both tabs read the same goal before either saved: no goal exists yet, so both quote the
        // revision of a goal that was never saved.
        int sharedRevision = currentPersonalGoalRevision(book.id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);

        try {
            List<CompletableFuture<Integer>> saves = List.of(300, 900).stream()
                    .map(target -> CompletableFuture.supplyAsync(
                            () -> {
                                await(startTogether);
                                return saveOrNullOnConflict(book.id(), target, sharedRevision);
                            },
                            executor
                    ))
                    .toList();

            List<Integer> outcomes = saves.stream().map(this::join).toList();
            List<Integer> accepted = outcomes.stream().filter(java.util.Objects::nonNull).toList();

            // Exactly one of the two divergent choices is persisted, and the other is refused instead
            // of overwriting it. Never two successes, and never a unique-key failure from two inserts.
            assertThat(accepted).hasSize(1);
            assertThat(goalCount(book.id())).isEqualTo(1);
            assertThat(persistedTarget(book.id())).isEqualTo(accepted.get(0));
            assertThat(persistedRevision(book.id())).isEqualTo(sharedRevision + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** The accepted target, or {@code null} when this save was refused as stale. */
    private Integer saveOrNullOnConflict(UUID bookId, int dailyTargetWordCount, int expectedRevision) {
        try {
            return setPersonalDailyTarget(bookId, dailyTargetWordCount, expectedRevision).dailyTargetWordCount();
        } catch (ConflictException conflict) {
            return null;
        }
    }

    private Integer join(CompletableFuture<Integer> save) {
        try {
            return save.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("A concurrent goal save neither succeeded nor conflicted", exception);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to start the concurrent saves together", exception);
        }
    }

    private int goalCount(UUID bookId) throws SQLException {
        return queryInt("select count(*) from book_personal_writing_goals where book_id = '" + bookId + "'");
    }

    private int persistedRevision(UUID bookId) throws SQLException {
        return queryInt("select revision from book_personal_writing_goals where book_id = '" + bookId + "'");
    }

    private int persistedTarget(UUID bookId) throws SQLException {
        return queryInt("select daily_target_word_count from book_personal_writing_goals where book_id = '" + bookId + "'");
    }

    private int queryInt(String sql) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
