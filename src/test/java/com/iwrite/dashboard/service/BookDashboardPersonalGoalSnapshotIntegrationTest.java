package com.iwrite.dashboard.service;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dashboard projects the Personal Book Writing Goal as one coherent snapshot (#206).
 *
 * <p>The projection carries both the caller's daily target and the revision their next save is
 * decided against, so the two must describe the same state of the same goal. Reading them as two
 * statements would not: under {@code READ COMMITTED} every statement takes its own snapshot, so
 * another tab's first save committing between them lets the response pair a target read before that
 * save with the revision that save produced. A save decided from that response would quote a revision
 * the server accepts and silently replace the choice the response never showed — the lost update the
 * revision exists to refuse.
 *
 * <p>Deterministic, and nothing sleeps for a duration. The dashboard is held open inside its own
 * transaction on a table lock it must cross between the two reads, observed as an ungranted request
 * in {@code pg_locks}; only then does the competing save commit, so it lands inside the window rather
 * than merely near it.
 */
class BookDashboardPersonalGoalSnapshotIntegrationTest extends PostgresIntegrationTest {

    /**
     * Read while building the caller's own writing progress — after the goal is read and before the
     * revision was. Locking it pauses the dashboard exactly inside the window, and the competing save
     * never touches it, so it stays free to commit while the dashboard waits.
     */
    private static final String TABLE_CROSSED_INSIDE_THE_WINDOW = "book_daily_writing_progress";

    @Autowired
    private BookDashboardService dashboardService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDashboardReadRacingTheFirstSaveNeverPairsTheStateItReadWithARevisionItDidNotSee() throws Exception {
        BookResponse book = createBook("Dashboard goal snapshot race");
        // Materializes the caller's routine up front so the dashboard only reads it, and so the
        // competing save has no reason to touch anything the dashboard is waiting on. No goal row is
        // created: this User still has no target and still reads the unsaved revision.
        personalBookWritingGoalService.getGoal(book.id());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection windowHolder = TestDatabaseInitializer.openDirectConnection()) {
            windowHolder.setAutoCommit(false);
            try (Statement statement = windowHolder.createStatement()) {
                statement.execute("lock table " + TABLE_CROSSED_INSIDE_THE_WINDOW + " in access exclusive mode");
            }

            CompletableFuture<BookDashboardResponse> read =
                    CompletableFuture.supplyAsync(() -> dashboardService.getDashboard(book.id()), executor);

            // The dashboard has read the goal and cannot finish the projection yet.
            awaitBlockedOn(TABLE_CROSSED_INSIDE_THE_WINDOW);
            assertThatThrownBy(() -> read.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            // Another tab saves the very first goal of this Book and commits, strictly inside the window.
            setPersonalDailyTarget(book.id(), 1000, 0);
            windowHolder.commit();

            BookDashboardResponse dashboard = read.get(30, TimeUnit.SECONDS);

            // The dashboard read the goal before that save existed, which pins the window.
            assertThat(dashboard.dailyTargetWordCount()).isNull();
            // So it must report the revision of that same absent goal. Reporting the competing save's
            // revision beside its own absent target would be two different goals in one response.
            assertThat(dashboard.myWriting().writingGoalRevision()).isZero();

            // The harm the incoherent pair causes: a save decided from this response must not be able
            // to overwrite the newer choice the response never showed.
            assertThatThrownBy(() -> setPersonalDailyTarget(
                    book.id(),
                    300,
                    dashboard.myWriting().writingGoalRevision()
            )).isInstanceOf(ConflictException.class);
            assertThat(persistedTarget(book.id())).isEqualTo(1000);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits on the state that matters — the dashboard's own ungranted lock request — instead of on the
     * clock, and fails rather than passing silently if the projection never crosses that table.
     */
    private void awaitBlockedOn(String relation) throws SQLException {
        String waitingBackends = """
                select count(*) from pg_locks lock
                join pg_class table_entry on table_entry.oid = lock.relation
                where lock.granted = false
                  and table_entry.relname = '%s'
                """.formatted(relation);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        try (Connection observer = TestDatabaseInitializer.openDirectConnection();
             Statement statement = observer.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(waitingBackends)) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError(
                "The dashboard never queued on the " + relation + " lock, so the goal read and the "
                        + "revision read were never separated by the competing save"
        );
    }

    private int persistedTarget(UUID bookId) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select daily_target_word_count from book_personal_writing_goals where book_id = '" + bookId + "'"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
