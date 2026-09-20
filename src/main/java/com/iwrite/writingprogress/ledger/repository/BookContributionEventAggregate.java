package com.iwrite.writingprogress.ledger.repository;

import java.time.LocalDate;
import java.util.UUID;

public interface BookContributionEventAggregate {

    UUID getActorUserId();

    LocalDate getProgressDate();

    UUID getOriginalSceneId();

    String getSceneTitleSnapshot();

    UUID getOriginalChapterId();

    String getChapterTitleSnapshot();

    long getProductiveWordDelta();

    long getManuscriptWordDelta();
}
