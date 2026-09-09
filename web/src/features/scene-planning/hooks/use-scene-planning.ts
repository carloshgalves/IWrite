"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateScenePlanning } from "@/features/scene-planning/api/scene-planning-api";
import type { ScenePlanningRequest } from "@/features/scene-planning/types";
import { applySceneMutationResponse } from "@/features/scenes/cache/apply-scene-mutation-response";
import { queryKeys } from "@/lib/query/keys";

export function useUpdateScenePlanning(bookId: string, sceneId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ScenePlanningRequest) => updateScenePlanning(sceneId, payload),
    onSuccess: (scene) => {
      applySceneMutationResponse(queryClient, scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.scene(scene.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.bookDashboard(bookId) });
    },
  });
}
