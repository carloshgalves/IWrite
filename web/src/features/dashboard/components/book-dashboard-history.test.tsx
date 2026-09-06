import { screen, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { characterAda, dashboardWithScenes, itemKey, locationLibrary } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useBookDashboard: vi.fn(),
  useBookContributions: vi.fn(),
  useCharacter: vi.fn(),
  useLocation: vi.fn(),
  useItem: vi.fn(),
  updateBook: vi.fn(),
  updateWritingGoal: vi.fn(),
}));

vi.mock("@/features/dashboard/api/dashboard-hooks", () => ({
  useBookDashboard: mocks.useBookDashboard,
  useBookContributions: mocks.useBookContributions,
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacter: mocks.useCharacter,
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocation: mocks.useLocation,
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItem: mocks.useItem,
}));

vi.mock("@/features/books/api/books-api", () => ({
  updateBook: mocks.updateBook,
}));

vi.mock("@/features/writing-goal/api/writing-goal-api", () => ({
  updateWritingGoal: mocks.updateWritingGoal,
}));

function historicalGoalDashboard(currentDailyTargetWordCount: number | null) {
  return {
    ...dashboardWithScenes,
    dailyTargetWordCount: currentDailyTargetWordCount,
    myWriting: {
      progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          {
            ...dashboardWithScenes.myWriting.progress.today,
            date: "2026-05-13",
            dailyTargetWordCount: 500,
            productiveWordCountChange: 600,
            progressPercent: 120,
          },
          {
            ...dashboardWithScenes.myWriting.progress.today,
            date: "2026-05-14",
            dailyTargetWordCount: null,
            productiveWordCountChange: 1_200,
            progressPercent: null,
          },
        ],
      },
      schedule: dashboardWithScenes.myWriting.schedule,
    },
  };
}

function expectHistoricalGoalHitCount(expected: string) {
  const summary = screen.getByTestId("daily-progress-summary-grid");
  const label = within(summary).getByText("Dias em que bateu a meta");
  expect(label.nextElementSibling).toHaveTextContent(expected);
}

describe("BookDashboard historical writing goals", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useCharacter.mockReturnValue({ isLoading: false, isError: false, data: characterAda });
    mocks.useLocation.mockReturnValue({ isLoading: false, isError: false, data: locationLibrary });
    mocks.useItem.mockReturnValue({ isLoading: false, isError: false, data: itemKey });
    mocks.useBookContributions.mockReturnValue({ isLoading: false, isError: false, data: undefined });
    mocks.updateBook.mockResolvedValue({});
    mocks.updateWritingGoal.mockResolvedValue({});
  });

  test("mantem o sucesso historico quando a meta atual aumenta", () => {
    mocks.useBookDashboard.mockReturnValue({
      isLoading: false,
      isFetching: false,
      isError: false,
      data: historicalGoalDashboard(1_000),
    });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expectHistoricalGoalHitCount("1");
  });

  test("mantem o sucesso historico quando a meta atual e removida", () => {
    mocks.useBookDashboard.mockReturnValue({
      isLoading: false,
      isFetching: false,
      isError: false,
      data: historicalGoalDashboard(null),
    });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expectHistoricalGoalHitCount("1");
    const summary = screen.getByTestId("daily-progress-summary-grid");
    expect(within(summary).queryByText("Sem meta")).not.toBeInTheDocument();
  });
});
