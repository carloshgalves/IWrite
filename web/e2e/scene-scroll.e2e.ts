import { expect, test } from "@playwright/test";

/**
 * #251: browser-only proof of which actual DOM scrollport owns long-scene movement.
 * Must run against an isolated E2E stack with seeded demo credentials, never the writer's
 * persistent iwrite database. Normal GitHub PR CI does not execute this spec.
 *
 * Distinct from #246: this checks scroll ownership, not Tiptap's caret echo/re-hydration.
 */
test("long scene scrolls in one surface across metadata, focus and small viewport", async ({ page }) => {
  test.setTimeout(120_000);
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const bookTitle = `Scroll validation ${suffix}`;
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

  const contentEditable = page.getByTestId("scene-content-editor");
  await expect(contentEditable).toBeVisible();
  const longScene = Array.from({ length: 180 }, (_, i) =>
    `Paragraph ${i + 1}: text for validating one scroll surface in a large manuscript.`
  ).join("\n");
  await contentEditable.fill(longScene);

  const sceneScroll = page.getByRole("region", { name: "Área de leitura da cena" });
  await expect(sceneScroll).toBeVisible();
  await assertSingleScroll(sceneScroll);

  // No nested independent vertical scroll area is allowed anywhere inside the scene scrollport.
  // The outer workspace and outline may scroll independently; they are NOT descendants of this pane.
  await expect.poll(async () => sceneScroll.evaluate((el) =>
    [...el.querySelectorAll("*")].filter((child) => {
      const css = getComputedStyle(child);
      return (css.overflowY === "auto" || css.overflowY === "scroll") &&
        child.scrollHeight > child.clientHeight + 2;
    }).length
  )).toBe(0);

  // The pointer is over the text, but the only changing scrollTop belongs to the scene.
  await contentEditable.locator("p").first().scrollIntoViewIfNeeded();
  const firstParagraph = await contentEditable.locator("p").first().boundingBox();
  expect(firstParagraph).not.toBeNull();
  const scrollBefore = await sceneScroll.evaluate((element) => element.scrollTop);
  await page.mouse.move(firstParagraph!.x + 10, firstParagraph!.y + 10);
  const pageBefore = await page.evaluate(() => window.scrollY);
  await page.mouse.wheel(0, 550);
  await expect.poll(() => sceneScroll.evaluate((element) => element.scrollTop)).toBeGreaterThan(scrollBefore);
  expect(await page.evaluate(() => window.scrollY)).toBe(pageBefore);

  // Keyboard navigation works when focus is explicitly on the scene scroll region.
  await sceneScroll.focus();
  await page.keyboard.press("End");
  await expect.poll(() => sceneScroll.evaluate((el) => el.scrollHeight - el.clientHeight - el.scrollTop)).toBeLessThan(15);
  await page.keyboard.press("Home");
  await expect.poll(() => sceneScroll.evaluate((el) => el.scrollTop)).toBeLessThan(15);

  // Planning expands within that same scrolling column, not in a new pane.
  await page.getByRole("button", { name: "Expandir planejamento da cena" }).click();
  await expect(page.getByRole("button", { name: "Recolher planejamento da cena" })).toBeVisible();
  await assertSingleScroll(sceneScroll);
  await page.getByRole("button", { name: "Recolher planejamento da cena" }).click();

  // Focus uses the same scroll owner and the actual Tiptap DOM node.
  const initialEditorHandle = await contentEditable.elementHandle();
  await page.getByRole("button", { name: "Modo foco" }).click();
  await expect(page.getByRole("button", { name: "Sair do foco" })).toBeVisible();
  await assertSingleScroll(sceneScroll);
  expect(await contentEditable.evaluate((element, initial) => element === initial, initialEditorHandle)).toBe(true);

  // Shorter screens still retain a usable region; the mobile header layout is a separate UX issue.
  await page.setViewportSize({ width: 820, height: 540 });
  await expect.poll(() => sceneScroll.evaluate((element) => element.clientHeight)).toBeGreaterThan(100);
  await assertSingleScroll(sceneScroll);
});

async function assertSingleScroll(scrollport: import("@playwright/test").Locator) {
  expect(await scrollport.evaluate((element) => {
    const styles = getComputedStyle(element);
    return styles.overflowY === "auto" && element.scrollHeight > element.clientHeight + 20;
  })).toBe(true);
}
