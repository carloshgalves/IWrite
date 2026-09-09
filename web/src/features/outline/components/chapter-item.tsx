import { type FormEvent, useState } from "react";
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  type DragEndEvent,
  type DragStartEvent,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { CollapseChevronButton } from "@/features/outline/components/collapse-chevron-button";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { Field, WordCount } from "@/features/outline/components/outline-sidebar-parts";
import { SceneDragPreview } from "@/features/outline/components/outline-drag-overlay";
import { SceneRow } from "@/features/outline/components/scene-row";
import type { OutlineChapter } from "@/features/outline/types";
import { getReorderedIds } from "@/features/outline/utils/reorder";

type ChapterItemProps = {
  chapter: OutlineChapter;
  /** Whether this book granted MUTATE_MANUSCRIPT_STRUCTURE; false renders the chapter read-only. */
  canMutateStructure: boolean;
  isCollapsed: boolean;
  isEditing: boolean;
  chapterTitle: string;
  chapterSummary: string;
  selectedSceneId: string | null;
  updatePending: boolean;
  deletePending: boolean;
  createScenePending: boolean;
  deleteScenePending: boolean;
  reorderPending: boolean;
  reorderScenePending: boolean;
  onTitleChange: (title: string) => void;
  onSummaryChange: (summary: string) => void;
  onStartEdit: (chapter: OutlineChapter) => void;
  onCancelEdit: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onToggleChapter: (chapterId: string) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
  onReorderScenes: (chapter: OutlineChapter, orderedIds: string[]) => void;
};

