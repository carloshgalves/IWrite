package com.iwrite.dashboard.dto;

import com.iwrite.book.authorization.BookCapability;

import java.util.List;
import java.util.UUID;

/**
 * Book dashboard projection (#206).
 *
 * <p>{@code dailyTargetWordCount} is the authenticated User's own Personal Book Writing Goal, not a
 * Book-wide setting: it is {@code null} when this User chose no target, and it never exposes another
 * collaborator's goal. {@code targetWordCount} remains the shared Book-wide target.
 *
 * @param capabilities capabilities authorized by Book scope alone, so the dashboard can present only
 *                     the controls this User may actually attempt; the server authorizes every
 *                     request again and a hidden control is never the authorization boundary
 * @param contextualCapabilities capabilities the User is eligible for that still depend on a
 *                               resource-scoped predicate evaluated by the operation itself
 */
public record BookDashboardResponse(
        UUID bookId,
        String title,
        int totalWordCount,
        Integer targetWordCount,
        Integer dailyTargetWordCount,
        Integer remainingWordCount,
        Double wordCountProgressPercent,
        Integer exceededTargetWordCount,
        int totalSections,
        int totalChapters,
        int totalScenes,
        BookMyWritingResponse myWriting,
        PlanningProgressResponse planningProgress,
        List<StatusCountResponse> scenesByStatus,
        List<PovStatsResponse> povStats,
        NarrativeGapsResponse narrativeGaps,
        List<EntityUsageResponse> mostUsedCharacters,
        List<EntityUsageResponse> mostUsedLocations,
        List<EntityUsageResponse> mostUsedItems,
        List<BookCapability> capabilities,
        List<BookCapability> contextualCapabilities
) {
}
