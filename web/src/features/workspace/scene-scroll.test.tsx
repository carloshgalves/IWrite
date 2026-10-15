import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, test } from "vitest";
import {
  clearSceneScrollPositions,
  readSceneScroll,
  useSceneScrollRestoration,
  writeSceneScroll,
} from "@/features/workspace/scene-scroll";

function Scrollport({ bookId = "book-A", sceneId = "scene-1", ready = true }: {
  bookId?: string;
  sceneId?: string;
  ready?: boolean;
}) {
  const scroll = useSceneScrollRestoration<HTMLDivElement>(bookId, sceneId, "scene-shell", ready ? sceneId : null);
  return <div ref={scroll.ref} onScroll={scroll.onScroll} data-testid="scrollport" />;
}

describe("scene scroll restoration (#248)", () => {
  beforeEach(() => window.sessionStorage.clear());

  test("restores the same scene on remount (refresh) without resetting on rerender", () => {
    const view = render(<Scrollport />);
    const first = screen.getByTestId("scrollport");
    first.scrollTop = 256;
    fireEvent.scroll(first);
    expect(readSceneScroll("book-A", "scene-1", "scene-shell")).toBe(256);

    view.unmount();
    const next = render(<Scrollport />);
    const restored = screen.getByTestId("scrollport");
    expect(restored.scrollTop).toBe(256);
    restored.scrollTop = 309;
    fireEvent.scroll(restored);
    next.rerender(<Scrollport />);
    expect(restored.scrollTop).toBe(309);
  });

  test("scroll state is scoped to book, scene and surface", () => {
    writeSceneScroll("book-A", "scene-1", "scene-shell", 190);
    expect(readSceneScroll("book-A", "scene-2", "scene-shell")).toBe(0);
    expect(readSceneScroll("book-B", "scene-1", "scene-shell")).toBe(0);
    expect(readSceneScroll("book-A", "scene-1", "scene-content")).toBe(0);
  });

  test("restoration waits until the scene is ready", () => {
    writeSceneScroll("book-A", "scene-1", "scene-shell", 410);
    const view = render(<Scrollport ready={false} />);
    const element = screen.getByTestId("scrollport");
    expect(element.scrollTop).toBe(0);
    view.rerender(<Scrollport ready />);
    expect(element.scrollTop).toBe(410);
  });

  test("session change deletes only IWrite scroll keys", () => {
    window.sessionStorage.setItem("other-product-key", "keep");
    writeSceneScroll("book-A", "scene-1", "scene-shell", 230);
    clearSceneScrollPositions();
    expect(readSceneScroll("book-A", "scene-1", "scene-shell")).toBe(0);
    expect(window.sessionStorage.getItem("other-product-key")).toBe("keep");
  });

  test("untrusted storage values cannot cause arbitrary scroll offsets", () => {
    writeSceneScroll("book-A", "scene-1", "scene-content", Number.POSITIVE_INFINITY);
    expect(readSceneScroll("book-A", "scene-1", "scene-content")).toBe(0);
    window.sessionStorage.setItem("iwrite.sceneScroll.v1:book-A:scene-1:scene-shell", "-50");
    expect(readSceneScroll("book-A", "scene-1", "scene-shell")).toBe(0);
  });
});
