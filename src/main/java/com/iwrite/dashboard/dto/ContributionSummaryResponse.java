package com.iwrite.dashboard.dto;

public record ContributionSummaryResponse(
        long productiveWords,
        long manuscriptAdjustments,
        long writingDays,
        long contributorsCount,
        long distinctScenes,
        long distinctChapters
) {
}
