package com.iwrite.book.migration;

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
 * Expand phase of the Book Role foundation (#205): a restored legacy database must gain an
 * explicit role on every existing collaboration without losing or elevating effective access.
 */
class V35BookCollaboratorRoleMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("35000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("35000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_A = UUID.fromString("35000000-0000-0000-0000-000000000010");
    private static final UUID COLLABORATOR_A = UUID.fromString("35000000-0000-0000-0000-000000000011");
    private static final UUID COLLABORATOR_A_SECOND = UUID.fromString("35000000-0000-0000-0000-000000000012");
    private static final UUID OWNER_B = UUID.fromString("35000000-0000-0000-0000-000000000020");
    private static final UUID COLLABORATOR_B = UUID.fromString("35000000-0000-0000-0000-000000000021");
    private static final UUID BOOK_A = UUID.fromString("35000000-0000-0000-0000-000000000101");
    private static final UUID BOOK_B = UUID.fromString("35000000-0000-0000-0000-000000000102");
    private static final UUID COLLABORATION_A = UUID.fromString("35000000-0000-0000-0000-000000000201");
    private static final UUID COLLABORATION_A_SECOND = UUID.fromString("35000000-0000-0000-0000-000000000202");
    private static final UUID COLLABORATION_B = UUID.fromString("35000000-0000-0000-0000-000000000203");
    private static final UUID LEGACY_INVITATION = UUID.fromString("35000000-0000-0000-0000-000000000301");

    private static final String LEGACY_CREATED_AT = "2026-01-05 10:15:00+00";

    @Autowired
    private DataSource dataSource;

    @Test
    void v35BackfillsLegacyCollaboratorRoleAndClosesTheRoleCatalogFromV34() throws Exception {
        String schema = "phase_c3_v35_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("34"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyData(connection, schema);
                assertEquals("0", scalar(connection, schema, roleColumnCount()));
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("1", scalar(connection, schema, roleColumnCount()));

                // Every legacy collaboration is backfilled to the non-assignable compatibility role.
                assertEquals("3", scalar(connection, schema, "select count(*)::text from book_collaborators where role = 'LEGACY_COLLABORATOR'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from book_collaborators where role <> 'LEGACY_COLLABORATOR'"));

                // Relational data and timestamps of the legacy rows are preserved untouched.
                assertEquals(
                        TENANT_A + "|" + BOOK_A + "|" + COLLABORATOR_A + "|" + OWNER_A,
                        scalar(connection, schema, "select tenant_id || '|' || book_id || '|' || user_id || '|' || created_by_user_id from book_collaborators where id = '" + COLLABORATION_A + "'")
                );
                assertEquals(
                        "1",
                        scalar(connection, schema, "select count(*)::text from book_collaborators where id = '" + COLLABORATION_A + "' and created_at = timestamptz '" + LEGACY_CREATED_AT + "'")
                );

                // Effective access is neither reduced nor elevated by the migration.
                assertHasAccess(connection, schema, BOOK_A, TENANT_A, OWNER_A);
                assertHasAccess(connection, schema, BOOK_A, TENANT_A, COLLABORATOR_A);
                assertHasAccess(connection, schema, BOOK_A, TENANT_A, COLLABORATOR_A_SECOND);
                assertHasAccess(connection, schema, BOOK_B, TENANT_B, COLLABORATOR_B);
                assertNoAccess(connection, schema, BOOK_A, TENANT_A, COLLABORATOR_B);
                assertNoAccess(connection, schema, BOOK_B, TENANT_B, COLLABORATOR_A);

                // The catalog of persistable roles is closed by the database itself.
                for (String assignableRole : new String[]{"AUTHOR", "EDITOR", "READER"}) {
                    UUID id = UUID.randomUUID();
                    executeUpdate(connection, schema, collaboratorInsert(id, TENANT_B, BOOK_B, OWNER_A, OWNER_B, "'" + assignableRole + "'"));
                    executeUpdate(connection, schema, "delete from book_collaborators where id = '" + id + "'");
                }
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_B, BOOK_B, OWNER_A, OWNER_B, "'COLLABORATOR'"));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_B, BOOK_B, OWNER_A, OWNER_B, "'REVIEWER'"));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_B, BOOK_B, OWNER_A, OWNER_B, "'author'"));
                assertSqlFails(connection, schema, "update book_collaborators set role = null where id = '" + COLLABORATION_A + "'");

                // Both role constraints end the migration validated. They are added as NOT VALID so the
                // ACCESS EXCLUSIVE lock only covers the catalog change, and the existing rows are then
                // proven by a separate VALIDATE that does not block concurrent reads and writes.
                assertConstraintValidated(connection, schema, "book_collaborators", "chk_book_collaborators_role");
                assertConstraintValidated(connection, schema, "book_collaboration_invitations", "chk_book_collaboration_invitations_role");

                // Rollout compatibility: an application version that predates the role still inserts a
                // usable row, and it lands on the legacy surface instead of a new policy.
                UUID rolloutId = UUID.randomUUID();
                executeUpdate(connection, schema, legacyCollaboratorInsertWithoutRole(rolloutId, TENANT_B, BOOK_B, OWNER_A, OWNER_B));
                assertEquals("LEGACY_COLLABORATOR", scalar(connection, schema, "select role from book_collaborators where id = '" + rolloutId + "'"));
                executeUpdate(connection, schema, "delete from book_collaborators where id = '" + rolloutId + "'");

                // Uniqueness and tenant/book integrity survive the column addition.
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_A, COLLABORATOR_A, OWNER_A, "'AUTHOR'"));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_B, COLLABORATOR_A, OWNER_A, "'AUTHOR'"));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_A, COLLABORATOR_B, OWNER_A, "'AUTHOR'"));

                // A stored legacy invitation stays exactly as it was: preserved, never inferred into a grant.
                assertEquals(
                        "COLLABORATOR|PENDING",
                        scalar(connection, schema, "select requested_role || '|' || status from book_collaboration_invitations where id = '" + LEGACY_INVITATION + "'")
                );
                assertEquals(
                        "0",
                        scalar(connection, schema, "select count(*)::text from book_collaborators where book_id = '" + BOOK_A + "' and user_id = '" + COLLABORATOR_B + "'")
                );

                // The invitation catalog opens for the assignable roles and stays closed otherwise.
                UUID assignableInvitation = UUID.randomUUID();
                executeUpdate(connection, schema, invitationInsert(assignableInvitation, TENANT_A, BOOK_A, OWNER_A, "editor@iwrite.local", tokenHash(2), "AUTHOR"));
                executeUpdate(connection, schema, "delete from book_collaboration_invitations where id = '" + assignableInvitation + "'");
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, OWNER_A, "reviewer@iwrite.local", tokenHash(3), "REVIEWER"));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, OWNER_A, "legacy@iwrite.local", tokenHash(4), "LEGACY_COLLABORATOR"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private String roleColumnCount() {
        return "select count(*)::text from information_schema.columns "
                + "where table_schema = current_schema() and table_name = 'book_collaborators' and column_name = 'role'";
    }

    private void seedLegacyData(Connection connection, String schema) throws SQLException {
        insertTenant(connection, schema, TENANT_A, "Tenant A");
        insertTenant(connection, schema, TENANT_B, "Tenant B");
        insertUser(connection, schema, OWNER_A, "owner-a@iwrite.local");
        insertUser(connection, schema, COLLABORATOR_A, "collab-a@iwrite.local");
        insertUser(connection, schema, COLLABORATOR_A_SECOND, "collab-a2@iwrite.local");
        insertUser(connection, schema, OWNER_B, "owner-b@iwrite.local");
        insertUser(connection, schema, COLLABORATOR_B, "collab-b@iwrite.local");
        insertMembership(connection, schema, TENANT_A, OWNER_A);
        insertMembership(connection, schema, TENANT_A, COLLABORATOR_A);
        insertMembership(connection, schema, TENANT_A, COLLABORATOR_A_SECOND);
        insertMembership(connection, schema, TENANT_B, OWNER_B);
        insertMembership(connection, schema, TENANT_B, COLLABORATOR_B);
        insertMembership(connection, schema, TENANT_B, OWNER_A);
        insertBook(connection, schema, BOOK_A, TENANT_A, OWNER_A, "Legacy A");
        insertBook(connection, schema, BOOK_B, TENANT_B, OWNER_B, "Legacy B");
        executeUpdate(connection, schema, legacyCollaboratorInsertWithoutRole(COLLABORATION_A, TENANT_A, BOOK_A, COLLABORATOR_A, OWNER_A));
        executeUpdate(connection, schema, legacyCollaboratorInsertWithoutRole(COLLABORATION_A_SECOND, TENANT_A, BOOK_A, COLLABORATOR_A_SECOND, OWNER_A));
        executeUpdate(connection, schema, legacyCollaboratorInsertWithoutRole(COLLABORATION_B, TENANT_B, BOOK_B, COLLABORATOR_B, OWNER_B));
        executeUpdate(connection, schema, invitationInsert(LEGACY_INVITATION, TENANT_A, BOOK_A, OWNER_A, "collab-b@iwrite.local", tokenHash(1), "COLLABORATOR"));
    }

    private void insertTenant(Connection connection, String schema, UUID tenantId, String name) throws SQLException {
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('"
                + tenantId + "', '" + name + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertUser(Connection connection, String schema, UUID userId, String email) throws SQLException {
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('"
                + userId + "', '" + email + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema, UUID tenantId, UUID userId) throws SQLException {
        executeUpdate(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + userId + "', 'OWNER', current_timestamp)");
    }

    private void insertBook(Connection connection, String schema, UUID bookId, UUID tenantId, UUID ownerId, String title) throws SQLException {
        executeUpdate(connection, schema, "insert into books (id, tenant_id, owner_user_id, title, status, created_at, updated_at) values ('"
                + bookId + "', '" + tenantId + "', '" + ownerId + "', '" + title + "', 'PLANNING', current_timestamp, current_timestamp)");
    }

    private String legacyCollaboratorInsertWithoutRole(UUID id, UUID tenantId, UUID bookId, UUID userId, UUID createdByUserId) {
        return "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id) values ('"
                + id + "', '" + tenantId + "', '" + bookId + "', '" + userId + "', timestamptz '" + LEGACY_CREATED_AT + "', '" + createdByUserId + "')";
    }

    private String collaboratorInsert(UUID id, UUID tenantId, UUID bookId, UUID userId, UUID createdByUserId, String roleLiteral) {
        return "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id, role) values ('"
                + id + "', '" + tenantId + "', '" + bookId + "', '" + userId + "', current_timestamp, '" + createdByUserId + "', " + roleLiteral + ")";
    }

    private String invitationInsert(UUID id, UUID tenantId, UUID bookId, UUID inviterId, String email, String tokenHash, String requestedRole) {
        return "insert into book_collaboration_invitations ("
                + "id, tenant_id, book_id, inviter_user_id, recipient_email, requested_role, token_hash, status, expires_at, created_at, updated_at, version) values ('"
                + id + "', '" + tenantId + "', '" + bookId + "', '" + inviterId + "', '" + email + "', '" + requestedRole + "', '" + tokenHash
                + "', 'PENDING', current_timestamp + interval '7 days', current_timestamp, current_timestamp, 0)";
    }

    private String tokenHash(int seed) {
        return String.format("%064d", seed);
    }

    private void assertHasAccess(Connection connection, String schema, UUID bookId, UUID tenantId, UUID userId) throws SQLException {
        assertEquals("1", accessCount(connection, schema, bookId, tenantId, userId));
    }

    private void assertNoAccess(Connection connection, String schema, UUID bookId, UUID tenantId, UUID userId) throws SQLException {
        assertEquals("0", accessCount(connection, schema, bookId, tenantId, userId));
    }

    private String accessCount(Connection connection, String schema, UUID bookId, UUID tenantId, UUID userId) throws SQLException {
        return scalar(connection, schema, """
                select count(*)::text
                from books book
                where book.id = '%s'
                  and book.tenant_id = '%s'
                  and (
                        book.owner_user_id = '%s'
                        or exists (
                            select 1
                            from book_collaborators collaborator
                            where collaborator.book_id = book.id
                              and collaborator.tenant_id = '%s'
                              and collaborator.user_id = '%s'
                        )
                  )
                """.formatted(bookId, tenantId, userId, tenantId, userId));
    }

    private void assertConstraintValidated(Connection connection, String schema, String table, String constraint) throws SQLException {
        assertEquals("true", scalar(connection, schema, """
                select convalidated::text
                from pg_constraint
                where conname = '%s'
                  and conrelid = (current_schema() || '.%s')::regclass
                """.formatted(constraint, table)));
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
