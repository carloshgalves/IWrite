import type { QueryClient } from "@tanstack/react-query";
import type { Scene } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

/**
 * Writes a scene mutation response into the shared scene cache without letting it put back an
 * authority a newer projection has already taken away.
 *
 * Every writer of `queryKeys.scene(...)` shares this rule, because the cache it writes is the one
 * `SceneEditor` reads its effective authority from: a response applied raw by any surface — the
 * board's status move, a planning save — reopens controls and re-arms autosave on a grant nothing
 * has confirmed since.
 *
 * A mutation response carries the grant its mutation was decided with, so it is the newest word
 * about the data it just wrote and never about a permission that may have been withdrawn while it
 * travelled. It may therefore reconcile data and narrow `canEditContent`, never widen it, and while
 * the projection is not currently successful — an error leaves the authority unknown, not granted —
 * it does not write at all. The next successful fetch is what may widen the authority again.
 */
export function applySceneMutationResponse(queryClient: QueryClient, savedScene: Scene): void {
  const queryState = queryClient.getQueryState<Scene>(queryKeys.scene(savedScene.id));
  if (queryState && queryState.status !== "success") {
    return;
  }

  queryClient.setQueryData<Scene>(queryKeys.scene(savedScene.id), (cachedScene) => {
    if (!cachedScene || cachedScene.id !== savedScene.id) {
      return savedScene;
    }

    return {
      ...savedScene,
      canEditContent: savedScene.canEditContent === true && cachedScene.canEditContent === true,
    };
  });
}
