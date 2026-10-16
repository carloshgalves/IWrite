import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { dashboardWithScenes } from "@/test/fixtures";
import { queryKeys } from "@/lib/query/keys";
import type { BookContributionDashboardResponse } from "@/features/dashboard/types";

const mocks = vi.hoisted(() => ({
  useBookDashboard: vi.fn(),
  getBookContributions: vi.fn(),
  useCharacter: vi.fn(),
  useLocation: vi.fn(),
  useItem: vi.fn(),
}));

// Keep useBookContributions real: a mocked hook cannot reproduce key-specific cached data
// surviving a 404 in another key.
vi.mock("@/features/dashboard/api/dashboard-hooks", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/dashboard/api/dashboard-hooks")>()),
  useBookDashboard: mocks.useBookDashboard,
}));
vi.mock("@/features/dashboard/api/dashboard-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/dashboard/api/dashboard-api")>()),
  getBookContributions: mocks.getBookContributions,
}));
vi.mock("@/features/characters/api/characters-hooks", () => ({ useCharacter: mocks.useCharacter }));
vi.mock("@/features/locations/api/locations-hooks", () => ({ useLocation: mocks.useLocation }));
vi.mock("@/features/items/api/items-hooks", () => ({ useItem: mocks.useItem }));

const allContributions: BookContributionDashboardResponse = {
  period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
  scope: "ALL_CONTRIBUTORS",
  selectedContributor: null,
  availableContributors: [
    { userId: "user-1", displayName: "Carlos" },
    { userId: "user-2", displayName: "Bruna" },
  ],
  summary: {
    productiveWords: 300,
    manuscriptAdjustments: 0,
    writingDays: 1,
    contributorsCount: 2,
    distinctScenes: 1,
    distinctChapters: 1,
  },
  dailySeries: [{ date: "2026-05-14", productiveWords: 300, manuscriptAdjustments: 0 }],
  origins: [{
    sceneId: "scene-1",
    sceneTitle: "A chave aparece",
    chapterId: "chapter-1",
    chapterTitle: "Capitulo 1",
    productiveWords: 300,
    manuscriptAdjustments: 0,
    writingDays: 1,
  }],
};

describe("BookContributionCard — real React Query cache", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useBookDashboard.mockReturnValue({
      isLoading: false,
      isError: false,
      data: dashboardWithScenes,
    });
    mocks.useCharacter.mockReturnValue({ isLoading: false, isError: false, data: undefined });
    mocks.useLocation.mockReturnValue({ isLoading: false, isError: false, data: undefined });
    mocks.useItem.mockReturnValue({ isLoading: false, isError: false, data: undefined });
  });

  test("404 on selected key cannot resurface cached all-scope data before reauthorization", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 60_000 } },
    });
    let resolveFreshAll: ((data: BookContributionDashboardResponse) => void) | undefined;
    let allFetches = 0;

    mocks.getBookContributions.mockImplementation((_bookId: string, _period: string, contributorId?: string) => {
      if (contributorId) {
        return Promise.reject(new Error("404: access revoked"));
      }
      allFetches += 1;
      if (allFetches === 1) {
        return Promise.resolve(allContributions);
      }
      return new Promise<BookContributionDashboardResponse>((resolve) => {
        resolveFreshAll = resolve;
      });
    });

    render(
      <QueryClientProvider client={client}>
        <BookDashboard bookId="book-1" />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByText("A chave aparece")).toBeInTheDocument());
    expect(screen.getByLabelText("Contribuidor")).toHaveTextContent("Bruna");
    expect(client.getQueryData([...queryKeys.bookContributions("book-1"), "7d", "all"]))
      .toEqual(allContributions);

    fireEvent.change(screen.getByLabelText("Contribuidor"), { target: { value: "user-2" } });
    await waitFor(() =>
      expect(screen.getByText("Não foi possível carregar as contribuições registradas.")).toBeInTheDocument(),
    );
    expect(screen.queryByText("A chave aparece")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Voltar a todos os contribuidores" }));

    await waitFor(() => expect(allFetches).toBe(2));
    // The old all-scope query existed and was fresh before the 404. No cached private
    // names or metrics may appear while the new authorization-dependent request is pending.
    expect(client.getQueryData([...queryKeys.bookContributions("book-1"), "7d", "all"]))
      .toBeUndefined();
    expect(screen.queryByText("A chave aparece")).not.toBeInTheDocument();
    expect(screen.queryByText("Palavras produtivas")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Contribuidor")).not.toBeInTheDocument();

    resolveFreshAll?.({ ...allContributions, summary: { ...allContributions.summary, productiveWords: 12 } });
    await waitFor(() => expect(screen.getByText("12")).toBeInTheDocument());
    expect(screen.getByLabelText("Contribuidor")).toHaveTextContent("Bruna");
    client.clear();
  });
});
