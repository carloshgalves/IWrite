package com.iwrite.dashboard.dto;

import java.util.UUID;

public record ContributionOriginResponse(
        UUID sceneId,
        String sceneTitle,
        UUID chapterId,
        String chapterTitle,
        long productiveWords,
        long manuscriptAdjustments,
        long writingDays
) {
}
