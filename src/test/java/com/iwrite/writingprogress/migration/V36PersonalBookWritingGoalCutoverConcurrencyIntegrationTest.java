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
 * <p>The migration reads the shared target and then drops the column that holds it. If the exclusive
 * lock were taken only at the drop, a still-running application version that predates the migration
 * could update the shared target and commit between the two: the backfill would already hold the old
 * value, the drop would wait for that writer and then remove the column, and a change the user was
 * told had succeeded would be gone. V36 therefore locks before it reads.
 *
 * <p>Both tests are deterministic. Nothing sleeps for a duration: the migration is observed through
 * the lock it is actually waiting for in {@code pg_locks}, and the legacy writer commits only after
 * the migration is proven to be blocked.
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
            awaitBlockedOnBooksLock(schema);
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
            // target is: a collaboration that slipped in after the snapshot would leave that User
            // without the target that was effective for them at the cutover.
            legacyWriter.setAutoCommit(false);
            execute(legacyWriter, schema, "lock table books in access exclusive mode");

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> migrate(schema, null), executor);
            awaitBlockedOnBooksLock(schema);
            assertThatThrownBy(() -> migration.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            execute(legacyWriter, schema, "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                    + UUID.randomUUID() + "', '" + TENANT + "', '" + BOOK + "', '" + LATE_COLLABORATOR
                    + "', current_timestamp, '" + OWNER + "', 'LEGACY_COLLABORATOR')");
            legacyWriter.commit();

            migration.get(30, TimeUnit.SECONDS);

            // Committed before the migration's locks were granted, so it is inside the snapshot and the
            // collaborator keeps the target that was effective for them.
            assertThat(goalOf(schema, LATE_COLLABORATOR)).isEqualTo(500);
            assertThat(goalOf(schema, OWNER)).isEqualTo(500);
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    /**
     * Waits on the state that actually matters — an ungranted lock request on {@code books} from
     * another backend — instead of on the clock. It returns as soon as the migration is provably
     * queued, and fails rather than passing silently if it never blocks.
     */
    private void awaitBlockedOnBooksLock(String schema) throws Exception {
        String waitingBackends = """
                select count(*) from pg_locks lock
                join pg_class table_entry on table_entry.oid = lock.relation
                join pg_namespace namespace on namespace.oid = table_entry.relnamespace
                where lock.granted = false
                  and table_entry.relname = 'books'
                  and namespace.nspname = '%s'
                """.formatted(schema);
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
        throw new AssertionError("The migration never queued on the books lock, so it did not freeze the legacy state before reading it");
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
