import { DndContext } from "@dnd-kit/core";
import { SortableContext } from "@dnd-kit/sortable";
import { render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { SectionItem } from "@/features/outline/components/section-item";
import type { OutlineSection } from "@/features/outline/types";

const section: OutlineSection = {
  id: "section-1",
  title: "Parte 1",
  type: "PART",
  sortOrder: 0,
  wordCount: 1200,
  chapters: [],
};

function renderSectionItem(props?: Partial<React.ComponentProps<typeof SectionItem>>) {
  const defaultProps: React.ComponentProps<typeof SectionItem> = {
    section,
    canMutateStructure: true,
    sectionTypes: ["PART", "PROLOGUE", "INTERLUDE", "EPILOGUE", "OTHER"],
    selectedSceneId: null,
    editingSectionId: null,
    sectionTitle: "",
    sectionType: "PART",
    editingChapterId: null,
    chapterTitle: "",
    chapterSummary: "",
    updateSectionPending: false,
    deleteSectionPending: false,
    createChapterPending: false,
    updateChapterPending: false,
    deleteChapterPending: false,
    createScenePending: false,
    deleteScenePending: false,
    reorderSectionPending: false,
    reorderChapterPending: false,
    reorderScenePending: false,
    isCollapsed: false,
    collapsedChapterIds: new Set(),
    onSectionTitleChange: vi.fn(),
    onSectionTypeChange: vi.fn(),
    onStartEditSection: vi.fn(),
    onCancelEditSection: vi.fn(),
    onSubmitSection: vi.fn(),
    onDeleteSection: vi.fn(),
    onToggleSection: vi.fn(),
    onToggleChapter: vi.fn(),
    onCreateChapter: vi.fn(),
    onChapterTitleChange: vi.fn(),
    onChapterSummaryChange: vi.fn(),
    onStartEditChapter: vi.fn(),
    onCancelEditChapter: vi.fn(),
    onSubmitChapter: vi.fn(),
    onDeleteChapter: vi.fn(),
    onReorderChapters: vi.fn(),
    onCreateScene: vi.fn(),
    onSelectScene: vi.fn(),
    onDeleteScene: vi.fn(),
    onReorderScenes: vi.fn(),
  };

  const mergedProps = { ...defaultProps, ...props };

  render(
    <DndContext>
      <SortableContext items={[section.id]}>
        <SectionItem {...mergedProps} />
      </SortableContext>
    </DndContext>
  );

  return mergedProps;
}

describe("SectionItem", () => {
  test("renderiza drag handle de seção com nome acessível", () => {
    renderSectionItem();

    expect(screen.getByRole("button", { name: "Reordenar seção Parte 1" })).toBeInTheDocument();
  });

  test("mantem controles existentes de secao renderizados", () => {
    renderSectionItem();

    expect(screen.getAllByRole("button", { name: "Recolher seção Parte 1" })).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Mover seção Parte 1 para cima" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mover seção Parte 1 para baixo" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Editar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Excluir" })).toBeInTheDocument();
  });
});
