import { expect, test } from "@playwright/test";

test("keeps the caret and editor scroll position during a mid-paragraph edit", async ({ page }) => {
  await page.goto("/");

  const editor = page.getByTestId("scene-content-editor");
  const paragraph = editor.locator("p").nth(35);
  await expect(paragraph).toHaveText("synthetic paragraph 36 for caret verification");

  await paragraph.evaluate((element) => {
    const text = element.firstChild;
    if (!text) throw new Error("Expected target paragraph text node");
    const range = document.createRange();
    range.setStart(text, 11);
    range.collapse(true);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  });

  const scrollport = page.getByTestId("editor-scrollport");
  await paragraph.evaluate((element) => element.scrollIntoView({ block: "center" }));
  const scrollBefore = await scrollport.evaluate((element) => element.scrollTop);

  await page.keyboard.type("INSERTED");

  await expect(paragraph).toHaveText("synthetic pINSERTEDaragraph 36 for caret verification");
  await expect
    .poll(() =>
      editor.evaluate((element) => {
        const selection = window.getSelection();
        const text = element.querySelector("p:nth-child(36)")?.firstChild;
        if (!selection || !text) return -1;
        return selection.anchorNode === text ? selection.anchorOffset : -1;
      })
    )
    .toBe(19);
  await expect.poll(() => scrollport.evaluate((element) => element.scrollTop)).toBe(scrollBefore);
});
