import { DndContext } from "@dnd-kit/core";
import { SortableContext } from "@dnd-kit/sortable";
import { render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { ChapterItem } from "@/features/outline/components/chapter-item";
import type { OutlineChapter } from "@/features/outline/types";

const chapter: OutlineChapter = {
  id: "chapter-1",
  title: "Capítulo 1",
  summary: "Resumo curto",
  sortOrder: 0,
  wordCount: 1200,
  scenes: [],
};

function renderChapterItem(props?: Partial<React.ComponentProps<typeof ChapterItem>>) {
  const defaultProps: React.ComponentProps<typeof ChapterItem> = {
    chapter,
    canMutateStructure: true,
    isCollapsed: false,
    isEditing: false,
    chapterTitle: "",
    chapterSummary: "",
    selectedSceneId: null,
    updatePending: false,
    deletePending: false,
    createScenePending: false,
    deleteScenePending: false,
    reorderPending: false,
    reorderScenePending: false,
    onTitleChange: vi.fn(),
    onSummaryChange: vi.fn(),
    onStartEdit: vi.fn(),
    onCancelEdit: vi.fn(),
    onSubmit: vi.fn(),
    onDeleteChapter: vi.fn(),
    onToggleChapter: vi.fn(),
    onCreateScene: vi.fn(),
    onSelectScene: vi.fn(),
    onDeleteScene: vi.fn(),
    onReorderScenes: vi.fn(),
  };

  const mergedProps = { ...defaultProps, ...props };

  render(
    <DndContext>
      <SortableContext items={[chapter.id]}>
        <ChapterItem {...mergedProps} />
      </SortableContext>
    </DndContext>
  );

  return mergedProps;
}

describe("ChapterItem", () => {
  test("renderiza drag handle de capitulo com nome acessivel", () => {
    renderChapterItem();

    expect(screen.getByRole("button", { name: "Reordenar capítulo Capítulo 1" })).toBeInTheDocument();
  });

  test("mantem controles existentes de capitulo renderizados", () => {
    renderChapterItem();

    expect(screen.getAllByRole("button", { name: "Recolher capítulo Capítulo 1" })).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Mover capítulo Capítulo 1 para cima" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mover capítulo Capítulo 1 para baixo" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Editar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Excluir" })).toBeInTheDocument();
  });
});
