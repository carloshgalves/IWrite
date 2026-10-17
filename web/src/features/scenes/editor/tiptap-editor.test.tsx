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
    // Never emitting an update: tiptap-editor-editable.test.tsx proves what that suppression buys.
    expect(editor.setEditable).toHaveBeenCalledWith(false, false);
  });


  test("toolbar sticky preserva seleção ao clicar e evita scroll forçado do TipTap", () => {
    const editor = createEditor();
    mocks.useEditor.mockReturnValue(editor);
    renderEditor();
    const toolbar = screen.getByRole("toolbar", { name: "Ferramentas de escrita" });
    expect(toolbar).toHaveClass("sticky", "top-0", "z-20");

    const bold = screen.getByRole("button", { name: "B" });
    expect(fireEvent.mouseDown(bold)).toBe(false);
    fireEvent.click(bold);

    const chain = editor.chain();
    expect(chain.focus).toHaveBeenCalledWith(undefined, { scrollIntoView: false });
    expect(chain.toggleBold).toHaveBeenCalled();
  });

  test("toolbar custom actions stay beside formatting even on narrow layouts", () => {
    renderEditor({
      toolbarActions: <button type="button">Salvar agora</button>,
    });
    const toolbar = screen.getByRole("toolbar", { name: "Ferramentas de escrita" });
    expect(toolbar).toContainElement(screen.getByRole("button", { name: "Salvar agora" }));
    expect(toolbar.querySelector('[role="group"][aria-label="Formatação"]')).toHaveClass("overflow-x-auto");
  });

  test("Ctrl+S and Cmd+S save only inside editable surface and preserve Tab shortcut", () => {
    const onSaveContent = vi.fn();
    renderEditor({ onSaveContent });
    const options = mocks.useEditor.mock.calls.at(-1)?.[0];
    const onKeyDown = options.editorProps.handleKeyDown;

    for (const modifier of ["ctrlKey", "metaKey"] as const) {
      const event = new KeyboardEvent("keydown", { key: "s", cancelable: true, [modifier]: true });
      expect(onKeyDown({} as never, event)).toBe(true);
      expect(event.defaultPrevented).toBe(true);
    }
    expect(onSaveContent).toHaveBeenCalledTimes(2);

    const tab = new KeyboardEvent("keydown", { key: "Tab", cancelable: true });
    const fakeView = {
      state: { selection: { from: 1, to: 1 }, doc: { textBetween: vi.fn() }, tr: { insertText: vi.fn(() => "transaction") } },
      dispatch: vi.fn(),
    };
    expect(onKeyDown(fakeView as never, tab)).toBe(true);
    expect(tab.defaultPrevented).toBe(true);
    expect(fakeView.dispatch).toHaveBeenCalledWith("transaction");
    expect(onSaveContent).toHaveBeenCalledTimes(2);
  });

  test("saving shortcuts are not enabled for read-only manuscripts", () => {
    const onSaveContent = vi.fn();
    renderEditor({ onSaveContent, readOnly: true });
    const onKeyDown = mocks.useEditor.mock.calls.at(-1)?.[0].editorProps.handleKeyDown;
    const event = new KeyboardEvent("keydown", { key: "s", ctrlKey: true, cancelable: true });
    expect(onKeyDown({} as never, event)).toBe(false);
    expect(event.defaultPrevented).toBe(false);
    expect(onSaveContent).not.toHaveBeenCalled();
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
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
