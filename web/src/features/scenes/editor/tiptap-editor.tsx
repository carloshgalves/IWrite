"use client";

import { useEffect, useMemo, type ReactNode } from "react";
import type { Editor } from "@tiptap/react";
import { EditorContent, useEditor, type JSONContent } from "@tiptap/react";
import TextAlign from "@tiptap/extension-text-align";
import StarterKit from "@tiptap/starter-kit";
import { handleTiptapTabKey } from "@/features/scenes/editor/tab-keymap";

type TiptapEditorProps = {
  contentKey: string;
  initialContentJson?: JSONContent | string | null;
  initialContentText?: string | null;
  /** Read-only mode: the document renders, the toolbar is inert and no edit reaches onChange. */
  readOnly?: boolean;
  onChange: (contentJson: JSONContent, contentText: string) => void;
  className?: string;
};

function plainTextToDocument(text: string): JSONContent {
  const paragraphs = text.split(/\r?\n/);

  return {
    type: "doc",
    content: paragraphs.map((paragraph) => ({
      type: "paragraph",
      content: paragraph ? [{ type: "text", text: paragraph }] : undefined,
    })),
  };
}

function parseContentJson(contentJson: JSONContent | string | null | undefined): JSONContent | null {
  if (!contentJson) {
    return null;
  }

  if (typeof contentJson === "string") {
    try {
      const parsed = JSON.parse(contentJson) as JSONContent;
      return parsed?.type === "doc" ? parsed : null;
    } catch {
      return null;
    }
  }

  return contentJson.type === "doc" ? contentJson : null;
}

type ToolbarButtonProps = {
  label: ReactNode;
  ariaLabel?: string;
  title?: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
};

function ToolbarButton({ label, ariaLabel, title, active, disabled, onClick }: ToolbarButtonProps) {
  return (
    <button
      type="button"
      aria-label={ariaLabel}
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex min-h-8 items-center justify-center rounded-md border px-2.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${
        active
          ? "border-zinc-900 bg-zinc-900 text-white"
          : "border-zinc-200 bg-white text-zinc-700 hover:border-zinc-300 hover:bg-zinc-50"
      }`}
    >
      {label}
    </button>
  );
}

type AlignIconProps = {
  align: "left" | "center" | "right" | "justify";
};

function AlignIcon({ align }: AlignIconProps) {
  const lines: Record<AlignIconProps["align"], Array<{ x1: number; x2: number; y: number }>> = {
    left: [
      { x1: 3, x2: 21, y: 5 },
      { x1: 3, x2: 15, y: 10 },
      { x1: 3, x2: 21, y: 15 },
      { x1: 3, x2: 13, y: 20 },
    ],
    center: [
      { x1: 3, x2: 21, y: 5 },
      { x1: 6, x2: 18, y: 10 },
      { x1: 3, x2: 21, y: 15 },
      { x1: 7, x2: 17, y: 20 },
    ],
    right: [
      { x1: 3, x2: 21, y: 5 },
      { x1: 9, x2: 21, y: 10 },
      { x1: 3, x2: 21, y: 15 },
      { x1: 11, x2: 21, y: 20 },
    ],
    justify: [
      { x1: 3, x2: 21, y: 5 },
      { x1: 3, x2: 21, y: 10 },
      { x1: 3, x2: 21, y: 15 },
      { x1: 3, x2: 21, y: 20 },
    ],
  };

  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      {lines[align].map((line) => (
        <line key={`${line.x1}-${line.x2}-${line.y}`} x1={line.x1} x2={line.x2} y1={line.y} y2={line.y} />
      ))}
    </svg>
  );
}

function TiptapToolbar({ editor }: { editor: Editor | null }) {
  return (
    <div className="flex flex-wrap gap-2 rounded-t-lg border border-b-0 border-zinc-200 bg-zinc-50 px-3 py-2">
      <ToolbarButton label="B" active={editor?.isActive("bold")} disabled={!editor} onClick={() => editor?.chain().focus().toggleBold().run()} />
      <ToolbarButton label="I" active={editor?.isActive("italic")} disabled={!editor} onClick={() => editor?.chain().focus().toggleItalic().run()} />
      <ToolbarButton
        label="Parágrafo"
        active={editor?.isActive("paragraph")}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setParagraph().run()}
      />
      <ToolbarButton
        label="Título"
        active={editor?.isActive("heading", { level: 2 })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().toggleHeading({ level: 2 }).run()}
      />
      <ToolbarButton
        label={<AlignIcon align="left" />}
        ariaLabel={"Alinhar \u00e0 esquerda"}
        title="Alinhar a esquerda"
        active={editor?.isActive({ textAlign: "left" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setTextAlign("left").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="center" />}
        ariaLabel="Centralizar"
        title="Centralizar"
        active={editor?.isActive({ textAlign: "center" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setTextAlign("center").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="right" />}
        ariaLabel={"Alinhar \u00e0 direita"}
        title="Alinhar a direita"
        active={editor?.isActive({ textAlign: "right" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setTextAlign("right").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="justify" />}
        ariaLabel="Justificar"
        title="Justificar"
        active={editor?.isActive({ textAlign: "justify" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setTextAlign("justify").run()}
      />
      <ToolbarButton label="Desfazer" disabled={!editor || !editor.can().undo()} onClick={() => editor?.chain().focus().undo().run()} />
      <ToolbarButton label="Refazer" disabled={!editor || !editor.can().redo()} onClick={() => editor?.chain().focus().redo().run()} />
    </div>
  );
}

export function TiptapEditor({
  contentKey,
  initialContentJson,
  initialContentText,
  readOnly = false,
  onChange,
  className = "",
}: TiptapEditorProps) {
  const initialContent = useMemo(
    () => parseContentJson(initialContentJson) ?? plainTextToDocument(initialContentText ?? ""),
    [initialContentJson, initialContentText]
  );

  const editor = useEditor({
    extensions: [StarterKit, TextAlign.configure({ types: ["paragraph", "heading"] })],
    content: initialContent,
    editable: !readOnly,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class: `min-h-[320px] whitespace-pre-wrap rounded-lg border border-zinc-200 bg-white px-4 py-4 text-base leading-7 text-zinc-900 outline-none [tab-size:4] focus:border-zinc-800 ${className}`,
        "aria-label": "Scene content editor",
        "data-testid": "scene-content-editor",
      },
      handleKeyDown: handleTiptapTabKey,
    },
    onUpdate: ({ editor: updatedEditor }) => {
      onChange(updatedEditor.getJSON(), updatedEditor.getText());
    },
  });

  useEffect(() => {
    if (!editor) {
      return;
    }

    editor.commands.setContent(initialContent, { emitUpdate: false });
  }, [contentKey, editor, initialContent]);

  // The effective access arrives from the backend after the editor is created, so the editable flag
  // has to follow it rather than being fixed at construction time.
  useEffect(() => {
    editor?.setEditable(!readOnly);
  }, [editor, readOnly]);

  return (
    <div>
      {readOnly ? null : <TiptapToolbar editor={editor} />}
      <EditorContent editor={editor} />
    </div>
  );
}
