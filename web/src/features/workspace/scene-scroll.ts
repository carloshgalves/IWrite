"use client";

import { useLayoutEffect, useRef, type UIEvent } from "react";

type ScrollSurface = "scene-shell" | "scene-content";
const PREFIX = "iwrite.sceneScroll.v1:";
const MAX_SCROLL = 10_000_000;

function key(bookId: string, sceneId: string, surface: ScrollSurface): string {
  return `${PREFIX}${encodeURIComponent(bookId)}:${encodeURIComponent(sceneId)}:${surface}`;
}

/** Only a numeric position is kept per browser tab. Never store manuscript text or server data. */
export function readSceneScroll(bookId: string, sceneId: string, surface: ScrollSurface): number {
  try {
    const raw = window.sessionStorage.getItem(key(bookId, sceneId, surface));
    if (raw === null) return 0;
    const value = Number(raw);
    return Number.isFinite(value) && value >= 0 && value <= MAX_SCROLL ? value : 0;
  } catch {
    return 0; // storage disabled
  }
}

export function writeSceneScroll(bookId: string, sceneId: string, surface: ScrollSurface, scrollTop: number): void {
  if (!Number.isFinite(scrollTop) || scrollTop < 0) return;
  try {
    window.sessionStorage.setItem(key(bookId, sceneId, surface), String(Math.min(Math.round(scrollTop), MAX_SCROLL)));
  } catch {
    // A denied/quota-limited storage API must never interfere with writing or reading.
  }
}

/** Invoked on login, logout, expired session and cross-tab identity announcements. */
export function clearSceneScrollPositions(): void {
  if (typeof window === "undefined") return;
  try {
    const storage = window.sessionStorage;
    for (let i = storage.length - 1; i >= 0; i--) {
      const entry = storage.key(i);
      if (entry?.startsWith(PREFIX)) storage.removeItem(entry);
    }
  } catch {
    // Session data can be unavailable in private contexts. There is no privileged data here.
  }
}

/**
 * Restores each scrollport only when the selected scene/content becomes ready. Content in Tiptap
 * may mount one frame after the containing element; ResizeObserver retries briefly until there
 * is room to scroll. No route changes, selection changes or scroll resets on editor keystrokes.
 */
export function useSceneScrollRestoration<T extends HTMLElement>(
  bookId: string,
  sceneId: string | null,
  surface: ScrollSurface,
  readyToken: string | null
) {
  const ref = useRef<T | null>(null);

  useLayoutEffect(() => {
    if (!sceneId || !readyToken) return;
    const element = ref.current;
    if (!element) return;
    const position = readSceneScroll(bookId, sceneId, surface);
    if (!position) return;

    let stopped = false;
    let resizeObserver: ResizeObserver | null = null;
    let mutationObserver: MutationObserver | null = null;

    function stop() {
      stopped = true;
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
    }

    function restore() {
      if (stopped) return;
      element!.scrollTop = position;
      // A partially hydrated editor can temporarily have a small positive scroll range
      // (e.g. 74px) before Tiptap inserts the manuscript. Reaching that temporary limit
      // does NOT prove that the saved reading position (e.g. 220px) was restored.
      // Keep observing until the full position is reached or the bounded retry expires.
      // For a genuinely shorter scene the browser naturally clamps to its final maximum.
      if (element!.scrollTop >= position - 1) {
        stop();
      }
    }

    // ResizeObserver alone misses Tiptap inserting the actual editable DOM into an already
    // fixed-height scrollport. MutationObserver catches that delayed mount without touching
    // editor selection or commands.
    restore();
    if (!stopped && typeof ResizeObserver !== "undefined") {
      resizeObserver = new ResizeObserver(restore);
      resizeObserver.observe(element);
    }
    if (!stopped && typeof MutationObserver !== "undefined") {
      mutationObserver = new MutationObserver(restore);
      mutationObserver.observe(element, { subtree: true, childList: true });
    }
    // A bounded observation protects later manual scrolling from being overridden.
    const timeout = window.setTimeout(stop, 1_500);
    return () => {
      stop();
      window.clearTimeout(timeout);
    };
  }, [bookId, sceneId, surface, readyToken]);

  function onScroll(event: UIEvent<T>) {
    if (sceneId) writeSceneScroll(bookId, sceneId, surface, event.currentTarget.scrollTop);
  }

  return { ref, onScroll };
}
