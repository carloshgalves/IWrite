import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import React, { type ReactNode } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { updateScenePlanning } from "@/features/scene-planning/api/scene-planning-api";
import { useUpdateScenePlanning } from "@/features/scene-planning/hooks/use-scene-planning";
import type { ScenePlanningRequest } from "@/features/scene-planning/types";
import type { Scene } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";
import { sceneForPlanning } from "@/test/fixtures";

vi.mock("@/features/scene-planning/api/scene-planning-api", () => ({
  updateScenePlanning: vi.fn(),
}));

const updateScenePlanningMock = vi.mocked(updateScenePlanning);

describe("useUpdateScenePlanning", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    updateScenePlanningMock.mockResolvedValue(sceneForPlanning);
  });

  test("invalidates scene, outline, and dashboard queries after a successful planning save", async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    const invalidateQueriesSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateScenePlanning("book-1", sceneForPlanning.id), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    });

    await act(async () => {
      await result.current.mutateAsync({
        goal: "Goal",
        conflict: "Conflict",
        outcome: "Outcome",
        planningNotes: null,
        povCharacterId: null,
        participantCharacterIds: [],
        mainLocationId: null,
        itemIds: [],
      });
    });

    await waitFor(() => {
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.scene(sceneForPlanning.id) });
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.outline("book-1") });
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.bookDashboard("book-1") });
    });
  });
});

/**
 * The planning save writes into the same scene cache the editor reads its effective authority from,
 * so a response that lands after a newer projection must not put back a grant that projection took
 * away. Planning authorization itself stays where #209 will move it; what is proved here is only the
 * effect a late planning response has on the shared scene cache.
 */
describe("useUpdateScenePlanning versus the newest projected authority", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test("uma resposta de planning atrasada nao ressuscita a autoridade retirada", async () => {
    const deferred = createDeferred<Scene>();
    updateScenePlanningMock.mockImplementation(() => deferred.promise);
    const queryClient = createTestQueryClient();
    queryClient.setQueryData<Scene>(sceneKey, { ...sceneForPlanning, canEditContent: true });

    const { result } = renderPlanningHook(queryClient);
    act(() => {
      result.current.mutate(planningPayload);
    });
    await waitFor(() => {
      expect(updateScenePlanningMock).toHaveBeenCalledTimes(1);
    });

    // While the planning save is in flight, a newer projection withdraws the authority over the text.
    queryClient.setQueryData<Scene>(sceneKey, { ...sceneForPlanning, canEditContent: false });

    await act(async () => {
      deferred.resolve({ ...sceneForPlanning, goal: "Goal", canEditContent: true });
      await deferred.promise;
    });

    const cachedScene = queryClient.getQueryData<Scene>(sceneKey);
    expect(cachedScene?.canEditContent).toBe(false);
    // The response is still the newest word about the planning fields it just wrote.
    expect(cachedScene?.goal).toBe("Goal");
  });

  test("uma resposta de planning atrasada nao transforma a falha de refetch em concessao", async () => {
    const deferred = createDeferred<Scene>();
    updateScenePlanningMock.mockImplementation(() => deferred.promise);
    const queryClient = createTestQueryClient();
    queryClient.setQueryData<Scene>(sceneKey, { ...sceneForPlanning, canEditContent: true });

    const { result } = renderPlanningHook(queryClient);
    act(() => {
      result.current.mutate(planningPayload);
    });
    await waitFor(() => {
      expect(updateScenePlanningMock).toHaveBeenCalledTimes(1);
    });

    // A failed refetch leaves the authority unknown, not granted.
    await act(async () => {
      await queryClient
        .fetchQuery({ queryKey: sceneKey, queryFn: () => Promise.reject(new Error("network down")) })
        .catch(() => undefined);
    });
    expect(queryClient.getQueryState<Scene>(sceneKey)?.status).toBe("error");

    await act(async () => {
      deferred.resolve({ ...sceneForPlanning, goal: "Goal", canEditContent: true });
      await deferred.promise;
    });

    expect(queryClient.getQueryState<Scene>(sceneKey)?.status).toBe("error");
    expect(queryClient.getQueryData<Scene>(sceneKey)?.goal).toBeNull();
  });

  // Control: with the authority still projected, the very same delayed response still reconciles the
  // planning fields. Without it, a writer that simply stopped applying responses would pass too.
  test("com a autoridade preservada a mesma resposta atrasada reconcilia os dados de planning", async () => {
    const deferred = createDeferred<Scene>();
    updateScenePlanningMock.mockImplementation(() => deferred.promise);
    const queryClient = createTestQueryClient();
    queryClient.setQueryData<Scene>(sceneKey, { ...sceneForPlanning, canEditContent: true });

    const { result } = renderPlanningHook(queryClient);
    act(() => {
      result.current.mutate(planningPayload);
    });
    await waitFor(() => {
      expect(updateScenePlanningMock).toHaveBeenCalledTimes(1);
    });

    queryClient.setQueryData<Scene>(sceneKey, { ...sceneForPlanning, canEditContent: true });

    await act(async () => {
      deferred.resolve({ ...sceneForPlanning, goal: "Goal", canEditContent: true });
      await deferred.promise;
    });

    const cachedScene = queryClient.getQueryData<Scene>(sceneKey);
    expect(cachedScene?.goal).toBe("Goal");
    expect(cachedScene?.canEditContent).toBe(true);
  });
});

const sceneKey = queryKeys.scene(sceneForPlanning.id);

const planningPayload: ScenePlanningRequest = {
  goal: "Goal",
  conflict: "Conflict",
  outcome: "Outcome",
  planningNotes: null,
  povCharacterId: null,
  participantCharacterIds: [],
  mainLocationId: null,
  itemIds: [],
};

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

function renderPlanningHook(queryClient: QueryClient) {
  return renderHook(() => useUpdateScenePlanning("book-1", sceneForPlanning.id), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
}

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}
