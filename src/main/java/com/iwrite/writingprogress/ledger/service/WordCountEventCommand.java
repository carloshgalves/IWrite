package com.iwrite.writingprogress.ledger.service;

import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;

import java.util.UUID;

public record WordCountEventCommand(
        UUID bookId,
        UUID sceneId,
        UUID originalSceneId,
        String sceneTitleSnapshot,
        UUID originalChapterId,
        String chapterTitleSnapshot,
        BookWordCountEventType eventType,
        int productiveWordDelta,
        int manuscriptWordDelta,
        UUID operationId,
        UUID idempotencyKey,
        Long contentRevisionBefore,
        Long contentRevisionAfter,
        String requestFingerprint,
        int knownManuscriptTotalAfterOperation
) {
}
