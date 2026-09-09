import { FeedbackMessage } from "@/components/ui/feedback-message";
import type { ContentSaveStatus } from "@/features/scenes/components/scene-editor-header";
import { TiptapEditor } from "@/features/scenes/editor/tiptap-editor";

type SceneContentEditorProps = {
  contentKey: string;
  sourceSceneId: string;
  contentJson: string;
  contentText: string;
  /** True when the backend grants no path to a content save; the editor then only displays the text. */
  readOnly: boolean;
  wordCount: number;
  isSuccess: boolean;
  isError: boolean;
  saveStatus: ContentSaveStatus;
  isFocusMode?: boolean;
  onContentChange: (sourceSceneId: string, contentJson: string, contentText: string) => void;
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

export function SceneContentEditor({
  contentKey,
  sourceSceneId,
  contentJson,
  contentText,
  readOnly,
  wordCount,
  isSuccess,
  isError,
  saveStatus,
  isFocusMode = false,
  onContentChange,
}: SceneContentEditorProps) {
  return (
    <div className={`grid h-full min-h-0 gap-4 overflow-y-auto bg-white ${isFocusMode ? "px-4 py-5 md:px-8 md:py-7" : "px-4 py-6 md:px-7"}`}>
      <div className={`mx-auto grid w-full gap-4 ${isFocusMode ? "max-w-4xl" : "max-w-5xl"}`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-zinc-950">Conteúdo textual</h2>
            <p className="text-xs text-zinc-500">
              {readOnly
                ? "Somente leitura: este livro não autoriza você a alterar o conteúdo desta cena."
                : "Salvamento manual. O contador é atualizado com o retorno do backend."}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-zinc-600">Word count oficial: {wordCount}</span>
            {readOnly ? null : (
              <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${saveStatusClasses[saveStatus]}`}>
                {saveStatusLabels[saveStatus]}
              </span>
            )}
          </div>
        </div>

        <TiptapEditor
          key={contentKey}
          contentKey={contentKey}
          initialContentJson={contentJson}
          initialContentText={contentText}
          readOnly={readOnly}
          onChange={(nextContentJson, nextContentText) =>
            onContentChange(sourceSceneId, JSON.stringify(nextContentJson), nextContentText)
          }
          className="min-h-[66vh] rounded-md border border-zinc-200 bg-white px-5 py-5 text-[17px] leading-8 shadow-sm shadow-zinc-100 focus:ring-2 focus:ring-zinc-200 md:px-8 md:py-7"
        />

        <div className="min-h-10">
          {isSuccess ? (
            <FeedbackMessage variant="success">Conteúdo salvo. Word count atualizado pelo backend.</FeedbackMessage>
          ) : null}

          {isError ? (
            <FeedbackMessage variant="error">
              Não foi possível salvar o conteúdo agora. As alterações continuam no editor para nova tentativa.
            </FeedbackMessage>
          ) : null}
        </div>
      </div>
    </div>
  );
}
