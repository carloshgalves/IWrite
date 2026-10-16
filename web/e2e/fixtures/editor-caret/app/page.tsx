"use client";

import { useState } from "react";
import type { JSONContent } from "@tiptap/react";
import { TiptapEditor } from "@/features/scenes/editor/tiptap-editor";

const initialText = Array.from({ length: 70 }, (_, index) =>
  index === 35 ? "synthetic paragraph 36 for caret verification" : `synthetic filler paragraph ${index + 1}`
).join("\n");

export default function EditorCaretFixture() {
  const [contentText, setContentText] = useState(initialText);
  const [contentJson, setContentJson] = useState<JSONContent | undefined>();

  return (
    <main>
      <div data-testid="editor-scrollport" style={{ height: 360, overflowY: "auto" }}>
        <TiptapEditor
          initialContentJson={contentJson}
          initialContentText={contentText}
          readOnly={false}
          onChange={(json, text) => {
            setContentJson(json);
            setContentText(text);
          }}
        />
      </div>
      <output data-testid="echoed-content">{contentText}</output>
    </main>
  );
}
