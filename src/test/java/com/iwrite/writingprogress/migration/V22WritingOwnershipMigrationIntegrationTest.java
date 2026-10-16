package com.iwrite.writingprogress.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V22WritingOwnershipMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID LEGACY_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEGACY_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOOK_ID = UUID.fromString("21000000-0000-0000-0000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("21000000-0000-0000-0000-000000000002");
    private static final UUID PROGRESS_ID = UUID.fromString("21000000-0000-0000-0000-000000000003");
    private static final UUID EVENT_ID = UUID.fromString("21000000-0000-0000-0000-000000000004");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("21000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_USER_ID = UUID.fromString("21000000-0000-0000-0000-000000000006");
    private static final UUID SECOND_MEMBERSHIP_ID = UUID.fromString("21000000-0000-0000-0000-000000000007");
    private static final UUID NON_LEGACY_TENANT_ID = UUID.fromString("22000000-0000-0000-0000-000000000001");
    private static final UUID NON_LEGACY_OWNER_ID = UUID.fromString("22000000-0000-0000-0000-000000000002");
    private static final UUID NON_LEGACY_MEMBERSHIP_ID = UUID.fromString("22000000-0000-0000-0000-000000000003");
    private static final UUID NON_LEGACY_SECOND_OWNER_ID = UUID.fromString("22000000-0000-0000-0000-000000000004");
    private static final UUID NON_LEGACY_SECOND_MEMBERSHIP_ID = UUID.fromString("22000000-0000-0000-0000-000000000005");
    private static final UUID NON_LEGACY_BOOK_ID = UUID.fromString("22000000-0000-0000-0000-000000000006");
    private static final UUID NON_LEGACY_SCHEDULE_ID = UUID.fromString("22000000-0000-0000-0000-000000000007");
    private static final UUID NON_LEGACY_PROGRESS_ID = UUID.fromString("22000000-0000-0000-0000-000000000008");
    private static final UUID NON_LEGACY_EVENT_ID = UUID.fromString("22000000-0000-0000-0000-000000000009");
    private static final UUID NON_LEGACY_IDEMPOTENCY_KEY = UUID.fromString("22000000-0000-0000-0000-000000000010");

    @Autowired
    private DataSource dataSource;

    @Test
    void v22BackfillsWritingOwnershipAndEnforcesPersonalProgressAndScheduleConstraints() throws Exception {
        String schema = "phase15_b7a_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("21"));
            insertLegacyWritingRows(schema);
            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals(LEGACY_USER_ID.toString(), scalar(connection, schema,
                        "select user_id::text from book_writing_schedules where id = '" + SCHEDULE_ID + "'"));
                assertEquals(LEGACY_USER_ID.toString(), scalar(connection, schema,
                        "select user_id::text from book_daily_writing_progress where id = '" + PROGRESS_ID + "'"));
                assertEquals(LEGACY_USER_ID.toString(), scalar(connection, schema,
                        "select actor_user_id::text from book_word_count_events where id = '" + EVENT_ID + "'"));

                assertEquals("uk_book_daily_writing_progress_user_book_date", scalar(connection, schema,
                        "select constraint_name from information_schema.table_constraints where table_schema = '" + schema + "' and table_name = 'book_daily_writing_progress' and constraint_name = 'uk_book_daily_writing_progress_user_book_date'"));
                assertEquals("uk_book_writing_schedules_user_book_one_active", scalar(connection, schema,
                        "select indexname from pg_indexes where schemaname = '" + schema + "' and tablename = 'book_writing_schedules' and indexname = 'uk_book_writing_schedules_user_book_one_active'"));
                assertEquals("idx_book_word_count_events_actor_book_created", scalar(connection, schema,
                        "select indexname from pg_indexes where schemaname = '" + schema + "' and tablename = 'book_word_count_events' and indexname = 'idx_book_word_count_events_actor_book_created'"));

                insertSecondUser(connection, schema);

                assertSqlState(connection, schema, "23505",
                        "insert into book_daily_writing_progress (id, user_id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('21000000-0000-0000-0000-000000000008', '" + LEGACY_USER_ID + "', '" + BOOK_ID + "', date '2026-06-01', 0, 1, 1, 0, current_timestamp, current_timestamp)");
                executeUpdate(connection, schema,
                        "insert into book_daily_writing_progress (id, user_id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('21000000-0000-0000-0000-000000000009', '" + SECOND_USER_ID + "', '" + BOOK_ID + "', date '2026-06-01', 0, 1, 1, 0, current_timestamp, current_timestamp)");

                assertSqlState(connection, schema, "23505",
                        "insert into book_writing_schedules (id, user_id, book_id, effective_from, created_at, updated_at) values ('21000000-0000-0000-0000-000000000010', '" + LEGACY_USER_ID + "', '" + BOOK_ID + "', date '2026-06-01', current_timestamp, current_timestamp)");
                executeUpdate(connection, schema,
                        "insert into book_writing_schedules (id, user_id, book_id, effective_from, created_at, updated_at) values ('21000000-0000-0000-0000-000000000011', '" + SECOND_USER_ID + "', '" + BOOK_ID + "', date '2026-06-01', current_timestamp, current_timestamp)");

                assertSqlState(connection, schema, "23505",
                        "insert into book_word_count_events (id, actor_user_id, book_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, progress_date, created_at) values ('21000000-0000-0000-0000-000000000012', '" + SECOND_USER_ID + "', '" + BOOK_ID + "', 'CONTENT_SAVE', 1, 1, '" + IDEMPOTENCY_KEY + "', date '2026-06-01', current_timestamp)");
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v22BackfillsNonLegacyTenantRowsWhenBookTenantHasExactlyOneOwner() throws Exception {
        String schema = "phase15_b7a_single_owner_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("21"));
            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertTenant(connection, schema, NON_LEGACY_TENANT_ID, "Single owner tenant");
                insertUser(connection, schema, NON_LEGACY_OWNER_ID, "Single owner", "single.owner@iwrite.local");
                insertMembership(connection, schema, NON_LEGACY_MEMBERSHIP_ID, NON_LEGACY_TENANT_ID, NON_LEGACY_OWNER_ID);
                insertWritingRows(
                        connection,
                        schema,
                        NON_LEGACY_TENANT_ID,
                        NON_LEGACY_BOOK_ID,
                        NON_LEGACY_SCHEDULE_ID,
                        NON_LEGACY_PROGRESS_ID,
                        NON_LEGACY_EVENT_ID,
                        NON_LEGACY_IDEMPOTENCY_KEY
                );
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertWritingRowsAttributedTo(connection, schema, NON_LEGACY_OWNER_ID, NON_LEGACY_BOOK_ID);
                assertNoNullWritingAttribution(connection, schema);
                assertWritingAttributionBelongsToBookTenant(connection, schema);
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v22FailsForNonLegacyTenantRowsWhenBookTenantHasMultipleOwners() throws Exception {
        String schema = "phase15_b7a_multi_owner_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("21"));
            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertTenant(connection, schema, NON_LEGACY_TENANT_ID, "Ambiguous owner tenant");
                insertUser(connection, schema, NON_LEGACY_OWNER_ID, "First owner", "first.owner@iwrite.local");
                insertUser(connection, schema, NON_LEGACY_SECOND_OWNER_ID, "Second owner", "second.owner@iwrite.local");
                insertMembership(connection, schema, NON_LEGACY_MEMBERSHIP_ID, NON_LEGACY_TENANT_ID, NON_LEGACY_OWNER_ID);
                insertMembership(connection, schema, NON_LEGACY_SECOND_MEMBERSHIP_ID, NON_LEGACY_TENANT_ID, NON_LEGACY_SECOND_OWNER_ID);
                insertWritingRows(
                        connection,
                        schema,
                        NON_LEGACY_TENANT_ID,
                        NON_LEGACY_BOOK_ID,
                        NON_LEGACY_SCHEDULE_ID,
                        NON_LEGACY_PROGRESS_ID,
                        NON_LEGACY_EVENT_ID,
                        NON_LEGACY_IDEMPOTENCY_KEY
                );
            }

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(schema, null));
            assertTrue(exception.getMessage().contains("multiple OWNER memberships"));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v22FailsForNonLegacyTenantRowsWhenBookTenantHasNoOwner() throws Exception {
        String schema = "phase15_b7a_no_owner_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("21"));
            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertTenant(connection, schema, NON_LEGACY_TENANT_ID, "No owner tenant");
                insertWritingRows(
                        connection,
                        schema,
                        NON_LEGACY_TENANT_ID,
                        NON_LEGACY_BOOK_ID,
                        NON_LEGACY_SCHEDULE_ID,
                        NON_LEGACY_PROGRESS_ID,
                        NON_LEGACY_EVENT_ID,
                        NON_LEGACY_IDEMPOTENCY_KEY
                );
            }

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(schema, null));
            assertTrue(exception.getMessage().contains("no OWNER membership"));
        } finally {
            dropSchema(schema);
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

    private void insertLegacyWritingRows(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + BOOK_ID + "', '" + LEGACY_TENANT_ID + "', 'Legacy writing ownership', 'PLANNING', current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into book_writing_schedules (id, book_id, effective_from, created_at, updated_at) values ('" + SCHEDULE_ID + "', '" + BOOK_ID + "', date '2026-06-01', current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into book_writing_schedule_days (schedule_id, day_of_week) values ('" + SCHEDULE_ID + "', 'MONDAY')");
            statement.executeUpdate("insert into book_daily_writing_progress (id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('" + PROGRESS_ID + "', '" + BOOK_ID + "', date '2026-06-01', 0, 10, 10, 0, current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into book_word_count_events (id, book_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, created_at) values ('" + EVENT_ID + "', '" + BOOK_ID + "', 'CONTENT_SAVE', 10, 10, '" + IDEMPOTENCY_KEY + "', current_timestamp)");
        }
    }

    private void insertWritingRows(
            Connection connection,
            String schema,
            UUID tenantId,
            UUID bookId,
            UUID scheduleId,
            UUID progressId,
            UUID eventId,
            UUID idempotencyKey
    ) throws SQLException {
        executeUpdate(connection, schema, "insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + bookId + "', '" + tenantId + "', 'Non-legacy writing ownership', 'PLANNING', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into book_writing_schedules (id, book_id, effective_from, created_at, updated_at) values ('" + scheduleId + "', '" + bookId + "', date '2026-06-01', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into book_writing_schedule_days (schedule_id, day_of_week) values ('" + scheduleId + "', 'MONDAY')");
        executeUpdate(connection, schema, "insert into book_daily_writing_progress (id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('" + progressId + "', '" + bookId + "', date '2026-06-01', 0, 10, 10, 0, current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into book_word_count_events (id, book_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, created_at) values ('" + eventId + "', '" + bookId + "', 'CONTENT_SAVE', 10, 10, '" + idempotencyKey + "', current_timestamp)");
    }

    private void insertTenant(Connection connection, String schema, UUID tenantId, String name) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + tenantId + "', '" + name + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertUser(Connection connection, String schema, UUID userId, String displayName, String email) throws SQLException {
        executeUpdate(connection, schema,
                "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + userId + "', '" + displayName + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema, UUID membershipId, UUID tenantId, UUID userId) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + membershipId + "', '" + tenantId + "', '" + userId + "', 'OWNER', current_timestamp)");
    }

    private void insertSecondUser(Connection connection, String schema) throws SQLException {
        insertUser(connection, schema, SECOND_USER_ID, "Second writer", "second.writer@iwrite.local");
        insertMembership(connection, schema, SECOND_MEMBERSHIP_ID, LEGACY_TENANT_ID, SECOND_USER_ID);
    }

    private void assertWritingRowsAttributedTo(Connection connection, String schema, UUID userId, UUID bookId) throws SQLException {
        assertEquals(userId.toString(), scalar(connection, schema,
                "select user_id::text from book_writing_schedules where book_id = '" + bookId + "'"));
        assertEquals(userId.toString(), scalar(connection, schema,
                "select user_id::text from book_daily_writing_progress where book_id = '" + bookId + "'"));
        assertEquals(userId.toString(), scalar(connection, schema,
                "select actor_user_id::text from book_word_count_events where book_id = '" + bookId + "'"));
    }

    private void assertNoNullWritingAttribution(Connection connection, String schema) throws SQLException {
        assertEquals("0", scalar(connection, schema,
                "select count(*)::text from book_writing_schedules where user_id is null"));
        assertEquals("0", scalar(connection, schema,
                "select count(*)::text from book_daily_writing_progress where user_id is null"));
        assertEquals("0", scalar(connection, schema,
                "select count(*)::text from book_word_count_events where actor_user_id is null"));
    }

    private void assertWritingAttributionBelongsToBookTenant(Connection connection, String schema) throws SQLException {
        assertEquals("0", scalar(connection, schema, """
                select count(*)::text
                from book_writing_schedules schedule
                join books book on book.id = schedule.book_id
                left join tenant_memberships membership
                    on membership.tenant_id = book.tenant_id
                   and membership.user_id = schedule.user_id
                where membership.id is null
                """));
        assertEquals("0", scalar(connection, schema, """
                select count(*)::text
                from book_daily_writing_progress progress
                join books book on book.id = progress.book_id
                left join tenant_memberships membership
                    on membership.tenant_id = book.tenant_id
                   and membership.user_id = progress.user_id
                where membership.id is null
                """));
        assertEquals("0", scalar(connection, schema, """
                select count(*)::text
                from book_word_count_events event
                join books book on book.id = event.book_id
                left join tenant_memberships membership
                    on membership.tenant_id = book.tenant_id
                   and membership.user_id = event.actor_user_id
                where membership.id is null
                """));
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

    private void assertSqlState(Connection connection, String schema, String expectedState, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            SQLException exception = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals(expectedState, exception.getSQLState());
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
