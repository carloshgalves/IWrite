import { describe, expect, test } from "vitest";
import { parseWorkspaceMode, workspaceHref } from "@/features/workspace/workspace-navigation";

describe("workspace navigation (#248)", () => {
  test("uses Cenas for a missing, invalid or unsafe tab", () => {
    expect(parseWorkspaceMode(null)).toBe("scenes");
    expect(parseWorkspaceMode("unknown")).toBe("scenes");
    expect(parseWorkspaceMode("__proto__")).toBe("scenes");
    expect(parseWorkspaceMode("notebook")).toBe("notebook");
    expect(parseWorkspaceMode("locations")).toBe("locations");
  });

  test("preserves the deep-linked Scene when switching workspace tabs", () => {
    const current = new URLSearchParams("sceneId=scene-123");
    expect(workspaceHref("book-1", current, "notebook")).toBe(
      "/books/book-1?sceneId=scene-123&tab=notebook"
    );
    expect(workspaceHref("book-1", new URLSearchParams("sceneId=scene-123&tab=notebook"), "scenes")).toBe(
      "/books/book-1?sceneId=scene-123"
    );
  });

  test("selects scenes and cleans obsolete tab parameters without dropping other query state", () => {
    const current = new URLSearchParams("tab=kanban&view=compact");
    expect(workspaceHref("book-1", current, "scenes", "scene-new")).toBe(
      "/books/book-1?view=compact&sceneId=scene-new"
    );
    expect(workspaceHref("book-1", new URLSearchParams("tab=notebook&sceneId=removed"), "notebook", null)).toBe(
      "/books/book-1?tab=notebook"
    );
  });

  test("handles encoded book and scene references without path injection", () => {
    expect(workspaceHref("book?unsafe", new URLSearchParams(), "scenes", "scene&a")).toBe(
      "/books/book%3Funsafe?sceneId=scene%26a"
    );
  });
});
