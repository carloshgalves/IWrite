import type { QueryClient } from "@tanstack/react-query";
import type { Scene } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

/**
 * Writes into the shared scene cache under the projection's current state.
 *
 * `setQueryData` does not only carry data: it also puts the query back into `success`. A write
 * performed while the projection is not currently successful — a failed refetch leaves the authority
 * unknown, not withdrawn and not granted — would turn the retained `data`, grant included, back into
 * a projection the surface treats as currently confirmed, without the server having confirmed
 * anything. So while the projection is not successful nothing is written here, and the next
 * successful fetch is what brings content and authority back together.
 *
 * Returns whether the write happened, so a caller that also keeps local state can tell reconciled
 * from refused.
 */
export function updateSceneProjection(
  queryClient: QueryClient,
  sceneId: string,
  updateScene: (cachedScene: Scene | undefined) => Scene
): boolean {
  const queryState = queryClient.getQueryState<Scene>(queryKeys.scene(sceneId));
  if (queryState && queryState.status !== "success") {
    return false;
  }

  queryClient.setQueryData<Scene>(queryKeys.scene(sceneId), updateScene);
  return true;
}

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
 * travelled. It may therefore reconcile data and narrow `canEditContent`, never widen it.
 */
export function applySceneMutationResponse(queryClient: QueryClient, savedScene: Scene): void {
  updateSceneProjection(queryClient, savedScene.id, (cachedScene) => {
    if (!cachedScene || cachedScene.id !== savedScene.id) {
      return savedScene;
    }

    return {
      ...savedScene,
      canEditContent: savedScene.canEditContent === true && cachedScene.canEditContent === true,
    };
  });
}
