import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import type { OutlineScene } from "@/features/outline/types";

type SceneRowProps = {
  scene: OutlineScene;
  /** Whether this book granted MUTATE_MANUSCRIPT_STRUCTURE; false leaves selection as the only action. */
  canMutateStructure: boolean;
  isSelected: boolean;
  deletePending: boolean;
  reorderPending: boolean;
  onSelect: (sceneId: string) => void;
  onDelete: (sceneId: string, sceneTitle: string) => void;
};

export function SceneRow({
  scene,
  canMutateStructure,
  isSelected,
  deletePending,
  reorderPending,
  onSelect,
  onDelete,
}: SceneRowProps) {
  const { attributes, listeners, setActivatorNodeRef, setNodeRef, transform, transition, isDragging } = useSortable({
    id: scene.id,
    disabled: !canMutateStructure || reorderPending,
  });
  const dragAttributes = canMutateStructure ? attributes : {};
  const dragListeners = canMutateStructure ? listeners : undefined;
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group/scene grid grid-cols-[minmax(0,1fr)_auto] items-stretch rounded-md border transition ${
        isSelected
          ? "border-emerald-700 bg-emerald-50 text-zinc-950 shadow-sm ring-1 ring-emerald-100"
          : "border-transparent bg-white/80 text-zinc-700 hover:border-zinc-200 hover:bg-white"
      } ${isDragging ? "z-10 opacity-40" : ""}`}
    >
      <button
        type="button"
        ref={setActivatorNodeRef}
        onClick={() => onSelect(scene.id)}
        className={`min-w-0 rounded-l-md px-2.5 py-1.5 text-left text-sm transition hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1 ${
          canMutateStructure ? "cursor-grab active:cursor-grabbing" : ""
        }`}
        {...dragAttributes}
        {...dragListeners}
      >
        <span className="block truncate font-medium">{scene.title}</span>
        <span className={`mt-0.5 flex items-center justify-between gap-2 text-[11px] ${isSelected ? "text-emerald-800" : "text-zinc-500"}`}>
          <span>{scene.status}</span>
          <span className="tabular-nums">{scene.wordCount} palavras</span>
        </span>
      </button>
      {canMutateStructure ? (
        <div className="flex items-stretch transition">
          <button
            type="button"
            aria-label={`Reordenar cena ${scene.title}`}
            title="Reordenar cena"
            disabled={reorderPending}
            className={`cursor-grab px-1.5 text-xs font-semibold transition active:cursor-grabbing focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1 ${
              isSelected ? "text-emerald-800 hover:text-emerald-950" : "text-zinc-500 hover:text-zinc-900"
            } disabled:cursor-not-allowed disabled:opacity-40`}
            {...dragListeners}
          >
            ::
          </button>
          <button
            type="button"
            onClick={() => onDelete(scene.id, scene.title)}
            disabled={deletePending}
            className={`px-2 text-xs font-medium transition ${
              isSelected ? "text-emerald-800 hover:text-red-700" : "text-zinc-500 hover:text-red-700"
            } disabled:opacity-60`}
          >
            Excluir
          </button>
        </div>
      ) : null}
    </div>
  );
}
