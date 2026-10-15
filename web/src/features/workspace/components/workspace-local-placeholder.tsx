import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";

/**
 * Authenticated, local loading shells. They contain no cached domain fields: a loading/error
 * state must never display text or edit controls from a previous account or revoked book.
 * SessionGuard deliberately remains responsible for the full-page security interstitial.
 */
type LocalPanelState = "loading" | "error" | "empty";

type LocalPanelProps = {
  state: LocalPanelState;
  onRetry?: () => void;
};

const outlineLabels: Record<LocalPanelState, string> = {
  loading: "Carregando outline...",
  error: "Não foi possível carregar o outline. Verifique a conexão e tente novamente.",
  empty: "Outline indisponível. Não recebemos dados deste livro.",
};

const sceneLabels: Record<LocalPanelState, string> = {
  loading: "Carregando cena...",
  error: "Não foi possível carregar a cena. Verifique a conexão ou o acesso e tente novamente.",
  empty: "A cena solicitada não está disponível.",
};

function LocalStateMessage({
  state,
  label,
  onRetry,
}: {
  state: LocalPanelState;
  label: string;
  onRetry?: () => void;
}) {
  return (
    <div className="grid gap-3">
      {state === "error" ? (
        <FeedbackMessage variant="error">{label}</FeedbackMessage>
      ) : (
        <p role="status" className="text-sm text-zinc-600">{label}</p>
      )}
      {state === "error" && onRetry ? (
        <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
          Tentar novamente
        </Button>
      ) : null}
    </div>
  );
}

/** Retains the fixed sidebar header, controls band and inner scrollport geometry. */
export function OutlineLocalPlaceholder({ state, onRetry }: LocalPanelProps) {
  return (
    <aside aria-label="Esboço do livro" aria-busy={state === "loading"} className="flex h-full min-h-0 flex-col bg-white">
      <div className="border-b border-zinc-200 bg-white px-4 py-5">
        <p className="text-xs font-medium uppercase text-zinc-500">Livro</p>
        <h1 className="mt-1 text-lg font-semibold text-zinc-950">Esboço</h1>
      </div>
      <div aria-hidden="true" className="h-16 shrink-0 border-b border-zinc-200 bg-white px-4 py-3">
        <div className="h-9 w-full rounded-md bg-zinc-100" />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto bg-zinc-50/70 px-3 py-4">
        <LocalStateMessage state={state} label={outlineLabels[state]} onRetry={onRetry} />
        {state === "loading" ? (
          <div aria-hidden="true" className="mt-6 grid gap-4">
            <div className="h-24 rounded-md border border-zinc-200 bg-white" />
            <div className="h-24 rounded-md border border-zinc-200 bg-white" />
            <div className="h-24 rounded-md border border-zinc-200 bg-white" />
          </div>
        ) : null}
      </div>
    </aside>
  );
}

/** Replaces only the SceneEditor surface; workspace navigation and outline stay mounted. */
export function SceneLocalPlaceholder({
  state,
  onRetry,
  label,
}: LocalPanelProps & { label?: string }) {
  return (
    <section aria-label="Área da cena" aria-busy={state === "loading"} className="h-full overflow-y-auto bg-zinc-100/70 p-4 md:p-6 lg:p-8">
      <div className="mx-auto grid min-h-full max-w-7xl grid-rows-[auto_auto_1fr] rounded-md border border-zinc-200 bg-white shadow-xl shadow-zinc-200/70">
        <div aria-hidden="true" className="grid gap-3 border-b border-zinc-200 px-4 py-5 md:px-7">
          <div className="h-3 w-14 rounded bg-zinc-100" />
          <div className="h-6 w-1/2 max-w-72 rounded bg-zinc-100" />
        </div>
        <div aria-hidden="true" className="grid gap-3 border-b border-zinc-200 px-4 py-5 md:px-7">
          <div className="h-3 w-28 rounded bg-zinc-100" />
          <div className="h-9 w-full rounded bg-zinc-100" />
        </div>
        <div className="grid content-start gap-6 p-5 md:p-7">
          <LocalStateMessage state={state} label={label ?? sceneLabels[state]} onRetry={onRetry} />
          {state === "loading" ? (
            <div aria-hidden="true" className="grid gap-4">
              <div className="h-5 w-4/5 rounded bg-zinc-100" />
              <div className="h-5 w-full rounded bg-zinc-100" />
              <div className="h-5 w-3/5 rounded bg-zinc-100" />
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}
