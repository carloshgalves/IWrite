import { beforeEach, describe, expect, test, vi } from "vitest";
import { updateWritingGoal } from "@/features/writing-goal/api/writing-goal-api";

const mocks = vi.hoisted(() => ({ apiRequest: vi.fn() }));

vi.mock("@/lib/api/client", () => ({ apiRequest: mocks.apiRequest }));

describe("personal writing goal API", () => {
  beforeEach(() => {
    mocks.apiRequest.mockReset().mockResolvedValue(undefined);
  });

  test("updates the goal without touching shared book settings", async () => {
    const request = { expectedRevision: 4, dailyTargetWordCount: 750 };
    await updateWritingGoal("book-1", request);
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books/book-1/writing-goal", {
      method: "PATCH",
      body: request,
    });
  });

  test("clears the target with an explicit null instead of a zero", async () => {
    await updateWritingGoal("book-1", { expectedRevision: 4, dailyTargetWordCount: null });
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books/book-1/writing-goal", {
      method: "PATCH",
      body: { expectedRevision: 4, dailyTargetWordCount: null },
    });
  });
});
