export const WORKSPACE_MODES = [
  "overview",
  "storyboard",
  "kanban",
  "scenes",
  "characters",
  "locations",
  "items",
  "notebook",
] as const;

export type WorkspaceMode = (typeof WORKSPACE_MODES)[number];

/** Unknown or removed tabs are safe to open as the default scene view. */
export function parseWorkspaceMode(value: string | null | undefined): WorkspaceMode {
  return WORKSPACE_MODES.find((mode) => mode === value) ?? "scenes";
}

/**
 * The URL owns tab and scene navigation, not localStorage. A copied URL or refresh therefore
 * restores the same public UI selection, but the backend still decides which book/scene is visible.
 * An omitted tab means the historical default ("scenes"), preserving existing deep links.
 */
export function workspaceHref(
  bookId: string,
  currentSearchParams: { toString(): string },
  mode: WorkspaceMode,
  sceneId?: string | null
): string {
  const params = new URLSearchParams(currentSearchParams.toString());

  if (sceneId === null) {
    params.delete("sceneId");
  } else if (sceneId !== undefined) {
    params.set("sceneId", sceneId);
  }

  if (mode === "scenes") {
    params.delete("tab");
  } else {
    params.set("tab", mode);
  }

  const query = params.toString();
  return `/books/${encodeURIComponent(bookId)}${query ? `?${query}` : ""}`;
}
