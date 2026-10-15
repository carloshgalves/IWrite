"use client";

import { useEffect, useMemo, useRef, type ReactNode } from "react";
import type { Editor } from "@tiptap/react";
import { EditorContent, useEditor, type JSONContent } from "@tiptap/react";
import TextAlign from "@tiptap/extension-text-align";
import StarterKit from "@tiptap/starter-kit";
import { handleTiptapTabKey } from "@/features/scenes/editor/tab-keymap";

type TiptapEditorProps = {
  initialContentJson?: JSONContent | string | null;
  initialContentText?: string | null;
  /** Read-only mode: the document renders, the toolbar is inert and no edit reaches onChange. */
  readOnly?: boolean;
  onChange: (contentJson: JSONContent, contentText: string) => void;
  className?: string;
  /** Optional domain actions (save/status), kept next to formatting in the same sticky row. */
  toolbarActions?: ReactNode;
  /** Ctrl/Cmd+S inside the editable surface saves without moving editor selection. */
  onSaveContent?: () => void;
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
      // Mouse click on the formatting toolbar must not collapse the current text selection.
      // Keyboard focus remains available via Tab/Shift+Tab from outside the editable surface.
      onMouseDown={(event) => event.preventDefault()}
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

function TiptapToolbar({ editor, actions }: { editor: Editor | null; actions?: ReactNode }) {
  return (
    <div role="toolbar" aria-label="Ferramentas de escrita" className="sticky top-0 z-20 flex min-w-0 items-center gap-1 rounded-t-lg border border-b-0 border-zinc-200 bg-zinc-50/95 p-2 shadow-sm backdrop-blur-sm">
      <div role="group" aria-label="Formatação" className="flex min-w-0 flex-1 items-center gap-2 overflow-x-auto overscroll-x-contain pb-1">
      <ToolbarButton label="B" active={editor?.isActive("bold")} disabled={!editor} onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).toggleBold().run()} />
      <ToolbarButton label="I" active={editor?.isActive("italic")} disabled={!editor} onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).toggleItalic().run()} />
      <ToolbarButton
        label="Parágrafo"
        active={editor?.isActive("paragraph")}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).setParagraph().run()}
      />
      <ToolbarButton
        label="Título"
        active={editor?.isActive("heading", { level: 2 })}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).toggleHeading({ level: 2 }).run()}
      />
      <ToolbarButton
        label={<AlignIcon align="left" />}
        ariaLabel={"Alinhar \u00e0 esquerda"}
        title="Alinhar a esquerda"
        active={editor?.isActive({ textAlign: "left" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).setTextAlign("left").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="center" />}
        ariaLabel="Centralizar"
        title="Centralizar"
        active={editor?.isActive({ textAlign: "center" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).setTextAlign("center").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="right" />}
        ariaLabel={"Alinhar \u00e0 direita"}
        title="Alinhar a direita"
        active={editor?.isActive({ textAlign: "right" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).setTextAlign("right").run()}
      />
      <ToolbarButton
        label={<AlignIcon align="justify" />}
        ariaLabel="Justificar"
        title="Justificar"
        active={editor?.isActive({ textAlign: "justify" })}
        disabled={!editor}
        onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).setTextAlign("justify").run()}
      />
      <ToolbarButton label="Desfazer" disabled={!editor || !editor.can().undo()} onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).undo().run()} />
      <ToolbarButton label="Refazer" disabled={!editor || !editor.can().redo()} onClick={() => editor?.chain().focus(undefined, { scrollIntoView: false }).redo().run()} />
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2 border-l border-zinc-200 pl-2">{actions}</div> : null}
    </div>
  );
}

export function TiptapEditor({
  initialContentJson,
  initialContentText,
  readOnly = false,
  onChange,
  className = "",
  toolbarActions,
  onSaveContent,
}: TiptapEditorProps) {
  const initialContent = useMemo(
    () => parseContentJson(initialContentJson) ?? plainTextToDocument(initialContentText ?? ""),
    [initialContentJson, initialContentText]
  );

  const onSaveContentRef = useRef(onSaveContent);
  const readOnlyRef = useRef(readOnly);
  onSaveContentRef.current = onSaveContent;
  readOnlyRef.current = readOnly;

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
      handleKeyDown: (view, event) => {
        if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === "s" &&
            !readOnlyRef.current && onSaveContentRef.current) {
          event.preventDefault();
          onSaveContentRef.current();
          return true;
        }
        // Preserve the existing Tab/Shift+Tab editor behavior.
        return handleTiptapTabKey(view, event);
      },
    },
    onUpdate: ({ editor: updatedEditor }) => {
      onChange(updatedEditor.getJSON(), updatedEditor.getText());
    },
  });

  // The effective access arrives from the backend after the editor is created, so the editable flag
  // has to follow it rather than being fixed at construction time.
  //
  // Without emitting an update: setEditable emits one by default, and this call runs on mount and on
  // every permission change. That update would reach onChange with no human edit behind it, marking
  // the scene dirty and arming autosave just for opening it or for a capability arriving late.
  useEffect(() => {
    editor?.setEditable(!readOnly, false);
  }, [editor, readOnly]);

  return (
    <div>
      {readOnly ? null : <TiptapToolbar editor={editor} actions={toolbarActions} />}
      <EditorContent editor={editor} />
    </div>
  );
}
