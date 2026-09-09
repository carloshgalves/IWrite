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
import { ChapterItem } from "@/features/outline/components/chapter-item";
import { CollapseChevronButton } from "@/features/outline/components/collapse-chevron-button";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { ChapterDragPreview } from "@/features/outline/components/outline-drag-overlay";
import { Field, WordCount } from "@/features/outline/components/outline-sidebar-parts";
import type { OutlineChapter, OutlineSection, SectionType } from "@/features/outline/types";
import { getReorderedIds } from "@/features/outline/utils/reorder";

type SectionItemProps = {
  section: OutlineSection;
  /** Whether this book granted MUTATE_MANUSCRIPT_STRUCTURE; false renders the section read-only. */
  canMutateStructure: boolean;
  sectionTypes: SectionType[];
  selectedSceneId: string | null;
  editingSectionId: string | null;
  sectionTitle: string;
  sectionType: SectionType;
  editingChapterId: string | null;
  chapterTitle: string;
  chapterSummary: string;
  updateSectionPending: boolean;
  deleteSectionPending: boolean;
  createChapterPending: boolean;
  updateChapterPending: boolean;
  deleteChapterPending: boolean;
  createScenePending: boolean;
  deleteScenePending: boolean;
  reorderSectionPending: boolean;
  reorderChapterPending: boolean;
  reorderScenePending: boolean;
  isCollapsed: boolean;
  collapsedChapterIds: Set<string>;
  onSectionTitleChange: (title: string) => void;
  onSectionTypeChange: (type: SectionType) => void;
  onStartEditSection: (section: OutlineSection) => void;
  onCancelEditSection: () => void;
  onSubmitSection: (event: FormEvent<HTMLFormElement>, sectionId: string) => void;
  onDeleteSection: (section: OutlineSection) => void;
  onToggleSection: (sectionId: string) => void;
  onToggleChapter: (chapterId: string) => void;
  onCreateChapter: (sectionId: string, title: string) => void;
  onChapterTitleChange: (title: string) => void;
  onChapterSummaryChange: (summary: string) => void;
  onStartEditChapter: (chapter: OutlineChapter) => void;
  onCancelEditChapter: () => void;
  onSubmitChapter: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onReorderChapters: (section: OutlineSection, orderedIds: string[]) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
  onReorderScenes: (chapter: OutlineChapter, orderedIds: string[]) => void;
};

