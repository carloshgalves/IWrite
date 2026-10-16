import { render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { SceneContentEditor } from "@/features/scenes/components/scene-content-editor";

vi.mock("@/features/scenes/editor/tiptap-editor", () => ({
  TiptapEditor: ({ initialContentText, className }: { initialContentText: string; className: string }) => (
    <div data-testid="scene-content-editor" className={className}>{initialContentText}</div>
  ),
}));

const text = Array.from({ length: 120 }, (_, index) => `Paragraph ${index + 1}`).join("\n");

function contentEditor(isFocusMode = false) {
  return (
    <SceneContentEditor
      contentKey="scene-1:0"
      sourceSceneId="scene-1"
      contentJson="{}"
      contentText={text}
      readOnly={false}
      wordCount={240}
      isSuccess={false}
      isError={false}
      saveStatus="saved"
      isFocusMode={isFocusMode}
      onContentChange={vi.fn()}
    />
  );
}

describe("SceneContentEditor unified scroll strategy (#251)", () => {
  test.each([false, true])("long text does not create a second scrollport (focus=%s)", (focusMode) => {
    const { container } = render(contentEditor(focusMode));
    const contentArea = container.firstElementChild;
    expect(contentArea).not.toBeNull();
    expect(contentArea).toHaveClass("grid", "w-full");
    expect(contentArea).not.toHaveClass("overflow-y-auto", "h-full", "min-h-0");
    expect(contentArea?.querySelectorAll(".overflow-y-auto")).toHaveLength(0);
    expect(screen.getByTestId("scene-content-editor")).toHaveTextContent("Paragraph 120");
    expect(screen.getByTestId("scene-content-editor")).toHaveClass("min-h-[66vh]");
  });

  test("normal and focus mode change only padding and width, not scroll ownership or editor identity", () => {
    const view = render(contentEditor(false));
    const editable = screen.getByTestId("scene-content-editor");
    view.rerender(contentEditor(true));

    expect(screen.getByTestId("scene-content-editor")).toBe(editable);
    expect(view.container.firstElementChild).not.toHaveClass("overflow-y-auto");
    expect(screen.getByText("Conteúdo textual")).toBeInTheDocument();
  });

  test("a local save error does not add scrollbars or hide the writing surface", () => {
    const view = render(contentEditor());
    const error = (
      <SceneContentEditor
        contentKey="scene-1:0"
        sourceSceneId="scene-1"
        contentJson="{}"
        contentText={text}
        readOnly={false}
        wordCount={240}
        isSuccess={false}
        isError
        saveStatus="error"
        onContentChange={vi.fn()}
      />
    );
    view.rerender(error);
    expect(screen.getByTestId("scene-content-editor")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Não foi possível salvar");
    expect(view.container.firstElementChild).not.toHaveClass("overflow-y-auto");
  });
});