export function ChapterItem({
  chapter,
  canMutateStructure,
  isCollapsed,
  isEditing,
  chapterTitle,
  chapterSummary,
  selectedSceneId,
  updatePending,
  deletePending,
  createScenePending,
  deleteScenePending,
  reorderPending,
  reorderScenePending,
  onTitleChange,
  onSummaryChange,
  onStartEdit,
  onCancelEdit,
  onSubmit,
  onDeleteChapter,
  onToggleChapter,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
  onReorderScenes,
}: ChapterItemProps) {
  const { attributes, listeners, setActivatorNodeRef, setNodeRef, transform, transition, isDragging } = useSortable({
    id: chapter.id,
    disabled: !canMutateStructure || reorderPending || isEditing,
  });
  const dragAttributes = canMutateStructure ? attributes : {};
  const dragListeners = canMutateStructure ? listeners : undefined;
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };
  const [activeSceneId, setActiveSceneId] = useState<string | null>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  function handleSceneDragEnd(event: DragEndEvent) {
    setActiveSceneId(null);

    const orderedIds = getReorderedIds(chapter.scenes, String(event.active.id), event.over ? String(event.over.id) : null);
    if (!orderedIds) {
      return;
    }

    onReorderScenes(chapter, orderedIds);
  }

  function handleSceneDragStart(event: DragStartEvent) {
    setActiveSceneId(String(event.active.id));
  }

  function handleSceneDragCancel() {
    setActiveSceneId(null);
  }

  const activeScene = activeSceneId ? chapter.scenes.find((scene) => scene.id === activeSceneId) : null;

  return (
    <article
      ref={setNodeRef}
      style={style}
      className={`group/chapter grid gap-2 border-l-2 border-zinc-300 pl-3 ${isDragging ? "z-10 opacity-40" : ""}`}
    >
      {isEditing ? (
        <form onSubmit={(event) => onSubmit(event, chapter.id)} className="grid gap-2">
          <Field label="Título do capítulo">
            <input
              value={chapterTitle}
              onChange={(event) => onTitleChange(event.target.value)}
              className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
            />
          </Field>
          <Field label="Resumo">
            <textarea
              value={chapterSummary}
              rows={2}
              onChange={(event) => onSummaryChange(event.target.value)}
              className="rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
            />
          </Field>
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={updatePending || !chapterTitle.trim()}>
              Salvar
            </Button>
            <Button type="button" size="sm" variant="secondary" onClick={onCancelEdit}>
              Cancelar
            </Button>
          </div>
        </form>
      ) : (
        <>
          <div className="flex items-start justify-between gap-3 rounded-md bg-white px-2 py-2">
            <div className="flex min-w-0 flex-1 items-start gap-2.5">
              <CollapseChevronButton
                isExpanded={!isCollapsed}
                label={`${isCollapsed ? "Expandir" : "Recolher"} capítulo ${chapter.title}`}
                onClick={() => onToggleChapter(chapter.id)}
              />
              <button
                type="button"
                ref={setActivatorNodeRef}
                className={`min-w-0 flex-1 rounded-md text-left transition hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2 ${
                  canMutateStructure ? "cursor-grab active:cursor-grabbing" : ""
                }`}
                aria-expanded={!isCollapsed}
                aria-label={`${isCollapsed ? "Expandir" : "Recolher"} capítulo ${chapter.title}`}
                onClick={() => onToggleChapter(chapter.id)}
                {...dragAttributes}
                {...dragListeners}
              >
                <p className="text-[11px] font-medium uppercase text-zinc-500">Capítulo</p>
                <h3 className="truncate text-sm font-medium text-zinc-800">{chapter.title}</h3>
                {chapter.summary ? <p className="mt-1 line-clamp-2 text-xs leading-5 text-zinc-500">{chapter.summary}</p> : null}
              </button>
            </div>
            <WordCount count={chapter.wordCount} />
          </div>
          {canMutateStructure ? (
            <div className="flex flex-wrap gap-1.5 opacity-60 transition group-hover/chapter:opacity-100 focus-within:opacity-100">
              <button
                type="button"
                aria-label={`Reordenar capítulo ${chapter.title}`}
                title="Reordenar capítulo"
                disabled={reorderPending}
                className="inline-flex min-h-8 cursor-grab items-center justify-center rounded-md px-2 py-1 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 active:cursor-grabbing focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-70"
                {...dragListeners}
              >
                ::
              </button>
              <Button type="button" variant="ghost" size="sm" onClick={() => onStartEdit(chapter)}>
                Editar
              </Button>
              <Button type="button" variant="ghost" size="sm" disabled={deletePending} onClick={() => onDeleteChapter(chapter)}>
                Excluir
              </Button>
            </div>
          ) : null}
        </>
      )}

      {!isCollapsed ? (
        <>
          {canMutateStructure ? (
            <InlineCreateForm
              compact
              ariaLabel={`Nova cena em ${chapter.title}`}
              placeholder="Nova cena"
              buttonLabel="Cena"
              disabled={createScenePending}
              onCreate={(title) => onCreateScene(chapter.id, title)}
            />
          ) : null}

          {chapter.scenes.length === 0 ? (
            <EmptyState size="sm" title="Nenhuma cena" description="Este capítulo ainda não tem cenas." />
          ) : (
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragStart={handleSceneDragStart}
              onDragEnd={handleSceneDragEnd}
              onDragCancel={handleSceneDragCancel}
            >
              <SortableContext items={chapter.scenes.map((scene) => scene.id)} strategy={verticalListSortingStrategy}>
                <div className="grid gap-1.5">
                  {chapter.scenes.map((scene) => (
                    <SceneRow
                      key={scene.id}
                      scene={scene}
                      canMutateStructure={canMutateStructure}
                      isSelected={selectedSceneId === scene.id}
                      deletePending={deleteScenePending}
                      reorderPending={reorderScenePending}
                      onSelect={onSelectScene}
                      onDelete={onDeleteScene}
                    />
                  ))}
                </div>
              </SortableContext>
              <DragOverlay>{activeScene ? <SceneDragPreview scene={activeScene} /> : null}</DragOverlay>
            </DndContext>
          )}
        </>
      ) : null}
    </article>
  );
}
