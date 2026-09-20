"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  getBookContributions,
  getBookDashboard,
  getCurrentUserDashboard,
  type WritingProgressPeriod,
} from "@/features/dashboard/api/dashboard-api";
import { queryKeys } from "@/lib/query/keys";

export function useBookDashboard(bookId: string, progressPeriod: WritingProgressPeriod = "7d") {
  return useQuery({
    queryKey: [...queryKeys.bookDashboard(bookId), progressPeriod],
    queryFn: () => getBookDashboard(bookId, progressPeriod),
    enabled: Boolean(bookId),
    placeholderData: keepPreviousData,
  });
}

export function useCurrentUserDashboard(progressPeriod: WritingProgressPeriod = "7d") {
  return useQuery({
    queryKey: [...queryKeys.userDashboard, progressPeriod],
    queryFn: () => getCurrentUserDashboard(progressPeriod),
    placeholderData: keepPreviousData,
  });
}

export function useBookContributions(
  bookId: string,
  progressPeriod: WritingProgressPeriod = "7d",
  contributorId?: string,
) {
  return useQuery({
    queryKey: [...queryKeys.bookContributions(bookId), progressPeriod, contributorId ?? "all"],
    queryFn: () => getBookContributions(bookId, progressPeriod, contributorId),
    enabled: Boolean(bookId),
  });
}
