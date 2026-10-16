package com.iwrite.writingprogress.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V23WordCountEventRequestFingerprintMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("23000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("23000000-0000-0000-0000-000000000002");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("23000000-0000-0000-0000-000000000003");
    private static final UUID BOOK_ID = UUID.fromString("23000000-0000-0000-0000-000000000004");
    private static final UUID LEGACY_EVENT_ID = UUID.fromString("23000000-0000-0000-0000-000000000005");
    private static final UUID LEGACY_IDEMPOTENCY_KEY = UUID.fromString("23000000-0000-0000-0000-000000000006");
    private static final UUID NEW_EVENT_ID = UUID.fromString("23000000-0000-0000-0000-000000000007");
    private static final UUID NEW_IDEMPOTENCY_KEY = UUID.fromString("23000000-0000-0000-0000-000000000008");
    private static final UUID DUPLICATE_EVENT_ID = UUID.fromString("23000000-0000-0000-0000-000000000009");
    private static final UUID SCENE_ID = UUID.fromString("23000000-0000-0000-0000-000000000010");

    @Autowired
    private DataSource dataSource;

    @Test
    void v23AddsNullableRequestFingerprintWithoutChangingExistingEventIdentity() throws Exception {
        String schema = "phase15_b7c_v23_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("22"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertFalse(columnExists(connection, schema, "request_fingerprint"));
                insertTenant(connection, schema);
                insertUser(connection, schema);
                insertMembership(connection, schema);
                insertBook(connection, schema);
                insertLegacyEvent(connection, schema);
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("character varying", scalar(connection, schema, """
                        select data_type
                        from information_schema.columns
                        where table_schema = '%s'
                          and table_name = 'book_word_count_events'
                          and column_name = 'request_fingerprint'
                        """.formatted(schema)));
                assertEquals("64", scalar(connection, schema, """
                        select character_maximum_length::text
                        from information_schema.columns
                        where table_schema = '%s'
                          and table_name = 'book_word_count_events'
                          and column_name = 'request_fingerprint'
                        """.formatted(schema)));
                assertEquals("1", scalar(connection, schema,
                        "select count(*)::text from book_word_count_events where id = '" + LEGACY_EVENT_ID + "'"));
                assertEquals(null, scalar(connection, schema,
                        "select request_fingerprint from book_word_count_events where id = '" + LEGACY_EVENT_ID + "'"));
                assertEquals(BOOK_ID.toString(), scalar(connection, schema,
                        "select book_id::text from book_word_count_events where id = '" + LEGACY_EVENT_ID + "'"));
                assertEquals(USER_ID.toString(), scalar(connection, schema,
                        "select actor_user_id::text from book_word_count_events where id = '" + LEGACY_EVENT_ID + "'"));
                assertEquals("CONTENT_SAVE", scalar(connection, schema,
                        "select event_type from book_word_count_events where id = '" + LEGACY_EVENT_ID + "'"));

                String fingerprint = WordCountRequestFingerprint.contentSave(
                        USER_ID,
                        BOOK_ID,
                        SCENE_ID,
                        0L,
                        null,
                        "{\"type\":\"doc\"}",
                        "new event words"
                );
                insertNewEvent(connection, schema, fingerprint);

                String persistedFingerprint = scalar(connection, schema,
                        "select request_fingerprint from book_word_count_events where id = '" + NEW_EVENT_ID + "'");
                assertEquals(fingerprint, persistedFingerprint);
                assertEquals(64, persistedFingerprint.length());
                assertTrue(persistedFingerprint.matches("[0-9a-f]{64}"));

                assertSqlState(connection, schema, "23505",
                        "insert into book_word_count_events (id, actor_user_id, book_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, request_fingerprint, progress_date, created_at) values ('"
                                + DUPLICATE_EVENT_ID + "', '" + USER_ID + "', '" + BOOK_ID + "', 'CONTENT_SAVE', 1, 1, '"
                                + NEW_IDEMPOTENCY_KEY + "', '" + "f".repeat(64) + "', current_date, current_timestamp)");
            }
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

    private void insertTenant(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_ID + "', 'V23 tenant', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertUser(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + USER_ID + "', 'V23 user', 'v23.user@iwrite.local', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + MEMBERSHIP_ID + "', '" + TENANT_ID + "', '" + USER_ID + "', 'OWNER', current_timestamp)");
    }

    private void insertBook(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + BOOK_ID + "', '" + TENANT_ID + "', 'V23 migration book', 'PLANNING', current_timestamp, current_timestamp)");
    }

    private void insertLegacyEvent(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into book_word_count_events (id, actor_user_id, book_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, created_at) values ('"
                        + LEGACY_EVENT_ID + "', '" + USER_ID + "', '" + BOOK_ID + "', 'CONTENT_SAVE', 10, 10, '" + LEGACY_IDEMPOTENCY_KEY + "', current_timestamp)");
    }

    private void insertNewEvent(Connection connection, String schema, String fingerprint) throws SQLException {
        executeUpdate(connection, schema,
                "insert into book_word_count_events (id, actor_user_id, book_id, scene_id, original_scene_id, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, content_revision_before, content_revision_after, request_fingerprint, progress_date, created_at) values ('"
                        + NEW_EVENT_ID + "', '" + USER_ID + "', '" + BOOK_ID + "', null, '" + SCENE_ID + "', 'CONTENT_SAVE', 3, 3, '" + NEW_IDEMPOTENCY_KEY + "', 0, 1, '" + fingerprint + "', current_date, current_timestamp)");
    }

    private boolean columnExists(Connection connection, String schema, String columnName) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (var resultSet = statement.executeQuery("""
                    select 1
                    from information_schema.columns
                    where table_schema = '%s'
                      and table_name = 'book_word_count_events'
                      and column_name = '%s'
                    """.formatted(schema, columnName))) {
                return resultSet.next();
            }
        }
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
