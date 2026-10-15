import { fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { OutlineSidebar } from "@/features/outline/components/outline-sidebar";
import type { BookOutline } from "@/features/outline/types";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({ getOutline: vi.fn() }));

vi.mock("@/features/outline/api/outline-api", async () => {
  const actual = await vi.importActual<typeof import("@/features/outline/api/outline-api")>(
    "@/features/outline/api/outline-api"
  );
  return { ...actual, getOutline: mocks.getOutline };
});

const sections: BookOutline["sections"] = Array.from({ length: 12 }, (_, section) => ({
  id: `part-${section}`,
  title: `Part ${section + 1}`,
  type: "PART",
  sortOrder: section,
  wordCount: 0,
  chapters: Array.from({ length: 2 }, (_, chapter) => ({
    id: `chapter-${section}-${chapter}`,
    title: `Chapter ${section + 1}-${chapter + 1}`,
    summary: null,
    sortOrder: chapter,
    wordCount: 0,
    scenes: Array.from({ length: 4 }, (_, scene) => ({
      id: `scene-${section}-${chapter}-${scene}`,
      title: `Scene ${section + 1}-${chapter + 1}-${scene + 1}`,
      sortOrder: scene,
      wordCount: 0,
      status: "DRAFT" as const,
      povCharacterId: null,
      povCharacterName: null,
      planningGaps: [],
    })),
  })),
}));

const outline: BookOutline = {
  id: "book-large",
  title: "Large outline",
  status: "WRITING",
  wordCount: 0,
  sections,
};
const deepSceneId = "scene-11-1-3";
const deepSceneTitle = "Scene 12-2-4";

function rect(top: number, bottom: number): DOMRect {
  return {
    x: 0, y: top, top, bottom, left: 0, right: 320,
    width: 320, height: bottom - top, toJSON: () => ({}),
  } as DOMRect;
}

describe("OutlineSidebar selected scene visibility (#249)", () => {
  const getRect = Element.prototype.getBoundingClientRect;
  const windowScroll = window.scrollTo;

  beforeEach(() => {
    mocks.getOutline.mockResolvedValue(outline);
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      if (this.getAttribute("data-testid") === "outline-scrollport") return rect(0, 200);
      if (this.getAttribute("aria-current") === "location") return rect(700, 740);
      return getRect.call(this);
    });
    vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    window.scrollTo = windowScroll;
  });

  function mount(selectedSceneId: string | null) {
    return <OutlineSidebar
      bookId="book-large"
      selectedSceneId={selectedSceneId}
      canMutateStructure={false}
      onSelectScene={vi.fn()}
    />;
  }

  test("reveals a deep-linked scene within the sidebar alone after async outline load", async () => {
    renderWithClient(mount(deepSceneId));

    const scrollport = await screen.findByTestId("outline-scrollport");
    Object.defineProperty(scrollport, "clientHeight", { configurable: true, value: 200 });

    // Rerender via query data arriving triggers the layout effect; the deep row must be
    // selected in the real tree without a user click or a remount of the editor.
    const selected = await screen.findByRole("button", { name: new RegExp(deepSceneTitle) });
    expect(selected).toHaveAttribute("aria-current", "location");
    await waitFor(() => expect(scrollport.scrollTop).toBeGreaterThan(0));
    expect(window.scrollTo).not.toHaveBeenCalled();
  });

  test("expands collapsed ancestors before revealing and does not seize manual scroll again", async () => {
    const view = renderWithClient(mount(null));
    const scrollport = await screen.findByTestId("outline-scrollport");
    Object.defineProperty(scrollport, "clientHeight", { configurable: true, value: 200 });
    await screen.findByRole("button", { name: new RegExp(deepSceneTitle) });

    fireEvent.click(screen.getByRole("button", { name: "Recolher capítulo Chapter 12-2" }).first());
    fireEvent.click(screen.getByRole("button", { name: "Recolher seção Part 12" }).first());
    expect(screen.queryByRole("button", { name: new RegExp(deepSceneTitle) })).not.toBeInTheDocument();

    view.rerender(mount(deepSceneId));
    const selected = await screen.findByRole("button", { name: new RegExp(deepSceneTitle) });
    expect(selected).toHaveAttribute("aria-current", "location");
    await waitFor(() => expect(scrollport.scrollTop).toBeGreaterThan(0));

    scrollport.scrollTop = 55; // the user manually scrolls after the scene is revealed
    view.rerender(mount(deepSceneId));
    expect(scrollport.scrollTop).toBe(55);
    expect(window.scrollTo).not.toHaveBeenCalled();
  });

  test("does not scroll or change focus for an invalid scene selection", async () => {
    renderWithClient(mount("scene-outside-this-book"));
    const scrollport = await screen.findByTestId("outline-scrollport");
    Object.defineProperty(scrollport, "clientHeight", { configurable: true, value: 200 });
    expect(scrollport.scrollTop).toBe(0);
    expect(screen.queryByRole("button", { current: "location" })).not.toBeInTheDocument();
    expect(window.scrollTo).not.toHaveBeenCalled();
  });
});
