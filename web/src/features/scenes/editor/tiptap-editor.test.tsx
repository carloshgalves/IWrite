import { fireEvent, screen } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { TiptapEditor } from "@/features/scenes/editor/tiptap-editor";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useEditor: vi.fn(),
  textAlignConfigure: vi.fn(() => "text-align-extension"),
  setTextAlign: vi.fn(),
}));

vi.mock("@tiptap/react", () => ({
  useEditor: mocks.useEditor,
  EditorContent: () => <div data-testid="editor-content" />,
}));

vi.mock("@tiptap/starter-kit", () => ({
  default: "starter-kit-extension",
}));

vi.mock("@tiptap/extension-text-align", () => ({
  default: {
    configure: mocks.textAlignConfigure,
  },
}));

describe("TiptapEditor toolbar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useEditor.mockReturnValue(createEditor());
  });

  test("configura alinhamento para paragrafos e titulos", () => {
    renderEditor();

    expect(mocks.textAlignConfigure).toHaveBeenCalledWith({ types: ["paragraph", "heading"] });
    expect(mocks.useEditor).toHaveBeenCalledWith(
      expect.objectContaining({
        extensions: ["starter-kit-extension", "text-align-extension"],
      })
    );
  });

  test("renderiza botoes existentes e botoes de alinhamento com nomes acessiveis", () => {
    renderEditor();

    expect(screen.getByRole("button", { name: "B" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "I" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Par.grafo/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /T.tulo/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Alinhar à esquerda" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Centralizar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Alinhar à direita" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Justificar" })).toBeInTheDocument();
    expect(screen.queryByText("Esquerda")).not.toBeInTheDocument();
    expect(screen.queryByText("Centro")).not.toBeInTheDocument();
    expect(screen.queryByText("Direita")).not.toBeInTheDocument();
    expect(screen.queryByText("Justificar")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desfazer" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refazer" })).toBeInTheDocument();
  });

  test("em somente leitura esconde a barra e desliga a edicao", () => {
    const editor = createEditor();
    mocks.useEditor.mockReturnValue(editor);

    renderEditor({ readOnly: true });

    expect(screen.queryByRole("button", { name: "B" })).not.toBeInTheDocument();
    expect(mocks.useEditor).toHaveBeenCalledWith(expect.objectContaining({ editable: false }));
    expect(editor.setEditable).toHaveBeenCalledWith(false);
  });

  test("aciona comando de alinhamento ao clicar em um botao", () => {
    renderEditor();

    fireEvent.click(screen.getByRole("button", { name: "Centralizar" }));

    expect(mocks.setTextAlign).toHaveBeenCalledWith("center");
  });
});

function renderEditor(props?: Partial<React.ComponentProps<typeof TiptapEditor>>) {
  renderWithClient(
    <TiptapEditor contentKey="scene-1" initialContentText="Texto" onChange={vi.fn()} {...props} />
  );
}

function createEditor() {
  const chain = {
    focus: vi.fn(() => chain),
    toggleBold: vi.fn(() => chain),
    toggleItalic: vi.fn(() => chain),
    setParagraph: vi.fn(() => chain),
    toggleHeading: vi.fn(() => chain),
    setTextAlign: vi.fn((alignment: string) => {
      mocks.setTextAlign(alignment);
      return chain;
    }),
    undo: vi.fn(() => chain),
    redo: vi.fn(() => chain),
    run: vi.fn(),
  };

  return {
    isActive: vi.fn(() => false),
    setEditable: vi.fn(),
    can: vi.fn(() => ({
      undo: vi.fn(() => true),
      redo: vi.fn(() => true),
    })),
    chain: vi.fn(() => chain),
    commands: {
      setContent: vi.fn(),
    },
  };
}
