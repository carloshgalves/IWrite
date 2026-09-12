package com.iwrite.scene.service;

import com.iwrite.book.entity.Book;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SceneDeletionLedgerService {

    private final SceneVersionService sceneVersionService;
    private final WordCountEventService wordCountEventService;
    private final SceneRepository sceneRepository;
    private final CurrentUserProvider currentUserProvider;

    public SceneDeletionLedgerService(
            SceneVersionService sceneVersionService,
            WordCountEventService wordCountEventService,
            SceneRepository sceneRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.sceneVersionService = sceneVersionService;
        this.wordCountEventService = wordCountEventService;
        this.sceneRepository = sceneRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public void prepareSceneDelete(Scene scene, Book lockedBook) {
        prepareSceneDeletes(List.of(scene), lockedBook, UUID.randomUUID());
    }

    public void prepareSceneDeletes(List<Scene> scenes, Book lockedBook, UUID operationId) {
        if (scenes.isEmpty()) {
            return;
        }

        List<DeletedSceneFacts> deletedScenes = scenes.stream()
                .map(DeletedSceneFacts::fromScene)
                .toList();
        UUID bookId = lockedBook.getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));

        sceneVersionService.checkpointBeforeDelete(scenes);

        int removedWordCount = 0;
        for (DeletedSceneFacts deletedScene : deletedScenes) {
            int sceneWordCount = deletedScene.wordCount();
            if (sceneWordCount == 0) {
                continue;
            }

            removedWordCount += sceneWordCount;
            UUID idempotencyKey = UUID.randomUUID();
            wordCountEventService.recordForLockedBook(lockedBook, new WordCountEventCommand(
                    bookId,
                    deletedScene.sceneId(),
                    deletedScene.sceneId(),
                    deletedScene.sceneTitle(),
                    deletedScene.chapterId(),
                    deletedScene.chapterTitle(),
                    BookWordCountEventType.SCENE_DELETE,
                    0,
                    -sceneWordCount,
                    operationId,
                    idempotencyKey,
                    deletedScene.contentRevision(),
                    null,
                    WordCountRequestFingerprint.sceneDelete(
                            currentUserProvider.userId(),
                            bookId,
                            deletedScene.sceneId(),
                            operationId,
                            idempotencyKey,
                            deletedScene.contentRevision()
                    ),
                    totalBefore - removedWordCount
            ));
        }
    }

    private record DeletedSceneFacts(
            UUID bookId,
            UUID sceneId,
            String sceneTitle,
            UUID chapterId,
            String chapterTitle,
            int wordCount,
            Long contentRevision
    ) {

        static DeletedSceneFacts fromScene(Scene scene) {
            return new DeletedSceneFacts(
                    scene.getBook().getId(),
                    scene.getId(),
                    scene.getTitle(),
                    scene.getChapter().getId(),
                    scene.getChapter().getTitle(),
                    scene.getWordCount() == null ? 0 : scene.getWordCount(),
                    scene.getContentRevision()
            );
        }
    }
}