export function SectionItem({
  section,
  canMutateStructure,
  sectionTypes,
  selectedSceneId,
  editingSectionId,
  sectionTitle,
  sectionType,
  editingChapterId,
  chapterTitle,
  chapterSummary,
  updateSectionPending,
  deleteSectionPending,
  createChapterPending,
  updateChapterPending,
  deleteChapterPending,
  createScenePending,
  deleteScenePending,
  reorderSectionPending,
  reorderChapterPending,
  reorderScenePending,
  isCollapsed,
  collapsedChapterIds,
  onSectionTitleChange,
  onSectionTypeChange,
  onStartEditSection,
  onCancelEditSection,
  onSubmitSection,
  onDeleteSection,
  onToggleSection,
  onToggleChapter,
  onCreateChapter,
  onChapterTitleChange,
  onChapterSummaryChange,
  onStartEditChapter,
  onCancelEditChapter,
  onSubmitChapter,
  onDeleteChapter,
  onReorderChapters,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
  onReorderScenes,
}: SectionItemProps) {
  const { attributes, listeners, setActivatorNodeRef, setNodeRef, transform, transition, isDragging } = useSortable({
    id: section.id,
    disabled: !canMutateStructure || reorderSectionPending || editingSectionId === section.id,
  });
  // Reordering is a manuscript structure mutation: without the capability the header stays a plain
  // collapse control instead of a drag activator.
  const dragAttributes = canMutateStructure ? attributes : {};
  const dragListeners = canMutateStructure ? listeners : undefined;
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };
  const [activeChapterId, setActiveChapterId] = useState<string | null>(null);
  const chapterSensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  function handleChapterDragEnd(event: DragEndEvent) {
    setActiveChapterId(null);

    const orderedIds = getReorderedIds(section.chapters, String(event.active.id), event.over ? String(event.over.id) : null);
    if (!orderedIds) {
      return;
    }

    onReorderChapters(section, orderedIds);
  }

  function handleChapterDragStart(event: DragStartEvent) {
    setActiveChapterId(String(event.active.id));
  }

  function handleChapterDragCancel() {
    setActiveChapterId(null);
  }

  const activeChapter = activeChapterId ? section.chapters.find((chapter) => chapter.id === activeChapterId) : null;

  return (
    <section
      ref={setNodeRef}
      style={style}
      className={`group/section rounded-md border border-zinc-200 bg-white shadow-sm shadow-zinc-200/50 ${
        isDragging ? "z-10 opacity-40" : ""
      }`}
    >
      <div className="border-b border-zinc-100 bg-white px-3 py-3">
        {editingSectionId === section.id ? (
          <form onSubmit={(event) => onSubmitSection(event, section.id)} className="grid gap-2">
            <Field label="Nome da seção">
              <input
                value={sectionTitle}
                onChange={(event) => onSectionTitleChange(event.target.value)}
                className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
              />
            </Field>
            <Field label="Tipo">
              <select
                value={sectionType}
                onChange={(event) => onSectionTypeChange(event.target.value as SectionType)}
                className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
              >
                {sectionTypes.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </Field>
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={updateSectionPending || !sectionTitle.trim()}>
                Salvar
              </Button>
              <Button type="button" size="sm" variant="secondary" onClick={onCancelEditSection}>
                Cancelar
              </Button>
            </div>
          </form>
        ) : (
          <div className="grid gap-2">
            <div className="flex items-center justify-between gap-3">
              <div className="flex min-w-0 flex-1 items-center gap-2.5">
                <CollapseChevronButton
                  isExpanded={!isCollapsed}
                  label={`${isCollapsed ? "Expandir" : "Recolher"} seção ${section.title}`}
                  onClick={() => onToggleSection(section.id)}
                />
                <button
                  type="button"
                  ref={setActivatorNodeRef}
                  className={`min-w-0 flex-1 rounded-md text-left transition hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2 ${
                    canMutateStructure ? "cursor-grab active:cursor-grabbing" : ""
                  }`}
                  aria-expanded={!isCollapsed}
                  aria-label={`${isCollapsed ? "Expandir" : "Recolher"} seção ${section.title}`}
                  onClick={() => onToggleSection(section.id)}
                  {...dragAttributes}
                  {...dragListeners}
                >
                  <p className="text-[11px] font-medium uppercase text-zinc-500">Seção · {section.type}</p>
                  <h2 className="truncate text-sm font-semibold text-zinc-900">{section.title}</h2>
                </button>
              </div>
              <WordCount count={section.wordCount} />
            </div>
            {canMutateStructure ? (
              <div className="flex flex-wrap gap-1.5 opacity-70 transition group-hover/section:opacity-100 focus-within:opacity-100">
                <button
                  type="button"
                  aria-label={`Reordenar seção ${section.title}`}
                  title="Reordenar seção"
                  disabled={reorderSectionPending}
                  className="inline-flex min-h-8 cursor-grab items-center justify-center rounded-md px-2 py-1 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 active:cursor-grabbing focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-70"
                  {...dragListeners}
                >
                  ::
                </button>
                <Button type="button" variant="ghost" size="sm" onClick={() => onStartEditSection(section)}>
                  Editar
                </Button>
                <Button type="button" variant="ghost" size="sm" disabled={deleteSectionPending} onClick={() => onDeleteSection(section)}>
                  Excluir
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </div>

      {!isCollapsed ? (
        <div className="grid gap-3 bg-zinc-50/40 p-3">
          {canMutateStructure ? (
            <InlineCreateForm
              compact
              ariaLabel={`Novo capítulo em ${section.title}`}
              placeholder="Novo capítulo"
              buttonLabel="Cap."
              disabled={createChapterPending}
              onCreate={(title) => onCreateChapter(section.id, title)}
            />
          ) : null}

          {section.chapters.length === 0 ? (
            <EmptyState size="sm" title="Nenhum capítulo" description="Esta seção ainda não tem capítulos." />
          ) : (
            <DndContext
              sensors={chapterSensors}
              collisionDetection={closestCenter}
              onDragStart={handleChapterDragStart}
              onDragEnd={handleChapterDragEnd}
              onDragCancel={handleChapterDragCancel}
            >
              <SortableContext items={section.chapters.map((chapter) => chapter.id)} strategy={verticalListSortingStrategy}>
                <div className="grid gap-4">
                  {section.chapters.map((chapter) => (
                    <ChapterItem
                      key={chapter.id}
                      chapter={chapter}
                      canMutateStructure={canMutateStructure}
                      isCollapsed={collapsedChapterIds.has(chapter.id)}
                      isEditing={editingChapterId === chapter.id}
                      chapterTitle={chapterTitle}
                      chapterSummary={chapterSummary}
                      selectedSceneId={selectedSceneId}
                      updatePending={updateChapterPending}
                      deletePending={deleteChapterPending}
                      createScenePending={createScenePending}
                      deleteScenePending={deleteScenePending}
                      reorderPending={reorderChapterPending}
                      reorderScenePending={reorderScenePending}
                      onTitleChange={onChapterTitleChange}
                      onSummaryChange={onChapterSummaryChange}
                      onStartEdit={onStartEditChapter}
                      onCancelEdit={onCancelEditChapter}
                      onSubmit={onSubmitChapter}
                      onDeleteChapter={onDeleteChapter}
                      onToggleChapter={onToggleChapter}
                      onCreateScene={onCreateScene}
                      onSelectScene={onSelectScene}
                      onDeleteScene={onDeleteScene}
                      onReorderScenes={onReorderScenes}
                    />
                  ))}
                </div>
              </SortableContext>
              <DragOverlay>{activeChapter ? <ChapterDragPreview chapter={activeChapter} /> : null}</DragOverlay>
            </DndContext>
          )}
        </div>
      ) : null}
    </section>
  );
}
