package com.iwrite.manuscript;

import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SqlInterleaveHook;
import com.iwrite.support.TestDatabaseInitializer;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-lock load invariant of the Manuscript Structure Mutations (#207).
 *
 * <p>{@code ManuscriptLockOrderingConcurrencyIntegrationTest} pinned the order these surfaces take
 * their two row locks in, and {@code ManuscriptStructureMutationConcurrencyIntegrationTest} pinned that
 * authority is re-proven under the Book row lock. Both leave a third thing unproven: <em>when</em> the
 * row that is about to be written is read.
 *
 * <p>{@code ChapterService} and {@code BookSectionService} loaded the entity before queueing for the
 * Book row lock and then handed that same managed instance to the mutation. A reorder committing inside
 * that window is invisible to the loaded instance, and the rename's flush writes the whole row back —
 * silently restoring the {@code sortOrder} the reorder had just changed. The lock order can be correct
 * and a write-back of pre-lock state still discards newer state, which is the concurrency guarantee of
 * #145.
 *
 * <p>The interleaving is deterministic rather than timed: the reorder commits on an independent
 * connection immediately before the Book row lock statement, which is exactly the window between the
 * pre-lock load and the lock. {@link SqlInterleaveHook#hasFired()} is asserted so a case that stops
 * matching fails loudly instead of passing without the race it claims to reproduce.
 */
@Import(ManuscriptStructurePreLockLoadConcurrencyIntegrationTest.PreLockLoadTestConfiguration.class)
class ManuscriptStructurePreLockLoadConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String RENAMED_CHAPTER = "capitulo renomeado sob disputa";
    private static final String RENAMED_SECTION = "secao renomeada sob disputa";

    @Autowired
    private SqlInterleaveHook sqlInterleaveHook;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void resetSeams() {
        sqlInterleaveHook.disarm();
    }

    /**
     * A Chapter rename racing a Chapter reorder that commits inside the guard window. Both writers are
     * authorized and touch different fields, so both effects must survive: the rename must not carry the
     * {@code sortOrder} it read before it queued for the Book row back into the Manuscript.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aChapterRenameDoesNotWriteBackTheSortOrderItReadBeforeTheBookLock() {
        StoryWorld world = createStoryWorld("Chapter pre-lock load");
        ChapterResponse first = world.chapter();
        ChapterResponse second = createChapter(world.section(), "Chapter racing sibling");
        assertThat(chapterSortOrder(first.id())).isZero();

        // The window: after the mutation has already read the Chapter, before it holds the Book row.
        armReorderOnTheBookLock(() -> swapSortOrder("chapters", first.id(), second.id()));

        chapterService.update(first.id(), new ChapterUpdateRequest(RENAMED_CHAPTER, null, null));

        assertRaced();
        assertThat(chapterTitle(first.id())).isEqualTo(RENAMED_CHAPTER);
        assertThat(chapterSortOrder(first.id()))
                .describedAs("the rename wrote back the sortOrder it read before the Book lock")
                .isEqualTo(1);
        assertThat(chapterSortOrder(second.id())).isZero();
    }

    /** The same invariant for the Section surface, which resolves its entity the same way. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aSectionRenameDoesNotWriteBackTheSortOrderItReadBeforeTheBookLock() {
        StoryWorld world = createStoryWorld("Section pre-lock load");
        BookSectionResponse first = world.section();
        BookSectionResponse second = createSection(world.book(), "Part racing sibling");
        assertThat(sectionSortOrder(first.id())).isZero();

        armReorderOnTheBookLock(() -> swapSortOrder("sections", first.id(), second.id()));

        sectionService.update(first.id(), new BookSectionUpdateRequest(RENAMED_SECTION, null, null));

        assertRaced();
        assertThat(sectionTitle(first.id())).isEqualTo(RENAMED_SECTION);
        assertThat(sectionSortOrder(first.id()))
                .describedAs("the rename wrote back the sortOrder it read before the Book lock")
                .isEqualTo(1);
        assertThat(sectionSortOrder(second.id())).isZero();
    }

    /**
     * Negative control. The same renames with nothing racing them must still apply, and must still leave
     * the ordering alone — otherwise the two cases above could be satisfied by a rename that simply
     * stopped writing rather than by one that stopped writing back pre-lock state.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSameRenamesApplyAndLeaveTheOrderingAloneWhenNothingRacesThem() {
        StoryWorld world = createStoryWorld("Uncontended rename");
        ChapterResponse chapter = world.chapter();
        BookSectionResponse section = world.section();

        chapterService.update(chapter.id(), new ChapterUpdateRequest(RENAMED_CHAPTER, null, null));
        sectionService.update(section.id(), new BookSectionUpdateRequest(RENAMED_SECTION, null, null));

        assertThat(chapterTitle(chapter.id())).isEqualTo(RENAMED_CHAPTER);
        assertThat(chapterSortOrder(chapter.id())).isZero();
        assertThat(sectionTitle(section.id())).isEqualTo(RENAMED_SECTION);
        assertThat(sectionSortOrder(section.id())).isZero();
    }

    /**
     * A rename that does carry an explicit {@code sortOrder} must still be honoured. The fix must remove
     * the accidental write-back of stale ordering without removing the caller's ability to reorder
     * deliberately through the same surface.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anExplicitSortOrderInTheSameRequestIsStillApplied() {
        StoryWorld world = createStoryWorld("Explicit sort order");

        chapterService.update(world.chapter().id(), new ChapterUpdateRequest(RENAMED_CHAPTER, null, 7));
        sectionService.update(world.section().id(), new BookSectionUpdateRequest(RENAMED_SECTION, null, 5));

        assertThat(chapterSortOrder(world.chapter().id())).isEqualTo(7);
        assertThat(sectionSortOrder(world.section().id())).isEqualTo(5);
    }

    /** PostgreSQL takes the Book row lock as {@code for no key update}. */
    private void armReorderOnTheBookLock(Runnable reorder) {
        sqlInterleaveHook.armOn(
                sql -> sql.contains("from books")
                        && (sql.contains("for update") || sql.contains("for no key update")),
                reorder
        );
    }

    /**
     * A surface that never reaches the Book row lock never matches the trigger, so the reorder never
     * commits and the rename is not actually raced.
     */
    private void assertRaced() {
        assertThat(sqlInterleaveHook.hasFired())
                .describedAs("the reorder never commited inside the guard window, so nothing was raced")
                .isTrue();
    }

    /** The committed effect of a concurrent reorder: the two rows exchange their positions. */
    private void swapSortOrder(String table, UUID firstId, UUID secondId) {
        executeOnAnIndependentConnection("""
                update %s set sort_order = 1 where id = '%s';
                update %s set sort_order = 0 where id = '%s';
                """.formatted(table, firstId, table, secondId));
    }

    private Integer chapterSortOrder(UUID chapterId) {
        return inNewTransaction(() -> chapterService.getChapter(chapterId).getSortOrder());
    }

    private String chapterTitle(UUID chapterId) {
        return inNewTransaction(() -> chapterService.getChapter(chapterId).getTitle());
    }

    private Integer sectionSortOrder(UUID sectionId) {
        return inNewTransaction(() -> sectionService.getSection(sectionId).getSortOrder());
    }

    private String sectionTitle(UUID sectionId) {
        return inNewTransaction(() -> sectionService.getSection(sectionId).getTitle());
    }

    private void executeOnAnIndependentConnection(String sql) {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to commit the concurrent reorder", exception);
        }
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PreLockLoadTestConfiguration {

        @Bean
        SqlInterleaveHook sqlInterleaveHook() {
            return new SqlInterleaveHook();
        }

        @Bean
        HibernatePropertiesCustomizer sqlInterleaveHookCustomizer(SqlInterleaveHook hook) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, hook);
        }
    }
}
