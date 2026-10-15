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
    let observer: ResizeObserver | null = null;
    function restore() {
      if (stopped) return;
      element!.scrollTop = position;
      if (element!.scrollTop >= position - 1) {
        observer?.disconnect();
        stopped = true;
      }
    }

    // This effect runs after hydration and when the SceneEditor has finished loading the scene.
    restore();
    if (!stopped && typeof ResizeObserver !== "undefined") {
      observer = new ResizeObserver(restore);
      observer.observe(element);
    }
    // A bounded observation protects the user's later manual scrolling from being overridden.
    const timeout = window.setTimeout(() => {
      stopped = true;
      observer?.disconnect();
    }, 1_500);
    return () => {
      stopped = true;
      observer?.disconnect();
      window.clearTimeout(timeout);
    };
  }, [bookId, sceneId, surface, readyToken]);

  function onScroll(event: UIEvent<T>) {
    if (sceneId) writeSceneScroll(bookId, sceneId, surface, event.currentTarget.scrollTop);
  }

  return { ref, onScroll };
}
