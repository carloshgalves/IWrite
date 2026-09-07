import type { FormEvent } from "react";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import type { SceneStatus } from "@/features/scenes/types";

type SceneMetadataFormProps = {
  formId: string;
  /** Scene metadata is a manuscript structure mutation: without the capability the fields only show. */
  readOnly: boolean;
  title: string;
  summary: string;
  status: SceneStatus;
  statuses: SceneStatus[];
  isSuccess: boolean;
  isError: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onTitleChange: (title: string) => void;
  onSummaryChange: (summary: string) => void;
  onStatusChange: (status: SceneStatus) => void;
};

export function SceneMetadataForm({
  formId,
  readOnly,
  title,
  summary,
  status,
  statuses,
  isSuccess,
  isError,
  onSubmit,
  onTitleChange,
  onSummaryChange,
  onStatusChange,
}: SceneMetadataFormProps) {
  return (
    <form
      id={formId}
      onSubmit={onSubmit}
      className="grid gap-3 border-b border-zinc-200 bg-zinc-50/50 px-4 py-3 md:grid-cols-[minmax(0,1fr)_160px] md:px-7"
    >
      <label className="grid gap-1 text-xs">
        <span className="font-medium text-zinc-600">Título</span>
        <input
          value={title}
          readOnly={readOnly}
          onChange={(event) => onTitleChange(event.target.value)}
          className={`min-h-9 rounded-md border border-zinc-300 px-3 py-1.5 text-sm outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200 ${
            readOnly ? "bg-zinc-50 text-zinc-600" : "bg-white"
          }`}
        />
      </label>

      <label className="grid gap-1 text-xs">
        <span className="font-medium text-zinc-600">Status</span>
        <select
          value={status}
          disabled={readOnly}
          onChange={(event) => onStatusChange(event.target.value as SceneStatus)}
          className="min-h-9 rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
        >
          {statuses.map((sceneStatus) => (
            <option key={sceneStatus} value={sceneStatus}>
              {sceneStatus}
            </option>
          ))}
        </select>
      </label>

      <label className="grid gap-1 text-xs md:col-span-2">
        <span className="font-medium text-zinc-600">Resumo</span>
        <Textarea
          value={summary}
          rows={2}
          readOnly={readOnly}
          onChange={(event) => onSummaryChange(event.target.value)}
          className={`resize-y text-sm focus:ring-2 focus:ring-zinc-200 ${readOnly ? "bg-zinc-50 text-zinc-600" : "bg-white"}`}
          placeholder="Resumo breve da função dramática desta cena."
        />
      </label>

      {isSuccess ? (
        <FeedbackMessage variant="success" className="md:col-span-2">
          Dados da cena salvos com sucesso.
        </FeedbackMessage>
      ) : null}

      {isError ? (
        <FeedbackMessage variant="error" className="md:col-span-2">
          Não foi possível salvar os dados da cena. Verifique o backend e tente novamente.
        </FeedbackMessage>
      ) : null}
    </form>
  );
}
