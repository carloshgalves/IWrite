package com.iwrite.writingprogress.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
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
 * Cutover concurrency of V36 (#206).
 *
 * <p>The migration reads two pieces of legacy state and then drops the column one of them lives in.
 * If the locks were taken only at the drop, a still-running application version that predates the
 * migration could commit inside the window and lose work either way: an update of the shared target
 * would be discarded by a backfill that already held the old value, and a collaboration grant would
 * leave that User with no goal at all despite the shared target still being in effect for them when
 * the grant was acknowledged. V36 therefore locks {@code books} and {@code book_collaborators} before
 * it reads either of them.
 *
 * <p>Freezing legacy state is not enough on its own: locks strong enough to stop a writer but weak
 * enough to let one in halfway can leave the cutover deadlocked against a mutation that started after
 * them, which kills the deploy the migration exists to perform. The last test pins that window.
 *
 * <p>Every test is deterministic and nothing sleeps for a duration: the migration is observed through
 * the lock it is actually waiting for in {@code pg_locks}, and the legacy writer commits only after
 * the migration is proven blocked. Each lock keeps a case that fails if that lock stops being taken —
 * an update for {@code books}, and a revocation for {@code book_collaborators}, which is the only
 * legacy write that reaches that table without reading {@code books} on the way.
 */
class V36PersonalBookWritingGoalCutoverConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("36c00000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("36c00000-0000-0000-0000-000000000010");
    private static final UUID LATE_COLLABORATOR = UUID.fromString("36c00000-0000-0000-0000-000000000011");
    private static final UUID BOOK = UUID.fromString("36c00000-0000-0000-0000-000000000101");

    @Autowired
    private DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aLegacyTargetCommittedBeforeTheCutoverIsMigratedInsteadOfBeingDropped() throws Exception {
        String schema = newSchema();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection legacyWriter = TestDatabaseInitializer.openDirectConnection()) {
            migrate(schema, MigrationVersion.fromVersion("35"));
            seedBookWithSharedTarget(schema, 500);

            // A pre-V36 instance updates the shared target and holds the transaction open, exactly as
            // a request that is about to be acknowledged would.
            legacyWriter.setAutoCommit(false);
            execute(legacyWriter, schema, "update books set daily_target_word_count = 900 where id = '" + BOOK + "'");

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> migrate(schema, null), executor);

            // The migration cannot reach the backfill: it is waiting for the table lock it takes before
            // reading anything, so it has not snapshotted the value this writer is about to replace.
            awaitBlockedOnLock(schema, "books");
            assertThatThrownBy(() -> migration.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            legacyWriter.commit();
            migration.get(30, TimeUnit.SECONDS);

            // The value the legacy writer committed is the one that became the Owner's personal goal.
            // Reading 500 here would be the lost update: an acknowledged change discarded by the cutover.
            assertThat(ownerGoal(schema)).isEqualTo(900);
            assertThat(sharedColumnExists(schema)).isFalse();
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCollaborationCannotBeCommittedBetweenTheSnapshotAndTheContractStep() throws Exception {
        String schema = newSchema();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection legacyWriter = TestDatabaseInitializer.openDirectConnection()) {
            migrate(schema, MigrationVersion.fromVersion("35"));
            seedBookWithSharedTarget(schema, 500);
            insertUser(schema, LATE_COLLABORATOR, "late-collaborator@iwrite.local");
            insertMembership(schema, LATE_COLLABORATOR);

            // The collaborator set decides who receives a goal, so it is frozen for the same reason the
            // target is. A pre-V36 instance grants access and holds the transaction open, exactly as a
            // request that is about to be acknowledged would.
            legacyWriter.setAutoCommit(false);
            execute(legacyWriter, schema, "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                    + UUID.randomUUID() + "', '" + TENANT + "', '" + BOOK + "', '" + LATE_COLLABORATOR
                    + "', current_timestamp, '" + OWNER + "', 'LEGACY_COLLABORATOR')");

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> migrate(schema, null), executor);

            // The migration cannot reach the backfill: it is waiting for the books lock it takes before
            // reading anything. A grant is caught there rather than at the book_collaborators lock
            // because inserting a collaboration checks its foreign key against books, and that read
            // conflicts with ACCESS EXCLUSIVE. The revoke below is the write that reaches
            // book_collaborators without touching books at all.
            awaitBlockedOnLock(schema, "books");
            assertThatThrownBy(() -> migration.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            legacyWriter.commit();
            migration.get(30, TimeUnit.SECONDS);

            // Committed before the migration's locks were granted, so the grant is inside the snapshot
            // and the collaborator keeps the target that was effective for them. Reading no goal here
            // would be the lost collaborator: an acknowledged grant that the cutover left with no target.
            assertThat(goalOf(schema, LATE_COLLABORATOR)).isEqualTo(500);
            assertThat(goalOf(schema, OWNER)).isEqualTo(500);
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRevocationCannotBeCommittedBetweenTheSnapshotAndTheContractStep() throws Exception {
        String schema = newSchema();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection legacyWriter = TestDatabaseInitializer.openDirectConnection()) {
            migrate(schema, MigrationVersion.fromVersion("35"));
            seedBookWithSharedTarget(schema, 500);
            insertUser(schema, LATE_COLLABORATOR, "revoked-collaborator@iwrite.local");
            insertMembership(schema, LATE_COLLABORATOR);
            UUID collaboration = insertCollaboration(schema, LATE_COLLABORATOR);

            // Removing a collaboration is the one legacy write that reaches book_collaborators without
            // reading books: a child row needs no foreign key check against its parent. It therefore
            // holds ROW EXCLUSIVE on book_collaborators and no lock at all on books, so nothing but the
            // explicit book_collaborators lock can make the migration wait for it.
            legacyWriter.setAutoCommit(false);
            execute(legacyWriter, schema, "delete from book_collaborators where id = '" + collaboration + "'");

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> migrate(schema, null), executor);

            awaitBlockedOnLock(schema, "book_collaborators");
            assertThatThrownBy(() -> migration.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            legacyWriter.commit();
            migration.get(30, TimeUnit.SECONDS);

            // The revocation was acknowledged before the cutover, so the backfill reads the set it left
            // behind. Reading a goal for the revoked User here would mean the migration handed personal
            // state to someone the Book had already stopped sharing with.
            assertThat(hasGoal(schema, LATE_COLLABORATOR)).isFalse();
            assertThat(goalOf(schema, OWNER)).isEqualTo(500);
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aGrantStartedAfterTheCutoverLocksQueuesInsteadOfDeadlockingAgainstThem() throws Exception {
        String schema = newSchema();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection lockHolder = TestDatabaseInitializer.openDirectConnection()) {
            migrate(schema, MigrationVersion.fromVersion("35"));
            seedBookWithSharedTarget(schema, 500);
            insertUser(schema, LATE_COLLABORATOR, "late-grant@iwrite.local");
            insertMembership(schema, LATE_COLLABORATOR);

            // Parks the migration with both of its table locks already granted, which is the window the
            // two tests above never reach: the goal table it creates next declares a foreign key to
            // users, and that needs a lock this open update conflicts with. Nothing else in the
            // migration touches users, so releasing this releases exactly the cutover.
            lockHolder.setAutoCommit(false);
            execute(lockHolder, schema, "update users set display_name = 'held' where id = '" + OWNER + "'");

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> migrate(schema, null), executor);
            awaitBlockedOnLock(schema, "users");

            // Only now does the grant begin, so it is provably a mutation that started after the cutover
            // already held its locks. It takes the Book row first and writes the collaboration second,
            // the order the application itself uses.
            CompletableFuture<Void> grant = CompletableFuture.runAsync(() -> grantCollaboration(schema), executor);
            awaitWaitingBackends(schema, 2);

            lockHolder.commit();

            // Either side aborting with "deadlock detected" fails here, and for the migration that is a
            // deploy dying on the release it exists to perform. A cutover may make a late mutation wait;
            // it may not make one impossible to complete.
            migration.get(60, TimeUnit.SECONDS);
            grant.get(60, TimeUnit.SECONDS);

            assertThat(ownerGoal(schema)).isEqualTo(500);
            assertThat(sharedColumnExists(schema)).isFalse();
            assertThat(collaboratorCount(schema)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    /**
     * Waits on the state that actually matters — an ungranted lock request on {@code relation} from
     * another backend — instead of on the clock. It returns as soon as the migration is provably
     * queued, and fails rather than passing silently if it never blocks.
     */
    private void awaitBlockedOnLock(String schema, String relation) throws Exception {
        String waitingBackends = """
                select count(*) from pg_locks lock
                join pg_class table_entry on table_entry.oid = lock.relation
                join pg_namespace namespace on namespace.oid = table_entry.relnamespace
                where lock.granted = false
                  and table_entry.relname = '%s'
                  and namespace.nspname = '%s'
                """.formatted(relation, schema);
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
        throw new AssertionError("The migration never queued on the " + relation + " lock, so it did not freeze that legacy state before reading it");
    }

    /**
     * Runs a collaboration grant the way the application does: guard the Book row first, then write the
     * collaboration. Raw SQL rather than the service, because this has to run against the V35 schema a
     * pre-V36 instance still sees.
     */
    private void grantCollaboration(String schema) {
        try (Connection grantor = TestDatabaseInitializer.openDirectConnection()) {
            grantor.setAutoCommit(false);
            execute(grantor, schema, "select id from books where id = '" + BOOK + "' for update");
            execute(grantor, schema, "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                    + UUID.randomUUID() + "', '" + TENANT + "', '" + BOOK + "', '" + LATE_COLLABORATOR
                    + "', current_timestamp, '" + OWNER + "', 'LEGACY_COLLABORATOR')");
            grantor.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("The late grant could not complete", exception);
        }
    }

    /**
     * Waits until {@code expected} backends are queued on a lock somewhere in this schema, so the test
     * proceeds on the interleaving it needs rather than on elapsed time.
     */
    private void awaitWaitingBackends(String schema, int expected) throws Exception {
        String waitingBackends = """
                select count(*) from pg_locks lock
                join pg_class table_entry on table_entry.oid = lock.relation
                join pg_namespace namespace on namespace.oid = table_entry.relnamespace
                where lock.granted = false
                  and namespace.nspname = '%s'
                """.formatted(schema);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        try (Connection observer = TestDatabaseInitializer.openDirectConnection();
             Statement statement = observer.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(waitingBackends)) {
                    resultSet.next();
                    if (resultSet.getInt(1) >= expected) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Only fewer than " + expected + " backends ever queued, so the late mutation never entered the cutover window");
    }

    private UUID insertCollaboration(String schema, UUID userId) throws SQLException {
        UUID collaboration = UUID.randomUUID();
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            execute(connection, schema, "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                    + collaboration + "', '" + TENANT + "', '" + BOOK + "', '" + userId
                    + "', current_timestamp, '" + OWNER + "', 'LEGACY_COLLABORATOR')");
        }
        return collaboration;
    }

    private boolean hasGoal(String schema, UUID userId) throws SQLException {
        return queryInt(schema, "select count(*) from book_personal_writing_goals where user_id = '" + userId + "'") > 0;
    }

    private int collaboratorCount(String schema) throws SQLException {
        return queryInt(schema, "select count(*) from book_collaborators where book_id = '" + BOOK + "'");
    }

    private String newSchema() throws SQLException {
        String schema = "phase_c3_v36_cutover_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            execute(connection, null, "create schema " + schema);
        }
        return schema;
    }

    private void seedBookWithSharedTarget(String schema, int dailyTarget) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            execute(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('"
                    + TENANT + "', 'Cutover tenant', 'UTC', current_timestamp, current_timestamp)");
            insertUser(connection, schema, OWNER, "cutover-owner@iwrite.local");
            insertMembership(connection, schema, OWNER);
            execute(connection, schema, "insert into books (id, tenant_id, owner_user_id, title, status, daily_target_word_count, created_at, updated_at) values ('"
                    + BOOK + "', '" + TENANT + "', '" + OWNER + "', 'Cutover book', 'WRITING', " + dailyTarget + ", current_timestamp, current_timestamp)");
        }
    }

    private void insertUser(String schema, UUID userId, String email) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            insertUser(connection, schema, userId, email);
        }
    }

    private void insertUser(Connection connection, String schema, UUID userId, String email) throws SQLException {
        execute(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('"
                + userId + "', '" + email + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(String schema, UUID userId) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            insertMembership(connection, schema, userId);
        }
    }

    private void insertMembership(Connection connection, String schema, UUID userId) throws SQLException {
        execute(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('"
                + UUID.randomUUID() + "', '" + TENANT + "', '" + userId + "', 'OWNER', current_timestamp)");
    }

    private int ownerGoal(String schema) throws SQLException {
        return goalOf(schema, OWNER);
    }

    private int goalOf(String schema, UUID userId) throws SQLException {
        return queryInt(schema, "select daily_target_word_count from book_personal_writing_goals where user_id = '" + userId + "'");
    }

    private boolean sharedColumnExists(String schema) throws SQLException {
        return queryInt(schema, "select count(*) from information_schema.columns where table_schema = '" + schema
                + "' and table_name = 'books' and column_name = 'daily_target_word_count'") > 0;
    }

    private int queryInt(String schema, String sql) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void execute(Connection connection, String schema, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (schema != null) {
                statement.execute("set search_path to " + schema);
            }
            statement.execute(sql);
        }
    }

    private void migrate(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
            execute(connection, null, "drop schema if exists " + schema + " cascade");
        }
    }
}
