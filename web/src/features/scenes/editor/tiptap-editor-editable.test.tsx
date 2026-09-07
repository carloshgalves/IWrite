import { render, screen, waitFor } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { TiptapEditor } from "@/features/scenes/editor/tiptap-editor";

/**
 * Editability synchronization against a real TipTap editor (#207).
 *
 * <p>The toolbar suite mounts this component with `@tiptap/react` mocked, so it cannot observe what
 * `setEditable` actually does. TipTap emits an update from `setEditable` by default, and this editor
 * synchronizes editability with the effective access that arrives from the backend — so mounting a
 * scene, or a capability landing a moment later, would emit `onChange` with no human edit behind it,
 * marking the scene dirty and arming autosave by itself.
 *
 * <p>These tests mount the real editor. The permission transitions must stay silent, and a genuine
 * edit must still be reported.
 */
describe("TiptapEditor editability synchronization", () => {
  test("montar em somente leitura nao dispara onChange", async () => {
    const onChange = vi.fn();

    renderEditor({ readOnly: true, onChange });
    await screen.findByTestId("scene-content-editor");

    await expectNoChange(onChange);
  });

  test("montar editavel nao dispara onChange", async () => {
    const onChange = vi.fn();

    renderEditor({ readOnly: false, onChange });
    await screen.findByTestId("scene-content-editor");

    await expectNoChange(onChange);
  });

  test("a transicao de somente leitura para editavel nao dispara onChange", async () => {
    const onChange = vi.fn();
    const view = renderEditor({ readOnly: true, onChange });
    await screen.findByTestId("scene-content-editor");
    onChange.mockClear();

    view.rerender(<TiptapEditor {...props({ readOnly: false, onChange })} />);

    await expectNoChange(onChange);
  });

  test("a transicao de editavel para somente leitura nao dispara onChange", async () => {
    const onChange = vi.fn();
    const view = renderEditor({ readOnly: false, onChange });
    await screen.findByTestId("scene-content-editor");
    onChange.mockClear();

    view.rerender(<TiptapEditor {...props({ readOnly: true, onChange })} />);

    await expectNoChange(onChange);
  });

  test("uma edicao real continua emitindo onChange", async () => {
    const onChange = vi.fn();
    renderEditor({ readOnly: false, onChange });
    const editable = await screen.findByTestId("scene-content-editor");
    onChange.mockClear();

    typeInto(editable, " editado");

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    const [, contentText] = onChange.mock.calls.at(-1) as [unknown, string];
    expect(contentText).toContain("editado");
  });

  // Negative control: the silence above must come from not emitting on a permission change, never
  // from an editor that stopped reporting edits at all.
  test("uma edicao no modo somente leitura nao emite onChange", async () => {
    const onChange = vi.fn();
    renderEditor({ readOnly: true, onChange });
    const editable = await screen.findByTestId("scene-content-editor");
    onChange.mockClear();

    typeInto(editable, " proibido");

    await expectNoChange(onChange);
  });
});

function props({ readOnly, onChange }: { readOnly: boolean; onChange: (contentJson: unknown, contentText: string) => void }) {
  return {
    contentKey: "scene-1:0",
    initialContentText: "conteudo inicial",
    readOnly,
    onChange,
  } as React.ComponentProps<typeof TiptapEditor>;
}

function renderEditor(options: { readOnly: boolean; onChange: (contentJson: unknown, contentText: string) => void }) {
  return render(<TiptapEditor {...props(options)} />);
}

/** Writes into the contenteditable the way a browser does, so ProseMirror observes a real DOM change. */
function typeInto(editable: HTMLElement, text: string) {
  const paragraph = editable.querySelector("p");
  expect(paragraph).not.toBeNull();
  (paragraph as HTMLParagraphElement).appendChild(document.createTextNode(text));
}

/** ProseMirror reports changes asynchronously, so silence has to be given time to be broken. */
async function expectNoChange(onChange: ReturnType<typeof vi.fn>) {
  await new Promise((resolve) => setTimeout(resolve, 200));
  expect(onChange).not.toHaveBeenCalled();
}
