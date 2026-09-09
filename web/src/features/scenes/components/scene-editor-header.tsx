import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import type { Scene } from "@/features/scenes/types";

export type ContentSaveStatus = "saved" | "editing" | "saving" | "error";

type SceneEditorHeaderProps = {
  scene: Scene;
  metadataFormId: string;
  title: string;
  /** Structure mutations (scene metadata, deletion) are offered only with the capability. */
  canMutateStructure: boolean;
  /** Whether a content save may be attempted at all for this book. */
  canEditContent: boolean;
  contentSaveStatus: ContentSaveStatus;
  metadataPending: boolean;
  contentPending: boolean;
  deletePending: boolean;
  deleteError: boolean;
  isFocusMode?: boolean;
  isFullscreenAvailable?: boolean;
  isFullscreenActive?: boolean;
  onEnterFocusMode?: () => void;
  onExitFocusMode?: () => void;
  onToggleFullscreen?: () => void;
  onSaveContent: () => void;
  onOpenHistory: () => void;
  onDeleteScene: (sceneTitle: string) => void;
};

const saveStatusLabels: Record<ContentSaveStatus, string> = {
  saved: "Salvo",
  editing: "Digitando...",
  saving: "Salvando...",
  error: "Erro ao salvar",
};

const saveStatusClasses: Record<ContentSaveStatus, string> = {
  saved: "border-emerald-200 bg-emerald-50 text-emerald-700",
  editing: "border-amber-200 bg-amber-50 text-amber-700",
  saving: "border-zinc-200 bg-zinc-50 text-zinc-600",
  error: "border-red-200 bg-red-50 text-red-700",
};

export function SceneEditorHeader({
  scene,
  metadataFormId,
  title,
  canMutateStructure,
  canEditContent,
  contentSaveStatus,
  metadataPending,
  contentPending,
  deletePending,
  deleteError,
  isFocusMode = false,
  isFullscreenAvailable = false,
  isFullscreenActive = false,
  onEnterFocusMode,
  onExitFocusMode,
  onToggleFullscreen,
  onSaveContent,
  onOpenHistory,
  onDeleteScene,
}: SceneEditorHeaderProps) {
  return (
    <header className="border-b border-zinc-200 bg-white px-4 py-4 md:px-7">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[11px] font-medium uppercase tracking-wide text-zinc-500">Cena</p>
          <h1 className="mt-1 truncate text-lg font-semibold text-zinc-950 md:text-xl">{scene.title}</h1>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <Badge>{scene.status}</Badge>
            <Badge variant="outline">{scene.wordCount} palavras</Badge>
            {canEditContent ? (
              <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${saveStatusClasses[contentSaveStatus]}`}>
                {saveStatusLabels[contentSaveStatus]}
              </span>
            ) : (
              <span className="rounded-full border border-zinc-200 bg-zinc-50 px-2.5 py-1 text-xs font-medium text-zinc-600">
                Somente leitura
              </span>
            )}
          </div>
        </div>

        <div className="flex shrink-0 flex-wrap items-center gap-1.5 rounded-md border border-zinc-200 bg-zinc-50/70 p-1">
          {isFocusMode ? (
            <>
              {isFullscreenAvailable ? (
                <Button type="button" variant="secondary" size="sm" onClick={onToggleFullscreen}>
                  {isFullscreenActive ? "Sair da tela cheia" : "Tela cheia"}
                </Button>
              ) : null}
              <Button type="button" variant="primary" size="sm" onClick={onExitFocusMode}>
                Sair do foco
              </Button>
            </>
          ) : (
            <Button type="button" variant="secondary" size="sm" onClick={onEnterFocusMode}>
              Modo foco
            </Button>
          )}
          {canMutateStructure ? (
            <Button
              type="submit"
              form={metadataFormId}
              variant="ghost"
              size="sm"
              disabled={metadataPending || !title.trim()}
            >
              {metadataPending ? "Salvando..." : "Salvar cena"}
            </Button>
          ) : null}
          {canEditContent ? (
            <Button type="button" variant="secondary" size="sm" onClick={onSaveContent} disabled={contentPending}>
              {contentPending ? "Salvando..." : "Salvar conteúdo"}
            </Button>
          ) : null}
          <Button type="button" variant="ghost" size="sm" onClick={onOpenHistory}>
            Histórico
          </Button>
          {canMutateStructure ? (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="text-red-600 hover:bg-red-50"
              onClick={() => onDeleteScene(scene.title)}
              disabled={deletePending}
            >
              {deletePending ? "Excluindo..." : "Excluir cena"}
            </Button>
          ) : null}
        </div>
      </div>

      {deleteError ? (
        <FeedbackMessage variant="error" className="mt-3">
          Não foi possível excluir a cena. Verifique a API e tente novamente.
        </FeedbackMessage>
      ) : null}
    </header>
  );
}
