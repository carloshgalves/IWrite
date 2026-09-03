package com.iwrite.writingprogress.ledger;

import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.book.service.BookCollaboratorService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(SceneWordCountConcurrencyIntegrationTest.CurrentUserTestConfiguration.class)
class SceneWordCountConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private SceneVersionRepository sceneVersionRepository;

    @Autowired
    private ThreadLocalCurrentUserProvider currentUserProvider;

    @Autowired
    private BookCollaboratorService collaboratorService;

    @Autowired
    private BookAccessService bookAccessService;

    @Autowired
    private BookCollaboratorRepository collaboratorRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUser() {
        currentUserProvider.reset();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSameKeySamePayloadAppliesOnce() {
        var world = createStoryWorld("b7c concurrent same key");
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                "{}",
                "one two three",
                null,
                world.scene().contentRevision(),
                operationId
        );

        List<SceneResponse> responses = runConcurrently(
                () -> sceneService.updateContent(world.scene().id(), request),
                () -> sceneService.updateContent(world.scene().id(), request)
        );

        assertThat(responses)
                .extracting(SceneResponse::id)
                .containsOnly(world.scene().id());
        assertThat(responses)
                .extracting(SceneResponse::contentRevision)
                .containsOnly(world.scene().contentRevision() + 1);
        assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
        assertThat(sceneService.findById(world.scene().id()).wordCount()).isEqualTo(3);
        assertProgress(world.book().id(), DEFAULT_USER_ID, 3, 3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentIdenticalNoOpReservesOperationOnceWithoutProgress() {
        var book = createBook("b7c concurrent noop");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        SceneResponse scene = createScene(chapter, "Noop scene", SceneStatus.DRAFT, 0, "");
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                scene.contentJson(),
                scene.contentText(),
                SceneVersionSource.AUTO_SAVE,
                scene.contentRevision(),
                operationId
        );

        List<SceneResponse> responses = runConcurrently(
                () -> sceneService.updateContent(scene.id(), request),
                () -> sceneService.updateContent(scene.id(), request)
        );

        assertThat(responses)
                .extracting(SceneResponse::contentRevision)
                .containsOnly(scene.contentRevision());
        assertThat(sceneService.findById(scene.id()).wordCount()).isZero();
        assertThat(eventsForKey(book.id(), operationId))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isZero();
                    assertThat(event.getContentRevisionBefore()).isEqualTo(scene.contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(scene.contentRevision());
                    assertThat(event.getRequestFingerprint()).matches("[0-9a-f]{64}");
                });
        assertThat(progressRowsFor(book.id(), DEFAULT_USER_ID)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameKeyDifferentPayloadConflictsBeforeMutation() {
        var world = createStoryWorld("b7c same key conflict");
        UUID operationId = UUID.randomUUID();
        SceneResponse first = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three", null, world.scene().contentRevision(), operationId)
        );

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three four", null, first.contentRevision(), operationId)
        )).isInstanceOf(ConflictException.class);

        SceneResponse reloaded = sceneService.findById(world.scene().id());
        assertThat(reloaded.contentText()).isEqualTo("one two three");
        assertThat(reloaded.contentRevision()).isEqualTo(first.contentRevision());
        assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void noOpKeyDifferentPayloadConflictsBeforeMutation() {
        var world = createStoryWorld("b7c noop key conflict");
        UUID operationId = UUID.randomUUID();
        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        world.scene().contentJson(),
                        world.scene().contentText(),
                        SceneVersionSource.AUTO_SAVE,
                        world.scene().contentRevision(),
                        operationId
                )
        );

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "different payload", null, world.scene().contentRevision(), operationId)
        )).isInstanceOf(ConflictException.class);

        SceneResponse reloaded = sceneService.findById(world.scene().id());
        assertThat(reloaded.contentText()).isEqualTo(world.scene().contentText());
        assertThat(reloaded.contentRevision()).isEqualTo(world.scene().contentRevision());
        assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameKeyDifferentSceneConflictsBeforeMutation() {
        var world = createStoryWorld("b7c same key different scene");
        SceneResponse otherScene = createScene(world.chapter(), "Other", SceneStatus.DRAFT, 1, "other words");
        UUID operationId = UUID.randomUUID();

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three", null, world.scene().contentRevision(), operationId)
        );

        assertThatThrownBy(() -> sceneService.updateContent(
                otherScene.id(),
                new SceneContentRequest("{}", "other words changed", null, otherScene.contentRevision(), operationId)
        )).isInstanceOf(ConflictException.class);

        assertThat(sceneService.findById(otherScene.id()).contentText()).isEqualTo("other words");
        assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameKeyDifferentActorConflictsAtBookScope() {
        var world = createStoryWorld("b7c same key different actor");
        UUID otherUserId = createMember("b7c-other-actor@iwrite.local");
        collaboratorService.grantInternal(world.book().id(), otherUserId, DEFAULT_USER_ID);
        UUID operationId = UUID.randomUUID();

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three", null, world.scene().contentRevision(), operationId)
        );

        currentUserProvider.switchTo(otherUserId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three four", null, world.scene().contentRevision() + 1, operationId)
        )).isInstanceOf(ConflictException.class);
        currentUserProvider.reset();

        assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
        assertThat(sceneService.findById(world.scene().id()).contentText()).isEqualTo("one two three");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameKeyInDifferentBookIsIndependent() {
        var firstWorld = createStoryWorld("b7c same key book one");
        var secondWorld = createStoryWorld("b7c same key book two");
        UUID operationId = UUID.randomUUID();

        sceneService.updateContent(
                firstWorld.scene().id(),
                new SceneContentRequest("{}", "one two three", null, firstWorld.scene().contentRevision(), operationId)
        );
        sceneService.updateContent(
                secondWorld.scene().id(),
                new SceneContentRequest("{}", "one two three four", null, secondWorld.scene().contentRevision(), operationId)
        );

        assertThat(eventsForKey(firstWorld.book().id(), operationId)).hasSize(1);
        assertThat(eventsForKey(secondWorld.book().id(), operationId)).hasSize(1);
        assertThat(sceneService.findById(firstWorld.scene().id()).wordCount()).isEqualTo(3);
        assertThat(sceneService.findById(secondWorld.scene().id()).wordCount()).isEqualTo(4);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDifferentScenesSameBookPreserveBothDeltasAndSerializedEndingTotals() {
        var book = createBook("b7c different scenes");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        SceneResponse firstScene = createScene(chapter, "First concurrent", SceneStatus.DRAFT, 0, "");
        SceneResponse secondScene = createScene(chapter, "Second concurrent", SceneStatus.DRAFT, 1, "");
        UUID otherUserId = createMember("b7c-concurrent-user@iwrite.local");
        collaboratorService.grantInternal(book.id(), otherUserId, DEFAULT_USER_ID);
        UUID firstOperationId = UUID.randomUUID();
        UUID secondOperationId = UUID.randomUUID();

        runConcurrently(
                asUser(DEFAULT_USER_ID, () -> sceneService.updateContent(
                        firstScene.id(),
                        new SceneContentRequest("{}", "one", null, firstScene.contentRevision(), firstOperationId)
                )),
                asUser(otherUserId, () -> sceneService.updateContent(
                        secondScene.id(),
                        new SceneContentRequest("{}", "two words", null, secondScene.contentRevision(), secondOperationId)
                ))
        );

        List<BookWordCountEvent> contentEvents = eventsForBook(book.id()).stream()
                .filter(event -> event.getEventType() == BookWordCountEventType.CONTENT_SAVE)
                .filter(event -> event.getContentRevisionBefore() != null)
                .sorted(Comparator.comparing(BookWordCountEvent::getCreatedAt))
                .toList();

        assertThat(contentEvents).hasSize(2);
        assertThat(contentEvents).extracting(BookWordCountEvent::getManuscriptWordDelta).containsExactlyInAnyOrder(1, 2);
        assertThat(contentEvents).extracting(BookWordCountEvent::getActorUser)
                .extracting(user -> user.getId())
                .containsExactlyInAnyOrder(DEFAULT_USER_ID, otherUserId);
        assertThat(contentEvents).extracting(event -> event.getIdempotencyKey())
                .containsExactlyInAnyOrder(firstOperationId, secondOperationId);
        assertThat(contentEvents).extracting(BookWordCountEvent::getCreatedAt).isSorted();

        int finalAggregate = sceneService.findById(firstScene.id()).wordCount()
                + sceneService.findById(secondScene.id()).wordCount();
        assertThat(finalAggregate).isEqualTo(3);
        assertProductiveProgress(book.id(), DEFAULT_USER_ID, 1);
        assertProductiveProgress(book.id(), otherUserId, 2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void collaboratorEditWaitingOnBookLockIsDeniedAfterOwnerRevokesAccess() throws Exception {
        var world = createStoryWorld("c1 revoked collaborator waits");
        UUID collaboratorId = createMember("c1-revoked-waiting@iwrite.local");
        collaboratorService.grantInternal(world.book().id(), collaboratorId, DEFAULT_USER_ID);
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                "{}",
                "collaborator should not persist",
                null,
                world.scene().contentRevision(),
                operationId
        );
        CountDownLatch ownerRemovedAndHoldingLock = new CountDownLatch(1);
        CountDownLatch allowOwnerCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<Void> ownerFuture = CompletableFuture.runAsync(() -> inNewTransaction(() -> {
                Book lockedBook = bookAccessService.requireCapabilityForUpdate(
                        world.book().id(),
                        BookCapability.MANAGE_COLLABORATORS
                );
                var collaborator = collaboratorRepository.findByBook_IdAndTenant_IdAndUser_Id(
                                lockedBook.getId(),
                                DEFAULT_TENANT_ID,
                                collaboratorId
                        )
                        .orElseThrow();
                collaboratorRepository.delete(collaborator);
                collaboratorRepository.flush();
                ownerRemovedAndHoldingLock.countDown();
                await(allowOwnerCommit);
                return null;
            }), executor);

            await(ownerRemovedAndHoldingLock);
            CompletableFuture<SceneResponse> collaboratorFuture = CompletableFuture.supplyAsync(
                    () -> call(asUser(collaboratorId, () -> sceneService.updateContent(world.scene().id(), request))),
                    executor
            );

            waitForBlockedDatabaseLock();
            assertThat(collaboratorFuture).isNotDone();

            allowOwnerCommit.countDown();
            ownerFuture.orTimeout(10, TimeUnit.SECONDS).join();
            assertThatThrownBy(() -> collaboratorFuture.orTimeout(10, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(ResourceNotFoundException.class);
        } finally {
            allowOwnerCommit.countDown();
            executor.shutdownNow();
        }

        SceneResponse reloaded = sceneService.findById(world.scene().id());
        assertThat(reloaded.contentText()).isEqualTo(world.scene().contentText());
        assertThat(reloaded.contentRevision()).isEqualTo(world.scene().contentRevision());
        assertThat(reloaded.wordCount()).isEqualTo(world.scene().wordCount());
        assertThat(eventsForKey(world.book().id(), operationId)).isEmpty();
        assertThat(progressRowsFor(world.book().id(), collaboratorId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void collaboratorEditWithBookLockFirstCompletesBeforeOwnerRemovalRevokesFutureAccess() throws Exception {
        var world = createStoryWorld("c1 collaborator first then revoke");
        UUID collaboratorId = createMember("c1-collaborator-first@iwrite.local");
        collaboratorService.grantInternal(world.book().id(), collaboratorId, DEFAULT_USER_ID);
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                "{}",
                "collaborator edit persisted",
                null,
                world.scene().contentRevision(),
                operationId
        );
        CountDownLatch collaboratorHoldingLock = new CountDownLatch(1);
        CountDownLatch allowCollaboratorEdit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<SceneResponse> collaboratorFuture = CompletableFuture.supplyAsync(
                    () -> call(asUser(collaboratorId, () -> inNewTransaction(() -> {
                        bookAccessService.requireBookEditAccessForUpdate(world.book().id());
                        collaboratorHoldingLock.countDown();
                        await(allowCollaboratorEdit);
                        return sceneService.updateContent(world.scene().id(), request);
                    }))),
                    executor
            );

            await(collaboratorHoldingLock);
            CompletableFuture<Void> ownerRemovalFuture = CompletableFuture.runAsync(
                    () -> collaboratorService.remove(world.book().id(), collaboratorId),
                    executor
            );
            waitForBlockedDatabaseLock();
            assertThat(ownerRemovalFuture).isNotDone();

            allowCollaboratorEdit.countDown();
            SceneResponse edited = collaboratorFuture.orTimeout(10, TimeUnit.SECONDS).join();
            ownerRemovalFuture.orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(edited.contentText()).isEqualTo("collaborator edit persisted");
            assertThat(eventsForKey(world.book().id(), operationId)).hasSize(1);
            assertProductiveProgress(world.book().id(), collaboratorId, 1);
        } finally {
            allowCollaboratorEdit.countDown();
            executor.shutdownNow();
        }

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "second edit denied", null, world.scene().contentRevision() + 1, UUID.randomUUID())
        )).isInstanceOf(ResourceNotFoundException.class);
        currentUserProvider.reset();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreateRetryCreatesOneScene() {
        var book = createBook("b7c create retry");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        UUID operationId = UUID.randomUUID();
        SceneRequest request = new SceneRequest(
                "Created once",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "created words",
                operationId
        );

        List<SceneResponse> responses = runConcurrently(
                () -> sceneService.create(chapter.id(), request),
                () -> sceneService.create(chapter.id(), request)
        );

        assertThat(responses).extracting(SceneResponse::id).containsOnly(responses.getFirst().id());
        assertThat(eventsForKey(book.id(), operationId)).hasSize(1);
        assertThat(countScenesInChapter(chapter.id())).isEqualTo(1);
        assertProgress(book.id(), DEFAULT_USER_ID, 2, 2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createRetryUsesImmutableFingerprintAfterLaterSceneEdit() {
        var book = createBook("b7c create immutable retry");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        UUID createOperationId = UUID.randomUUID();
        SceneRequest createRequest = new SceneRequest(
                "Created scene",
                "Original summary",
                SceneStatus.DRAFT,
                0,
                "{}",
                "created words",
                createOperationId
        );
        SceneResponse created = sceneService.create(chapter.id(), createRequest);

        SceneResponse edited = sceneService.updateContent(
                created.id(),
                new SceneContentRequest("{}", "later edited words", null, created.contentRevision(), UUID.randomUUID())
        );
        long eventsBeforeRetry = eventsForBook(book.id()).size();

        SceneResponse retry = sceneService.create(chapter.id(), createRequest);

        assertThat(retry.id()).isEqualTo(created.id());
        assertThat(retry.contentText()).isEqualTo("later edited words");
        assertThat(retry.contentRevision()).isEqualTo(edited.contentRevision());
        assertThat(countScenesInChapter(chapter.id())).isEqualTo(1);
        assertThat(eventsForBook(book.id())).hasSize((int) eventsBeforeRetry);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createRetryWithDifferentPayloadConflictsBeforeSecondScene() {
        var book = createBook("b7c create immutable mismatch");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        UUID operationId = UUID.randomUUID();

        sceneService.create(chapter.id(), new SceneRequest(
                "Created scene",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "created words",
                operationId
        ));

        assertThatThrownBy(() -> sceneService.create(chapter.id(), new SceneRequest(
                "Created scene",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "different words",
                operationId
        ))).isInstanceOf(ConflictException.class);
        assertThat(countScenesInChapter(chapter.id())).isEqualTo(1);
        assertThat(eventsForKey(book.id(), operationId)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createRetryAfterCreatedSceneWasDeletedDoesNotRecreateScene() {
        var book = createBook("b7c create retry deleted");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        UUID operationId = UUID.randomUUID();
        SceneRequest request = new SceneRequest(
                "Deleted created scene",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "created words",
                operationId
        );
        SceneResponse created = sceneService.create(chapter.id(), request);
        sceneService.delete(created.id());
        long eventsBeforeRetry = eventsForBook(book.id()).size();

        assertThatThrownBy(() -> sceneService.create(chapter.id(), request))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(countScenesInChapter(chapter.id())).isZero();
        assertThat(eventsForBook(book.id())).hasSize((int) eventsBeforeRetry);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void contentRetryUsesImmutableFingerprintAfterLaterEdits() {
        var world = createStoryWorld("b7c content immutable retry");
        UUID firstOperationId = UUID.randomUUID();
        SceneContentRequest firstRequest = new SceneContentRequest(
                "{}",
                "content A",
                SceneVersionSource.AUTO_SAVE,
                world.scene().contentRevision(),
                firstOperationId
        );
        SceneResponse first = sceneService.updateContent(world.scene().id(), firstRequest);
        SceneResponse second = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "content B now", null, first.contentRevision(), UUID.randomUUID())
        );
        long eventsBeforeRetry = eventsForBook(world.book().id()).size();

        SceneResponse retry = sceneService.updateContent(world.scene().id(), firstRequest);

        assertThat(retry.contentText()).isEqualTo("content B now");
        assertThat(retry.contentRevision()).isEqualTo(second.contentRevision());
        assertThat(sceneService.findById(world.scene().id()).contentText()).isEqualTo("content B now");
        assertThat(eventsForBook(world.book().id())).hasSize((int) eventsBeforeRetry);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void noOpContentRetryUsesImmutableFingerprintAfterLaterEdits() {
        var world = createStoryWorld("b7c noop immutable retry");
        UUID firstOperationId = UUID.randomUUID();
        SceneContentRequest firstRequest = new SceneContentRequest(
                world.scene().contentJson(),
                world.scene().contentText(),
                SceneVersionSource.AUTO_SAVE,
                world.scene().contentRevision(),
                firstOperationId
        );
        SceneResponse first = sceneService.updateContent(world.scene().id(), firstRequest);
        SceneResponse later = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "content B now", null, first.contentRevision(), UUID.randomUUID())
        );
        long eventsBeforeRetry = eventsForBook(world.book().id()).size();

        SceneResponse retry = sceneService.updateContent(world.scene().id(), firstRequest);

        assertThat(retry.contentText()).isEqualTo("content B now");
        assertThat(retry.contentRevision()).isEqualTo(later.contentRevision());
        assertThat(sceneService.findById(world.scene().id()).contentText()).isEqualTo("content B now");
        assertThat(eventsForBook(world.book().id())).hasSize((int) eventsBeforeRetry);
        assertThat(eventsForKey(world.book().id(), firstOperationId)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void restoreRetryUsesImmutableFingerprintAfterLaterEditsAndDifferentVersionConflicts() {
        var world = createStoryWorld("b7c restore immutable retry");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words here", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );
        var originalVersion = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .stream()
                .filter(version -> "scene words".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();
        UUID restoreOperationId = UUID.randomUUID();
        var restoreRequest = new com.iwrite.sceneversion.dto.SceneVersionRestoreRequest(
                updated.contentRevision(),
                restoreOperationId
        );
        SceneResponse restored = sceneService.restoreVersion(world.scene().id(), originalVersion.getId(), restoreRequest);
        SceneResponse later = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "later words", null, restored.contentRevision(), UUID.randomUUID())
        );
        long eventsBeforeRetry = eventsForBook(world.book().id()).size();

        SceneResponse retry = sceneService.restoreVersion(world.scene().id(), originalVersion.getId(), restoreRequest);

        assertThat(retry.contentText()).isEqualTo("later words");
        assertThat(retry.contentRevision()).isEqualTo(later.contentRevision());
        assertThat(eventsForBook(world.book().id())).hasSize((int) eventsBeforeRetry);

        var differentVersion = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .stream()
                .filter(version -> "new words here".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();
        assertThatThrownBy(() -> sceneService.restoreVersion(world.scene().id(), differentVersion.getId(), restoreRequest))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void legacyNullFingerprintDuplicateFailsClosedWithoutMutableSceneComparison() {
        var world = createStoryWorld("b7c legacy fingerprint");
        UUID operationId = UUID.randomUUID();
        createLegacyEvent(world.book().id(), world.scene().id(), operationId);

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "content A", null, world.scene().contentRevision(), operationId)
        )).isInstanceOf(ConflictException.class);

        SceneResponse reloaded = sceneService.findById(world.scene().id());
        assertThat(reloaded.contentText()).isEqualTo(world.scene().contentText());
        assertThat(reloaded.contentRevision()).isEqualTo(world.scene().contentRevision());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletionRetryDoesNotDuplicateLedgerEffects() {
        var world = createStoryWorld("b7c delete retry");

        sceneService.delete(world.scene().id());
        assertThatThrownBy(() -> sceneService.delete(world.scene().id()))
                .isInstanceOf(ResourceNotFoundException.class);

        List<BookWordCountEvent> deleteEvents = eventsForBook(world.book().id()).stream()
                .filter(event -> event.getEventType() == BookWordCountEventType.SCENE_DELETE)
                .toList();
        assertThat(deleteEvents).hasSize(1);
        assertThat(deleteEvents.getFirst().getManuscriptWordDelta()).isEqualTo(-2);
        assertProgress(world.book().id(), DEFAULT_USER_ID, 0, 2);
    }

    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<T> firstFuture = CompletableFuture.supplyAsync(() -> callAfter(start, first), executor);
            CompletableFuture<T> secondFuture = CompletableFuture.supplyAsync(() -> callAfter(start, second), executor);
            start.countDown();
            return List.of(
                    firstFuture.orTimeout(10, TimeUnit.SECONDS).join(),
                    secondFuture.orTimeout(10, TimeUnit.SECONDS).join()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> Callable<T> asUser(UUID userId, Callable<T> callable) {
        return () -> {
            currentUserProvider.switchTo(userId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
            try {
                return callable.call();
            } finally {
                currentUserProvider.reset();
            }
        };
    }

    private <T> T call(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T callAfter(CountDownLatch start, Callable<T> callable) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent workers");
            }
            return callable.call();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void waitForBlockedDatabaseLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (blockedDatabaseLockCount() > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Timed out waiting for a PostgreSQL lock wait");
    }

    private long blockedDatabaseLockCount() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("""
                     select count(*)
                     from pg_locks waiting
                     join pg_locks blocker
                       on waiting.locktype = blocker.locktype
                      and waiting.database is not distinct from blocker.database
                      and waiting.relation is not distinct from blocker.relation
                      and waiting.page is not distinct from blocker.page
                      and waiting.tuple is not distinct from blocker.tuple
                      and waiting.virtualxid is not distinct from blocker.virtualxid
                      and waiting.transactionid is not distinct from blocker.transactionid
                      and waiting.classid is not distinct from blocker.classid
                      and waiting.objid is not distinct from blocker.objid
                      and waiting.objsubid is not distinct from blocker.objsubid
                     where waiting.granted = false
                       and blocker.granted = true
                       and waiting.pid <> blocker.pid
                     """)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private List<BookWordCountEvent> eventsForKey(UUID bookId, UUID idempotencyKey) {
        return eventsForBook(bookId).stream()
                .filter(event -> idempotencyKey.equals(event.getIdempotencyKey()))
                .toList();
    }

    private List<BookWordCountEvent> eventsForBook(UUID bookId) {
        return inNewTransaction(() -> eventRepository.findAll().stream()
                .filter(event -> event.getBook().getId().equals(bookId))
                .toList());
    }

    private long countScenesInChapter(UUID chapterId) {
        return inNewTransaction(() -> entityManager.createQuery(
                        "select count(scene) from Scene scene where scene.chapter.id = :chapterId",
                        Long.class
                )
                .setParameter("chapterId", chapterId)
                .getSingleResult());
    }

    private void assertProgress(UUID bookId, UUID userId, int endingTotal, int productiveDelta) {
        DailyWritingProgress progress = onlyProgressFor(bookId, userId);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(endingTotal);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(productiveDelta);
    }

    private void assertProductiveProgress(UUID bookId, UUID userId, int productiveDelta) {
        assertThat(onlyProgressFor(bookId, userId).getProductiveWordCountChange()).isEqualTo(productiveDelta);
    }

    private DailyWritingProgress onlyProgressFor(UUID bookId, UUID userId) {
        return inNewTransaction(() -> entityManager.createQuery(
                        """
                                select progress
                                from DailyWritingProgress progress
                                where progress.user.id = :userId
                                  and progress.book.id = :bookId
                                """,
                        DailyWritingProgress.class
                )
                .setParameter("userId", userId)
                .setParameter("bookId", bookId)
                .getSingleResult());
    }

    private List<DailyWritingProgress> progressRowsFor(UUID bookId, UUID userId) {
        return inNewTransaction(() -> entityManager.createQuery(
                        """
                                select progress
                                from DailyWritingProgress progress
                                where progress.user.id = :userId
                                  and progress.book.id = :bookId
                                """,
                        DailyWritingProgress.class
                )
                .setParameter("userId", userId)
                .setParameter("bookId", bookId)
                .getResultList());
    }

    private UUID createMember(String email) {
        return inNewTransaction(() -> {
            User user = new User();
            user.setDisplayName(email);
            user.setEmail(email);
            user.setTimeZoneId("UTC");
            entityManager.persist(user);

            TenantMembership membership = new TenantMembership();
            membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
            membership.setUser(user);
            membership.setRole(TenantMembershipRole.OWNER);
            entityManager.persist(membership);
            entityManager.flush();
            return user.getId();
        });
    }

    private void createLegacyEvent(UUID bookId, UUID sceneId, UUID operationId) {
        inNewTransaction(() -> {
            BookWordCountEvent event = new BookWordCountEvent();
            event.setBook(entityManager.getReference(Book.class, bookId));
            event.setScene(entityManager.getReference(Scene.class, sceneId));
            event.setActorUser(entityManager.getReference(User.class, DEFAULT_USER_ID));
            event.setOriginalSceneId(sceneId);
            event.setSceneTitleSnapshot("Legacy event");
            event.setEventType(BookWordCountEventType.CONTENT_SAVE);
            event.setProductiveWordDelta(1);
            event.setManuscriptWordDelta(1);
            event.setOperationId(operationId);
            event.setIdempotencyKey(operationId);
            event.setContentRevisionBefore(0L);
            event.setContentRevisionAfter(1L);
            entityManager.persist(event);
            entityManager.flush();
            return null;
        });
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        ThreadLocalCurrentUserProvider threadLocalCurrentUserProvider() {
            return new ThreadLocalCurrentUserProvider();
        }
    }

    static class ThreadLocalCurrentUserProvider implements CurrentUserProvider {

        private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("America/Sao_Paulo");
        private final ThreadLocal<Identity> identity = ThreadLocal.withInitial(this::defaultIdentity);

        void switchTo(UUID userId, UUID tenantId, ZoneId effectiveZoneId) {
            identity.set(new Identity(userId, tenantId, effectiveZoneId));
        }

        void reset() {
            identity.set(defaultIdentity());
        }

        @Override
        public UUID userId() {
            return identity.get().userId();
        }

        @Override
        public UUID tenantId() {
            return identity.get().tenantId();
        }

        @Override
        public ZoneId effectiveZoneId() {
            return identity.get().effectiveZoneId();
        }

        private Identity defaultIdentity() {
            return new Identity(DEFAULT_USER_ID, DEFAULT_TENANT_ID, DEFAULT_ZONE_ID);
        }

        private record Identity(UUID userId, UUID tenantId, ZoneId effectiveZoneId) {
        }
    }
}
