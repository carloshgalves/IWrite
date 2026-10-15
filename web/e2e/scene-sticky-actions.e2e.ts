import { expect, test } from "@playwright/test";

/**
 * #252: real browser regression of deep-scene toolbar visibility, selection and manual save.
 * Intended for the isolated E2E Compose stack. Never target a persistent writer database.
 * This is NOT executed by the regular PR CI workflow.
 */
test("formatting and saving stay reachable in a long scene without a viewport jump", async ({ page }) => {
  test.setTimeout(120_000);
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const bookTitle = `Sticky writing toolbar ${suffix}`;
  const sceneTitle = `Long scene ${suffix}`;

  await page.goto("/library");
  await page.getByPlaceholder("Ex.: A cidade de vidro").fill(bookTitle);
  await page.getByRole("button", { name: "Criar livro" }).click();
  await page.getByRole("article").filter({ hasText: bookTitle })
    .getByRole("link", { name: "Abrir workspace" }).click();

  await page.getByPlaceholder(/Nova se/).fill(`Part ${suffix}`);
  await page.keyboard.press("Enter");
  await page.getByPlaceholder(/Novo cap/).fill(`Chapter ${suffix}`);
  await page.keyboard.press("Enter");
  await page.getByPlaceholder("Nova cena").fill(sceneTitle);
  await page.keyboard.press("Enter");
  await page.getByRole("button").filter({ hasText: sceneTitle }).click();

  const editor = page.getByTestId("scene-content-editor");
  const toolbar = page.getByRole("toolbar", { name: "Ferramentas de escrita" });
  const save = page.getByRole("button", { name: "Salvar agora" });
  const status = page.getByRole("status", { name: "Estado do salvamento" });
  await expect(editor).toBeVisible();

  await editor.fill(Array.from({ length: 145 }, (_, i) =>
    `Paragraph ${i + 1} for testing sticky formatting and writing without viewport jumps.`
  ).join("\n"));

  // Scroll to a paragraph far below the toolbar's original document position.
  const paragraph = editor.locator("p").nth(100);
  await paragraph.scrollIntoViewIfNeeded();
  await expect(paragraph).toBeVisible();
  await expect(toolbar).toBeInViewport({ ratio: 0.5 });
  await expect(save).toBeInViewport({ ratio: 0.5 });
  await expect(status).toBeInViewport({ ratio: 0.5 });

  const scrollport = editor.locator("xpath=ancestor::*[contains(@class, 'overflow-y-auto')][1]");
  const initialScroll = await scrollport.evaluate((element) => element.scrollTop);
  expect(initialScroll).toBeGreaterThan(100);

  const selected = await paragraph.evaluate((element) => {
    const textNode = element.firstChild;
    if (!textNode || textNode.nodeType !== Node.TEXT_NODE) throw new Error("Expected plain paragraph");
    const range = document.createRange();
    range.setStart(textNode, 0);
    range.setEnd(textNode, 9);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    return selection?.toString();
  });
  expect(selected).toBe("Paragraph");

  // Mouse selection is preserved by preventDefault on toolbar mousedown, while TipTap receives
  // focus without scrolling into view. Formatting must not return the user to scene metadata.
  await page.getByRole("button", { name: "B", exact: true }).click();
  expect(await page.evaluate(() => window.getSelection()?.toString())).toBe("Paragraph");
  await expect.poll(() => scrollport.evaluate((el) => el.scrollTop)).toBeGreaterThan(initialScroll - 12);
  await expect.poll(() => scrollport.evaluate((el) => el.scrollTop)).toBeLessThan(initialScroll + 12);
  await expect(toolbar).toBeInViewport({ ratio: 0.5 });

  // The nearby manual action uses the same backend PATCH as the scene header.
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes("/api/scenes/") &&
    response.url().endsWith("/content") &&
    response.request().method() === "PATCH"
  );
  await save.click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  await expect(status).toHaveText("Salvo");
  await expect(toolbar).toBeInViewport({ ratio: 0.5 });

  // No special dialog or browser "Save page" from Ctrl+S. The command stays within the editor.
  await editor.focus();
  const shortcutResponsePromise = page.waitForResponse((response) =>
    response.url().includes("/api/scenes/") &&
    response.url().endsWith("/content") &&
    response.request().method() === "PATCH"
  );
  await editor.press("Control+s");
  expect((await shortcutResponsePromise).status()).toBe(200);

  await page.setViewportSize({ width: 820, height: 540 });
  await editor.locator("p").nth(100).scrollIntoViewIfNeeded();
  await expect(toolbar).toBeInViewport({ ratio: 0.35 });
  await expect(save).toBeInViewport({ ratio: 0.5 });

  await page.getByRole("button", { name: "Modo foco" }).click();
  await editor.locator("p").nth(100).scrollIntoViewIfNeeded();
  await expect(toolbar).toBeInViewport({ ratio: 0.35 });
  await expect(save).toBeInViewport({ ratio: 0.5 });
});
