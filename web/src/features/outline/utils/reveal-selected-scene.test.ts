import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { revealSelectedOutlineScene } from "@/features/outline/utils/reveal-selected-scene";

function rect(top: number, bottom: number): DOMRect {
  return {
    x: 0, y: top, top, bottom, left: 0, right: 300,
    width: 300, height: bottom - top, toJSON: () => ({}),
  } as DOMRect;
}

describe("revealSelectedOutlineScene (#249)", () => {
  let scrollport: HTMLDivElement;
  let selected: HTMLButtonElement;

  beforeEach(() => {
    scrollport = document.createElement("div");
    scrollport.setAttribute("data-testid", "outline-scrollport");
    selected = document.createElement("button");
    selected.setAttribute("aria-current", "location");
    scrollport.appendChild(selected);
    document.body.appendChild(scrollport);
    Object.defineProperty(scrollport, "clientHeight", { configurable: true, value: 200 });
    vi.spyOn(scrollport, "getBoundingClientRect").mockReturnValue(rect(100, 300));
  });

  afterEach(() => {
    scrollport.remove();
    vi.restoreAllMocks();
  });

  test("reveals a deep selected scene below the outline viewport without moving the editor/body", () => {
    vi.spyOn(selected, "getBoundingClientRect").mockReturnValue(rect(500, 540));
    scrollport.scrollTop = 0;
    const editor = document.createElement("div");
    editor.scrollTop = 415;
    document.body.appendChild(editor);
    const windowScroll = vi.spyOn(window, "scrollTo").mockImplementation(() => {});
    const scrollIntoView = vi.fn();
    Object.defineProperty(selected, "scrollIntoView", { configurable: true, value: scrollIntoView });

    expect(revealSelectedOutlineScene(scrollport)).toBe(true);
    expect(scrollport.scrollTop).toBe(248);
    expect(editor.scrollTop).toBe(415);
    expect(windowScroll).not.toHaveBeenCalled();
    expect(scrollIntoView).not.toHaveBeenCalled();
    editor.remove();
  });

  test("scrolls upwards only when the selected scene is above the viewport", () => {
    vi.spyOn(selected, "getBoundingClientRect").mockReturnValue(rect(30, 70));
    scrollport.scrollTop = 300;
    expect(revealSelectedOutlineScene(scrollport)).toBe(true);
    expect(scrollport.scrollTop).toBe(222);
  });

  test("keeps manual sidebar scroll untouched when the scene is already fully visible", () => {
    vi.spyOn(selected, "getBoundingClientRect").mockReturnValue(rect(160, 200));
    scrollport.scrollTop = 425;
    expect(revealSelectedOutlineScene(scrollport)).toBe(true);
    expect(scrollport.scrollTop).toBe(425);
  });

  test("does nothing while the selected scene is collapsed, absent or scrollport unmeasured", () => {
    scrollport.scrollTop = 125;
    selected.remove();
    expect(revealSelectedOutlineScene(scrollport)).toBe(false);
    expect(scrollport.scrollTop).toBe(125);
    scrollport.append(selected);
    Object.defineProperty(scrollport, "clientHeight", { configurable: true, value: 0 });
    expect(revealSelectedOutlineScene(scrollport)).toBe(false);
    expect(scrollport.scrollTop).toBe(125);
  });
});
