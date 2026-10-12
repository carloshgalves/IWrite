import { render, screen, waitFor } from "@testing-library/react";
import React, { useState } from "react";
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
  test("nao reidrata props ecoadas na mesma instancia do editor", async () => {
    const onChange = vi.fn();
    const originalText = "primeira linha\ntexto antigo no meio\nultima linha";
    const echoText = "primeira linha\ntexto NOVOantigo no meio\nultima linha";
    const view = render(
      <TiptapEditor
        {...props({ readOnly: false, onChange })}
        initialContentText={originalText}
        initialContentJson={JSON.stringify(documentFor(originalText))}
      />
    );
    const editable = await screen.findByTestId("scene-content-editor");
    const selection = document.getSelection();
    selection?.collapse(editable.querySelectorAll("p")[1].firstChild, 6);

    view.rerender(
      <TiptapEditor
        {...props({ readOnly: false, onChange })}
        initialContentText={echoText}
        initialContentJson={JSON.stringify(documentFor(echoText))}
      />
    );

    expect(editable.querySelectorAll("p")[1].textContent).toBe("texto antigo no meio");
    expect(selection?.anchorNode).toBe(editable.querySelectorAll("p")[1].firstChild);
    expect(selection?.anchorOffset).toBe(6);
  });

  test("mantem cursor no ponto editado quando o pai ecoa o conteudo local", async () => {
    function EchoingEditor() {
      const [contentText, setContentText] = useState("primeira linha\ntexto antigo no meio\nultima linha");
      const [contentJson, setContentJson] = useState<string | undefined>();
      return (
        <>
          <output data-testid="echoed-content">{contentText}</output>
          <TiptapEditor
            initialContentJson={contentJson}
            initialContentText={contentText}
            readOnly={false}
            onChange={(json, text) => {
              setContentJson(JSON.stringify(json));
              setContentText(text);
            }}
          />
        </>
      );
    }

    render(<EchoingEditor />);
    const editable = await screen.findByTestId("scene-content-editor");
    const target = editable.querySelectorAll("p")[1].firstChild as Text;
    const selection = document.getSelection();
    selection?.collapse(target, 6);
    const originalGetClientRects = Range.prototype.getClientRects;
    const originalGetBoundingClientRect = Range.prototype.getBoundingClientRect;
    Range.prototype.getClientRects = () => Object.assign([], { item: () => null }) as DOMRectList;
    Range.prototype.getBoundingClientRect = () => new DOMRect();
    try {
      typeAtCaret(target, "NOVO");

      await waitFor(() => expect(screen.getByTestId("echoed-content").textContent).toContain("texto NOVOantigo no meio"));
      await waitFor(() => expect(editable.querySelectorAll("p")[1].textContent).toBe("texto NOVOantigo no meio"));
      await waitFor(() => expect(editable.contains(selection?.anchorNode ?? null)).toBe(true));
      expect(selection?.anchorNode).toBe(editable.querySelectorAll("p")[1].firstChild);
      expect(selection?.anchorOffset).toBe(10);
    } finally {
      Range.prototype.getClientRects = originalGetClientRects;
      Range.prototype.getBoundingClientRect = originalGetBoundingClientRect;
    }
  });

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
    initialContentText: "conteudo inicial",
    readOnly,
    onChange,
  } as React.ComponentProps<typeof TiptapEditor>;
}

function typeAtCaret(textNode: Text, text: string) {
  const selection = document.getSelection();
  const offset = selection?.anchorOffset ?? 0;
  textNode.insertData(offset, text);
  selection?.collapse(textNode, offset + text.length);
  textNode.parentElement?.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text }));
}

function documentFor(text: string) {
  return {
    type: "doc",
    content: text.split("\n").map((paragraph) => ({
      type: "paragraph",
      content: [{ type: "text", text: paragraph }],
    })),
  };
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
