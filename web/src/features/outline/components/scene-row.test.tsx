import { DndContext } from "@dnd-kit/core";
import { SortableContext } from "@dnd-kit/sortable";
import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { SceneRow } from "@/features/outline/components/scene-row";
import type { OutlineScene } from "@/features/outline/types";

const scene: OutlineScene = {
  id: "scene-1",
  title: "A chave aparece",
  status: "DRAFT",
  sortOrder: 0,
  wordCount: 1200,
  povCharacterId: null,
  povCharacterName: null,
  planningGaps: [],
};

function renderSceneRow(props?: Partial<React.ComponentProps<typeof SceneRow>>) {
  const defaultProps: React.ComponentProps<typeof SceneRow> = {
    scene,
    canMutateStructure: true,
    isSelected: false,
    deletePending: false,
    reorderPending: false,
    onSelect: vi.fn(),
    onDelete: vi.fn(),
  };

  const mergedProps = { ...defaultProps, ...props };

  render(
    <DndContext>
      <SortableContext items={[scene.id]}>
        <SceneRow {...mergedProps} />
      </SortableContext>
    </DndContext>
  );

  return mergedProps;
}

describe("SceneRow", () => {
  test("renderiza drag handle com nome acessivel", () => {
    renderSceneRow();

    expect(screen.getByRole("button", { name: "Reordenar cena A chave aparece" })).toBeInTheDocument();
  });

  test("clicar na cena ainda chama selecao", () => {
    const onSelect = vi.fn();
    renderSceneRow({ onSelect });

    fireEvent.click(screen.getByText("A chave aparece").closest("button")!);

    expect(onSelect).toHaveBeenCalledWith(scene.id);
  });

  test("mantem excluir renderizado e setas removidas", () => {
    renderSceneRow();

    expect(screen.queryByRole("button", { name: "Mover cena A chave aparece para cima" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mover cena A chave aparece para baixo" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Excluir" })).toBeInTheDocument();
  });
});
