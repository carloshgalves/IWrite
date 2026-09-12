package com.iwrite.dashboard.dto;

import java.util.List;

public record BookContributionDashboardResponse(
        WritingProgressPeriodResponse period,
        String scope,
        ContributorSummaryResponse selectedContributor,
        List<ContributorSummaryResponse> availableContributors,
        ContributionSummaryResponse summary,
        List<ContributionDailyWritingResponse> dailySeries,
        List<ContributionOriginResponse> origins
) {
}
