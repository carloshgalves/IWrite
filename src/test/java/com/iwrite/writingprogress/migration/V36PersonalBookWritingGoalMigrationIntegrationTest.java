package com.iwrite.writingprogress.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The daily target stops being shared Book data (#206): a restored legacy database must end with the
 * target owned by each User who was actually writing against it, with no history reinterpreted and no
 * shared column left behind for a stale writer to reach.
 */
class V36PersonalBookWritingGoalMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("36000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("36000000-0000-0000-0000-000000000010");
    private static final UUID COLLABORATOR = UUID.fromString("36000000-0000-0000-0000-000000000011");
    private static final UUID OUTSIDER = UUID.fromString("36000000-0000-0000-0000-000000000012");
    private static final UUID BOOK_WITH_TARGET = UUID.fromString("36000000-0000-0000-0000-000000000101");
    private static final UUID BOOK_WITHOUT_TARGET = UUID.fromString("36000000-0000-0000-0000-000000000102");
    private static final UUID BOOK_WITH_MEANINGLESS_TARGET = UUID.fromString("36000000-0000-0000-0000-000000000103");
    private static final UUID OWNER_HISTORY = UUID.fromString("36000000-0000-0000-0000-000000000201");
    private static final UUID COLLABORATOR_HISTORY = UUID.fromString("36000000-0000-0000-0000-000000000202");

    @Autowired
    private DataSource dataSource;

    @Test
    void v36MovesTheSharedDailyTargetToEachWritersOwnGoalWithoutRewritingHistory() throws Exception {
        String schema = "phase_c3_v36_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("35"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyData(connection, schema);
                assertEquals("1", scalar(connection, schema, sharedColumnCount()));
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // The shared column is gone, so no surface and no stale writer can reach a Book-wide
                // daily target any more.
                assertEquals("0", scalar(connection, schema, sharedColumnCount()));

                // The Book Owner and the existing collaborator each keep the target they were writing
                // against, and nobody else acquires a goal they never chose.
                assertEquals("500", goalOf(connection, schema, OWNER, BOOK_WITH_TARGET));
                assertEquals("500", goalOf(connection, schema, COLLABORATOR, BOOK_WITH_TARGET));
                assertEquals(
                        "0",
                        scalar(connection, schema, "select count(*)::text from book_personal_writing_goals where user_id = '" + OUTSIDER + "'")
                );

                // A Book without a target starts with no goal at all: absence stays absence, never zero.
                assertEquals(
                        "0",
                        scalar(connection, schema, "select count(*)::text from book_personal_writing_goals where book_id = '" + BOOK_WITHOUT_TARGET + "'")
                );

                // A non-positive legacy value was never a target, so it migrates as absence instead of
                // becoming a goal of zero.
                assertEquals(
                        "0",
                        scalar(connection, schema, "select count(*)::text from book_personal_writing_goals where book_id = '" + BOOK_WITH_MEANINGLESS_TARGET + "'")
                );

                // Past progress keeps the per-day target snapshot it already had, including the day the
                // collaborator wrote under a different value. History is preserved, not recomputed.
                assertEquals("300", scalar(connection, schema, "select daily_target_word_count::text from book_daily_writing_progress where id = '" + OWNER_HISTORY + "'"));
                assertEquals("120", scalar(connection, schema, "select daily_target_word_count::text from book_daily_writing_progress where id = '" + COLLABORATOR_HISTORY + "'"));
                assertEquals(
                        OWNER + "|2026-01-10|40",
                        scalar(connection, schema, "select user_id || '|' || progress_date || '|' || productive_word_count_change from book_daily_writing_progress where id = '" + OWNER_HISTORY + "'")
                );

                // The goal is versioned as a whole so a save can say which state it was decided
                // against. It is required and defaults to the revision an unsaved goal reads, so a
                // backfilled row -- which carries the target that was already effective, not a choice
                // made through the new contract -- starts there too.
                assertEquals(
                        "NO|0",
                        scalar(connection, schema, "select is_nullable || '|' || column_default from information_schema.columns "
                                + "where table_schema = current_schema() and table_name = 'book_personal_writing_goals' "
                                + "and column_name = 'revision'")
                );
                assertEquals(
                        "0",
                        scalar(connection, schema, "select revision::text from book_personal_writing_goals "
                                + "where user_id = '" + OWNER + "' and book_id = '" + BOOK_WITH_TARGET + "'")
                );

                // The cascade from books has to find its rows by book_id alone, so that direction is
                // indexed instead of scanning every goal in the installation per Book deletion.
                assertEquals(
                        "1",
                        scalar(connection, schema, "select count(*)::text from pg_indexes "
                                + "where schemaname = current_schema() and tablename = 'book_personal_writing_goals' "
                                + "and indexdef like '%(book_id)'")
                );

                // One goal per User per Book, and the target must be a real target when present.
                assertSqlFails(connection, schema, goalInsert(UUID.randomUUID(), OWNER, BOOK_WITH_TARGET, "700"));
                assertSqlFails(connection, schema, goalInsert(UUID.randomUUID(), OWNER, BOOK_WITHOUT_TARGET, "0"));
                assertSqlFails(connection, schema, goalInsert(UUID.randomUUID(), OWNER, BOOK_WITHOUT_TARGET, "-1"));
                assertSqlFails(connection, schema, goalInsert(UUID.randomUUID(), UUID.randomUUID(), BOOK_WITHOUT_TARGET, "700"));
                assertSqlFails(connection, schema, goalInsert(UUID.randomUUID(), OWNER, UUID.randomUUID(), "700"));

                // A chosen absence of target is representable, and the same User may hold one goal per Book.
                executeUpdate(connection, schema, goalInsert(UUID.randomUUID(), OWNER, BOOK_WITHOUT_TARGET, "null"));
                assertEquals("1", scalar(connection, schema, "select count(*)::text from book_personal_writing_goals where user_id = '" + OWNER + "' and book_id = '" + BOOK_WITHOUT_TARGET + "' and daily_target_word_count is null"));

                // Deleting a Book takes its personal goals with it; they are Book-scoped by definition.
                executeUpdate(connection, schema, "delete from books where id = '" + BOOK_WITHOUT_TARGET + "'");
                assertEquals(
                        "0",
                        scalar(connection, schema, "select count(*)::text from book_personal_writing_goals where book_id = '" + BOOK_WITHOUT_TARGET + "'")
                );
            }
        } finally {
            dropSchema(schema);
        }
    }

    private String sharedColumnCount() {
        return "select count(*)::text from information_schema.columns "
                + "where table_schema = current_schema() and table_name = 'books' and column_name = 'daily_target_word_count'";
    }

    private String goalOf(Connection connection, String schema, UUID userId, UUID bookId) throws SQLException {
        return scalar(connection, schema, "select daily_target_word_count::text from book_personal_writing_goals "
                + "where user_id = '" + userId + "' and book_id = '" + bookId + "'");
    }

    private String goalInsert(UUID id, UUID userId, UUID bookId, String dailyTargetLiteral) {
        return "insert into book_personal_writing_goals (id, user_id, book_id, daily_target_word_count, created_at, updated_at) values ('"
                + id + "', '" + userId + "', '" + bookId + "', " + dailyTargetLiteral + ", current_timestamp, current_timestamp)";
    }

    private void seedLegacyData(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('"
                + TENANT + "', 'Goal tenant', 'UTC', current_timestamp, current_timestamp)");
        insertUser(connection, schema, OWNER, "goal-owner@iwrite.local");
        insertUser(connection, schema, COLLABORATOR, "goal-collaborator@iwrite.local");
        insertUser(connection, schema, OUTSIDER, "goal-outsider@iwrite.local");
        insertMembership(connection, schema, OWNER);
        insertMembership(connection, schema, COLLABORATOR);
        insertMembership(connection, schema, OUTSIDER);
        insertBook(connection, schema, BOOK_WITH_TARGET, "Shared target", "500");
        insertBook(connection, schema, BOOK_WITHOUT_TARGET, "No target", "null");
        insertBook(connection, schema, BOOK_WITH_MEANINGLESS_TARGET, "Zero target", "0");
        executeUpdate(connection, schema, "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                + UUID.randomUUID() + "', '" + TENANT + "', '" + BOOK_WITH_TARGET + "', '" + COLLABORATOR
                + "', current_timestamp, '" + OWNER + "', 'LEGACY_COLLABORATOR')");

        // The Owner and the collaborator each wrote under a different target on different days: those
        // per-day snapshots are the history the migration must leave exactly as it found it.
        insertProgress(connection, schema, OWNER_HISTORY, OWNER, "2026-01-10", 300, 40);
        insertProgress(connection, schema, COLLABORATOR_HISTORY, COLLABORATOR, "2026-01-11", 120, 25);
    }

    private void insertUser(Connection connection, String schema, UUID userId, String email) throws SQLException {
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('"
                + userId + "', '" + email + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema, UUID userId) throws SQLException {
        executeUpdate(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('"
                + UUID.randomUUID() + "', '" + TENANT + "', '" + userId + "', 'OWNER', current_timestamp)");
    }

    private void insertBook(Connection connection, String schema, UUID bookId, String title, String dailyTargetLiteral) throws SQLException {
        executeUpdate(connection, schema, "insert into books (id, tenant_id, owner_user_id, title, status, daily_target_word_count, created_at, updated_at) values ('"
                + bookId + "', '" + TENANT + "', '" + OWNER + "', '" + title + "', 'WRITING', " + dailyTargetLiteral + ", current_timestamp, current_timestamp)");
    }

    private void insertProgress(
            Connection connection,
            String schema,
            UUID id,
            UUID userId,
            String progressDate,
            int dailyTargetWordCount,
            int productiveWordCountChange
    ) throws SQLException {
        executeUpdate(connection, schema, """
                insert into book_daily_writing_progress (
                    id,
                    user_id,
                    book_id,
                    progress_date,
                    daily_target_word_count,
                    starting_manuscript_word_count,
                    ending_manuscript_word_count,
                    productive_word_count_change,
                    manuscript_adjustment_word_count,
                    created_at,
                    updated_at
                ) values ('%s', '%s', '%s', date '%s', %d, 0, %d, %d, 0, current_timestamp, current_timestamp)
                """.formatted(id, userId, BOOK_WITH_TARGET, progressDate, dailyTargetWordCount, productiveWordCountChange, productiveWordCountChange));
    }

    private void assertSqlFails(Connection connection, String schema, String sql) {
        assertThrows(SQLException.class, () -> executeUpdate(connection, schema, sql));
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

    private String scalar(Connection connection, String schema, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private void executeUpdate(Connection connection, String schema, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate(sql);
        }
    }

    private void createSchema(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
