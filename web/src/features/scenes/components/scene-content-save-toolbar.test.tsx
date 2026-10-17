import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { SceneContentEditor } from "@/features/scenes/components/scene-content-editor";
import type { ContentSaveStatus } from "@/features/scenes/components/scene-editor-header";

vi.mock("@/features/scenes/editor/tiptap-editor", () => ({
  TiptapEditor: ({
    toolbarActions,
    onSaveContent,
    initialContentText,
  }: {
    toolbarActions?: React.ReactNode;
    onSaveContent?: () => void;
    initialContentText: string;
  }) => (
    <div data-testid="editor-fixture">
      {toolbarActions}
      <p>{initialContentText}</p>
      <button type="button" onClick={onSaveContent} disabled={!onSaveContent}>Mock Ctrl+S</button>
    </div>
  ),
}));

function contentEditor(status: ContentSaveStatus, options?: { readOnly?: boolean; onSave?: () => void }) {
  return (
    <SceneContentEditor
      contentKey="scene-1:0"
      sourceSceneId="scene-1"
      contentJson="{}"
      contentText="Meu texto de ficção"
      readOnly={options?.readOnly ?? false}
      wordCount={4}
      isSuccess={status === "saved"}
      isError={status === "error"}
      saveStatus={status}
      onSaveContent={options?.onSave}
      onContentChange={vi.fn()}
    />
  );
}

describe("SceneContentEditor nearby save actions (#252)", () => {
  test.each([
    ["editing", "Digitando..."],
    ["saving", "Salvando..."],
    ["saved", "Salvo"],
    ["error", "Erro ao salvar"],
  ] as const)("shows server-driven save status %s without fabricating success", (status, label) => {
    render(contentEditor(status, { onSave: vi.fn() }));
    const statuses = screen.getAllByRole("status", { name: "Estado do salvamento" });
    expect(statuses).toHaveLength(1);
    expect(statuses[0]).toHaveTextContent(label);
    expect(statuses[0]).toHaveAttribute("aria-live", "polite");
    expect(screen.getByText("Meu texto de ficção")).toBeInTheDocument();
    const save = screen.queryByRole("button", { name: "Salvar agora" });
    if (status === "saving") {
      expect(save).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Salvando..." })).toBeDisabled();
    } else {
      expect(save).toBeEnabled();
    }
  });

  test("manual save uses the same parent handler as the header, and never claims success prematurely", () => {
    const onSave = vi.fn();
    const view = render(contentEditor("editing", { onSave }));
    const save = screen.getByRole("button", { name: "Salvar agora" });
    expect(fireEvent.mouseDown(save)).toBe(false);
    fireEvent.click(save);
    expect(onSave).toHaveBeenCalledOnce();
    expect(screen.getByRole("status", { name: "Estado do salvamento" })).toHaveTextContent("Digitando...");

    view.rerender(contentEditor("saving", { onSave }));
    expect(screen.getByRole("button", { name: "Salvando..." })).toBeDisabled();
    expect(screen.getByRole("status", { name: "Estado do salvamento" })).toHaveTextContent("Salvando...");
    view.rerender(contentEditor("error", { onSave }));
    expect(screen.getByRole("button", { name: "Salvar agora" })).toBeEnabled();
    expect(screen.getByRole("status", { name: "Estado do salvamento" })).toHaveTextContent("Erro ao salvar");
  });

  test("read-only content offers neither save action nor keyboard save callback", () => {
    const onSave = vi.fn();
    render(contentEditor("saved", { readOnly: true, onSave }));
    expect(screen.queryByRole("button", { name: "Salvar agora" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Mock Ctrl+S" })).toBeDisabled();
    expect(screen.queryByRole("status", { name: "Estado do salvamento" })).not.toBeInTheDocument();
    expect(screen.getByText("Meu texto de ficção")).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });
});
