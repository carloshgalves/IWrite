package com.iwrite.manuscript;

import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.dto.SceneVersionSummaryResponse;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SqlInterleaveHook;
import com.iwrite.support.TestDatabaseInitializer;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lock ordering invariant of the Manuscript surfaces (#207).
 *
 * <p>Re-proving authority under the Book row lock gave these surfaces two row locks each, so the order
 * in which they take them became part of the contract. A Manuscript Structure Mutation locks the Book
 * row and reaches its Scenes afterwards. The canonical content save, the Scene Version restore and the
 * three deletions did the opposite: Scene rows first, Book row after.
 *
 * <p>Two authorized Users writing the same Scene then had two ways to lose. Deleting a Chapter or a
 * Section locks Scene rows and nothing else, so it closed a real cycle with a concurrent rename and
 * PostgreSQL aborted one of them with SQLSTATE {@code 40P01}. The content save escaped that only by
 * accident: its lock statement joined up to {@code books}, so it took the Book row in the very same
 * statement. That accident cost the other half of the invariant instead — the rename read the Scene
 * before queueing for the Book row and wrote that pre-lock text back once it got in, discarding a
 * content save that had committed in between.
 *
 * <p>The existing concurrency tests cannot see either symptom: they drive one transaction and commit
 * the competing change on an independent connection, which never holds a lock long enough to be waited
 * on. These cases run both surfaces as real concurrent transactions and pin the order they must agree
 * on, and they assert both writes survived rather than only that neither was aborted.
 *
 * <p>The interleaving is deterministic rather than timed. The Scene-first transaction is stopped
 * immediately before its <em>second</em> row lock — the point where it already holds one and is about
 * to ask for the other, whichever way round the two are ordered — the Book-first transaction is
 * released there, and the pause lasts until PostgreSQL itself reports a backend waiting on a lock.
 */
@Import(ManuscriptLockOrderingConcurrencyIntegrationTest.LockOrderingTestConfiguration.class)
class ManuscriptLockOrderingConcurrencyIntegrationTest extends PostgresIntegrationTest {

    /** The statement to stop on: by then the transaction holds its first row lock and wants the second. */
    private static final int SECOND_ROW_LOCK = 2;
    private static final long CONTENTION_TIMEOUT_SECONDS = 20;
    private static final long RACE_TIMEOUT_SECONDS = 90;
    private static final long POLL_MILLIS = 20;

    private static final String RESTORED_TEXT = "texto que a versao guardava";
    private static final String SAVED_TEXT = "texto salvo durante a reestruturacao";
    private static final String RENAMED = "cena renomeada sob disputa";

    @Autowired
    private SqlInterleaveHook sqlInterleaveHook;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SceneVersionService sceneVersionService;

    @AfterEach
    void resetSeams() {
        sqlInterleaveHook.disarm();
    }

    /**
     * A Manuscript Structure Mutation and a canonical content write on the same Scene, both authorized
     * and both concurrent. Neither may be aborted, and both effects must survive: a deadlock turns a
     * legitimate edit into a lost autosave, and a rename that wrote back pre-lock state would discard
     * the text just saved.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rename Scene x content save",
            "reorder Scenes x content save",
            "rename Scene x restore Scene Version"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aStructureMutationAndAContentWriteOnTheSameSceneBothComplete(String pairing) {
        RaceWorld world = createRaceWorld(pairing);

        Race race = race(() -> structureMutation(pairing, world), () -> contentWrite(pairing, world));

        assertThat(race.deadlocked())
                .describedAs("PostgreSQL aborted one of the two authorized writers with a deadlock")
                .isFalse();
        assertThat(race.structureFailure()).isNull();
        assertThat(race.contentFailure()).isNull();
        assertRacedForReal(race);

        SceneResponse scene = readScene(world.sceneId());
        assertThat(scene.contentText()).isEqualTo(expectedContent(pairing));
        assertStructureApplied(pairing, world);
    }

    /**
     * The same cycle with the Scene-first surface being a deletion. These share the family the fix has
     * to cover: fixing the content save alone would leave Scene, Chapter and Section deletion still
     * taking the Scene rows before the Book row.
     *
     * <p>The two orderings disagree on which write lands first, so the outcome asserted here is the one
     * that holds either way: the deletion is applied and the rename is either applied first or refused
     * as a Scene that no longer exists. What must never happen is an abort.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rename Scene x delete Scene",
            "rename Scene x delete Chapter",
            "rename Scene x delete Section"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aStructureMutationAndADeletionOfTheSameSceneDoNotDeadlock(String pairing) {
        RaceWorld world = createRaceWorld(pairing);

        Race race = race(() -> structureMutation(pairing, world), () -> contentWrite(pairing, world));

        assertThat(race.deadlocked())
                .describedAs("PostgreSQL aborted one of the two authorized writers with a deadlock")
                .isFalse();
        assertThat(race.contentFailure()).isNull();
        if (race.structureFailure() != null) {
            assertThat(race.structureFailure()).isInstanceOf(ResourceNotFoundException.class);
        }
        assertRacedForReal(race);

        assertThatThrownBy(() -> readScene(world.sceneId())).isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Negative control. Uncontended, the same rename and the same content save both apply, so the cases
     * above are read as statements about lock ordering and not about a surface that stopped working.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSameRenameAndContentSaveBothApplyWhenTheyDoNotOverlap() {
        RaceWorld world = createRaceWorld("uncontended");

        sceneService.update(world.sceneId(), new SceneUpdateRequest(RENAMED, null, null, null));
        saveContent(world, SAVED_TEXT);

        SceneResponse scene = readScene(world.sceneId());
        assertThat(scene.title()).isEqualTo(RENAMED);
        assertThat(scene.contentText()).isEqualTo(SAVED_TEXT);
    }

    private void assertRacedForReal(Race race) {
        // Without these the case could pass having never raced at all: the Book-first transaction is
        // only released from inside the pause, and the pause only ends once PostgreSQL reports a
        // backend actually waiting on a lock held by the other one.
        assertThat(race.interleaved())
                .describedAs("the Scene-first transaction never reached its second row lock")
                .isTrue();
        assertThat(race.observedContention())
                .describedAs("the two transactions never contended on a row lock")
                .isTrue();
    }

    private void structureMutation(String pairing, RaceWorld world) {
        if (pairing.startsWith("reorder Scenes")) {
            sceneService.reorder(world.chapterId(), new ReorderRequest(List.of(world.otherSceneId(), world.sceneId())));
            return;
        }
        sceneService.update(world.sceneId(), new SceneUpdateRequest(RENAMED, null, null, null));
    }

    private void contentWrite(String pairing, RaceWorld world) {
        switch (pairing.substring(pairing.indexOf(" x ") + 3)) {
            case "content save" -> saveContent(world, SAVED_TEXT);
            case "restore Scene Version" -> sceneService.restoreVersion(
                    world.sceneId(),
                    world.restorableVersionId(),
                    new SceneVersionRestoreRequest(world.revisionBeforeTheRace(), UUID.randomUUID())
            );
            case "delete Scene" -> sceneService.delete(world.sceneId());
            case "delete Chapter" -> chapterService.delete(world.chapterId());
            case "delete Section" -> sectionService.delete(world.sectionId());
            default -> throw new IllegalArgumentException("Unknown pairing: " + pairing);
        }
    }

    private String expectedContent(String pairing) {
        return pairing.endsWith("restore Scene Version") ? RESTORED_TEXT : SAVED_TEXT;
    }

    private void assertStructureApplied(String pairing, RaceWorld world) {
        if (pairing.startsWith("reorder Scenes")) {
            assertThat(readScene(world.sceneId()).sortOrder()).isEqualTo(1);
            assertThat(readScene(world.otherSceneId()).sortOrder()).isEqualTo(0);
            return;
        }
        assertThat(readScene(world.sceneId()).title()).isEqualTo(RENAMED);
    }

    /**
     * The expected revision is read before the race rather than inside it: reading the Scene first
     * would put it in the writer's persistence context ahead of its own row lock, which is exactly the
     * staleness these cases exist to rule out.
     */
    private SceneResponse saveContent(RaceWorld world, String text) {
        return sceneService.updateContent(world.sceneId(), new SceneContentRequest(
                "{\"type\":\"doc\"}",
                text,
                SceneVersionSource.AUTO_SAVE,
                world.revisionBeforeTheRace(),
                UUID.randomUUID()
        ));
    }

    /**
     * Runs the two surfaces as real concurrent transactions, stopping the Scene-first one between its
     * two row locks so the cycle, if the orders disagree, is closed every time instead of sometimes.
     */
    private Race race(Runnable structureMutation, Runnable contentWrite) {
        CountDownLatch releaseStructureMutation = new CountDownLatch(1);
        AtomicBoolean observedContention = new AtomicBoolean();
        AtomicInteger rowLocks = new AtomicInteger();

        sqlInterleaveHook.armOn(
                sql -> isRowLock(sql) && rowLocks.incrementAndGet() == SECOND_ROW_LOCK,
                () -> {
                    releaseStructureMutation.countDown();
                    observedContention.set(awaitBlockedBackend());
                }
        );

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> contentResult = threads.submit(() -> failureOf(contentWrite));
            Future<Throwable> structureResult = threads.submit(() -> {
                if (!releaseStructureMutation.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The content write never reached its second row lock");
                }
                return failureOf(structureMutation);
            });
            Throwable contentFailure = outcomeOf(contentResult);
            Throwable structureFailure = outcomeOf(structureResult);
            return new Race(
                    structureFailure,
                    contentFailure,
                    sqlInterleaveHook.hasFired(),
                    observedContention.get()
            );
        } finally {
            threads.shutdownNow();
        }
    }

    private Throwable failureOf(Runnable surface) {
        try {
            surface.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable outcomeOf(Future<Throwable> result) {
        try {
            return result.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting the race", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("The raced surface did not finish", failure);
        }
    }

    private static boolean isRowLock(String sql) {
        return sql.contains("for update") || sql.contains("for no key update");
    }

    /** Waits until PostgreSQL reports a backend blocked on a lock, which is the contention itself. */
    private boolean awaitBlockedBackend() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONTENTION_TIMEOUT_SECONDS);
        try (Connection connection = TestDatabaseInitializer.openDirectConnection();
             PreparedStatement blocked = connection.prepareStatement("""
                     select count(*)
                     from pg_stat_activity
                     where datname = current_database()
                       and pid <> pg_backend_pid()
                       and wait_event_type = 'Lock'
                     """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = blocked.executeQuery()) {
                    if (rows.next() && rows.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to observe the lock contention", failure);
        }
    }

    private RaceWorld createRaceWorld(String pairing) {
        return inNewTransaction(() -> {
            StoryWorld world = createStoryWorld("Lock ordering " + pairing + " " + UUID.randomUUID());
            SceneResponse other = createScene(world.chapter(), "Cena vizinha", SceneStatus.DRAFT, 1, "vizinha");
            UUID versionId = seedRestorableVersion(world.scene().id());
            return new RaceWorld(
                    world.section().id(),
                    world.chapter().id(),
                    world.scene().id(),
                    other.id(),
                    versionId,
                    sceneService.findById(world.scene().id()).contentRevision()
            );
        });
    }

    /**
     * Leaves a Scene Version holding {@link #RESTORED_TEXT} while the Scene itself holds something
     * else, so restoring it is a real content write rather than a no-op.
     */
    private UUID seedRestorableVersion(UUID sceneId) {
        sceneService.updateContent(sceneId, new SceneContentRequest(
                "{\"type\":\"doc\"}",
                RESTORED_TEXT,
                SceneVersionSource.MANUAL_SAVE,
                sceneService.findById(sceneId).contentRevision(),
                UUID.randomUUID()
        ));
        UUID versionId = sceneVersionService.listVersions(sceneId, 0, 20).items().stream()
                .filter(version -> RESTORED_TEXT.equals(version.contentTextPreview()))
                .map(SceneVersionSummaryResponse::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Scene Version holds the restorable text"));
        sceneService.updateContent(sceneId, new SceneContentRequest(
                "{\"type\":\"doc\"}",
                "texto que a restauracao deve substituir",
                SceneVersionSource.MANUAL_SAVE,
                sceneService.findById(sceneId).contentRevision(),
                UUID.randomUUID()
        ));
        return versionId;
    }

    private SceneResponse readScene(UUID sceneId) {
        return inNewTransaction(() -> sceneService.findById(sceneId));
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    private record RaceWorld(
            UUID sectionId,
            UUID chapterId,
            UUID sceneId,
            UUID otherSceneId,
            UUID restorableVersionId,
            long revisionBeforeTheRace
    ) {
    }

    private record Race(
            Throwable structureFailure,
            Throwable contentFailure,
            boolean interleaved,
            boolean observedContention
    ) {

        boolean deadlocked() {
            return isDeadlock(structureFailure) || isDeadlock(contentFailure);
        }

        private static boolean isDeadlock(Throwable failure) {
            for (Throwable current = failure; current != null && current != current.getCause(); current = current.getCause()) {
                if (current instanceof SQLException sqlException && "40P01".equals(sqlException.getSQLState())) {
                    return true;
                }
            }
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockOrderingTestConfiguration {

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
