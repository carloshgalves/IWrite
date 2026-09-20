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

class V37BookContributorProgressOriginMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOOK = UUID.fromString("37000000-0000-0000-0000-000000000001");
    private static final UUID SECTION = UUID.fromString("37000000-0000-0000-0000-000000000002");
    private static final UUID CHAPTER = UUID.fromString("37000000-0000-0000-0000-000000000003");
    private static final UUID SCENE = UUID.fromString("37000000-0000-0000-0000-000000000004");
    private static final UUID EVENT = UUID.fromString("37000000-0000-0000-0000-000000000005");

    @Autowired
    private DataSource dataSource;

    @Test
    void v37BackfillsHistoricalDateWithoutInventingALegacyChapterOrigin() throws Exception {
        String schema = "phase_211_v37_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("36"));
            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedV36State(connection, schema);
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals(
                        "2026-06-23||",
                        scalar(connection, schema, "select progress_date || '|' || coalesce(original_chapter_id::text, '') "
                                + "|| '|' || coalesce(chapter_title_snapshot, '') "
                                + "from book_word_count_events where id = '" + EVENT + "'")
                );
                assertEquals(
                        "NO",
                        scalar(connection, schema, "select is_nullable from information_schema.columns "
                                + "where table_schema = current_schema() and table_name = 'book_word_count_events' "
                                + "and column_name = 'progress_date'")
                );
                assertEquals(
                        "book_id,progress_date,actor_user_id",
                        scalar(connection, schema, "select string_agg(attribute.attname, ',' order by key.ordinality) "
                                + "from pg_class index_class "
                                + "join pg_namespace namespace on namespace.oid = index_class.relnamespace "
                                + "join pg_index index_definition on index_definition.indexrelid = index_class.oid "
                                + "join unnest(index_definition.indkey) with ordinality key(attnum, ordinality) on true "
                                + "join pg_attribute attribute on attribute.attrelid = index_definition.indrelid and attribute.attnum = key.attnum "
                                + "where namespace.nspname = current_schema() and index_class.relname = 'idx_book_word_count_events_book_progress_actor'")
                );

                executeUpdate(connection, schema, "delete from scenes where id = '" + SCENE + "'");
                assertEquals(
                        "|" + SCENE + "||Scene before deletion|",
                        scalar(connection, schema, "select coalesce(scene_id::text, '') || '|' || original_scene_id || '|' "
                                + "|| coalesce(original_chapter_id::text, '') || '|' || scene_title_snapshot || '|' "
                                + "|| coalesce(chapter_title_snapshot, '') "
                                + "from book_word_count_events where id = '" + EVENT + "'")
                );
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v37UsesStableUtcDateWhenLegacyDailyEvidenceIsUnavailable() throws Exception {
        String schema = "phase_211_v37_missing_evidence_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("36"));
            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedV36State(connection, schema);
                executeUpdate(connection, schema, "delete from book_daily_writing_progress where book_id = '" + BOOK + "'");
                executeUpdate(connection, schema, "update book_word_count_events set created_at = timestamptz '2026-06-24 01:30:00+00' where id = '" + EVENT + "'");
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals(
                        "2026-06-24",
                        scalar(connection, schema, "select progress_date::text from book_word_count_events where id = '" + EVENT + "'")
                );
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void seedV36State(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema, "insert into books (id, tenant_id, owner_user_id, title, status, created_at, updated_at) values ('"
                + BOOK + "', '" + TENANT + "', '" + USER + "', 'Contributor history', 'WRITING', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into sections (id, book_id, title, type, sort_order, created_at, updated_at) values ('"
                + SECTION + "', '" + BOOK + "', 'Part', 'PART', 0, current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into chapters (id, book_id, section_id, title, sort_order, created_at, updated_at) values ('"
                + CHAPTER + "', '" + BOOK + "', '" + SECTION + "', 'Chapter before deletion', 0, current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into scenes (id, book_id, chapter_id, title, status, sort_order, word_count, content_revision, created_at, updated_at) values ('"
                + SCENE + "', '" + BOOK + "', '" + CHAPTER + "', 'Scene before deletion', 'DRAFT', 0, 5, 1, current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into book_daily_writing_progress (id, user_id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('"
                + UUID.randomUUID() + "', '" + USER + "', '" + BOOK + "', date '2026-06-23', 0, 5, 5, 0, timestamptz '2026-06-24 03:00:00+00', timestamptz '2026-06-24 04:00:00+00')");
        executeUpdate(connection, schema, "insert into book_word_count_events (id, book_id, scene_id, actor_user_id, original_scene_id, scene_title_snapshot, event_type, productive_word_delta, manuscript_word_delta, idempotency_key, request_fingerprint, created_at) values ('"
                + EVENT + "', '" + BOOK + "', '" + SCENE + "', '" + USER + "', '" + SCENE + "', 'Scene before deletion', 'CONTENT_SAVE', 5, 5, '"
                + UUID.randomUUID() + "', 'synthetic-v37-migration-fixture', timestamptz '2026-06-24 03:30:00+00')");
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
}
