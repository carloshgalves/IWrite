import { expect, test } from "@playwright/test";

/**
 * #248: a workspace URL must survive refresh and browser history with the correct tab and Scene.
 * Runs against the normal isolated E2E stack; never against a developer's persistent IWrite DB.
 */
test("restores tabs and the selected scene through reload and back/forward", async ({ page }) => {
  test.setTimeout(90_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const bookTitle = `Navigation smoke ${suffix}`;
  const sceneTitle = `Chapter marker ${suffix}`;

  await page.goto("/library");
  await page.getByPlaceholder("Ex.: A cidade de vidro").fill(bookTitle);
  await page.getByRole("button", { name: "Criar livro" }).click();

  await page.getByRole("article").filter({ hasText: bookTitle }).getByRole("link", { name: "Abrir workspace" }).click();
  await expect(page.getByRole("heading", { name: bookTitle }).first()).toBeVisible();
  const bookPath = new URL(page.url()).pathname;

  await page.getByPlaceholder(/Nova se/).fill(`Section ${suffix}`);
  await page.keyboard.press("Enter");
  await page.getByPlaceholder(/Novo cap/).fill(`Chapter ${suffix}`);
  await page.keyboard.press("Enter");
  await page.getByPlaceholder("Nova cena").fill(sceneTitle);
  await page.keyboard.press("Enter");
  await page.getByRole("button").filter({ hasText: sceneTitle }).click();
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();

  // Rendering the scene heading can beat the async Next.js URL/history transition.
  // Wait for the public deep link itself before reading it.
  await expect(page).toHaveURL(/[?&]sceneId=[^&]+/);
  const sceneId = new URL(page.url()).searchParams.get("sceneId");
  expect(sceneId).toBeTruthy();

  await page.getByRole("button", { name: "Localizações" }).click();
  await expect(page).toHaveURL(new RegExp("tab=locations"));
  expect(new URL(page.url()).searchParams.get("sceneId")).toBe(sceneId);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Localizações" })).toBeVisible();

  await page.getByRole("button", { name: "Caderno" }).click();
  await expect(page).toHaveURL(/tab=notebook/);
  await expect(page.getByRole("heading", { name: "Caderno", exact: true })).toBeVisible();

  await page.goBack();
  await expect(page).toHaveURL(/tab=locations/);
  await expect(page.getByRole("heading", { name: "Localizações" })).toBeVisible();
  await page.goForward();
  await expect(page).toHaveURL(/tab=notebook/);
  await expect(page.getByRole("heading", { name: "Caderno", exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Cenas" }).click();
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();
  await expect(page).not.toHaveURL(/tab=/);
  await page.reload();
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();

  // A substantial synthetic Scene gives the actual reading scrollport space to move.
  // The test reads only numeric scroll positions; no manuscript content enters diagnostics.
  const editor = page.getByTestId("scene-content-editor");
  const longText = Array.from({ length: 120 }, (_, index) =>
    `Synthetic paragraph ${index + 1} for reading position restoration.`
  ).join("\n");
  await editor.fill(longText);
  await expect(page.getByText("Digitando...").first()).toBeVisible();
  const saveResponsePromise = page.waitForResponse(
    (response) => response.url().endsWith(`/api/scenes/${sceneId}/content`) &&
      response.request().method() === "PATCH"
  );
  await page.getByRole("button", { name: "Salvar conteúdo" }).click();
  const saveResponse = await saveResponsePromise;
  expect(saveResponse.status()).toBe(200);
  await expect(page.getByText("Salvo").first()).toBeVisible();

  const shell = page.getByTestId("scene-shell-scrollport");
  const content = page.getByTestId("scene-content-scrollport");
  const shellCanScroll = await shell.evaluate((element) => element.scrollHeight - element.clientHeight > 100);
  const scrollport = shellCanScroll ? shell : content;
  const observedScroll = await scrollport.evaluate((element) => {
    element.scrollTop = 220;
    element.dispatchEvent(new Event("scroll"));
    return element.scrollTop;
  });
  expect(observedScroll, "the long Scene must actually have a scrollable reading surface").toBeGreaterThan(80);

  await page.reload();
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();
  const restoredScrollport = shellCanScroll
    ? page.getByTestId("scene-shell-scrollport")
    : page.getByTestId("scene-content-scrollport");
  await expect.poll(() => restoredScrollport.evaluate((element) => element.scrollTop)).toBeGreaterThan(80);

  // Unknown tabs fall back to Scenes, and a scene from another book is never trusted locally.
  await page.goto(`${bookPath}?sceneId=${encodeURIComponent(sceneId!)}&tab=unknown-tab`);
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();
  await page.goto(`${bookPath}?sceneId=scene-not-in-outline&tab=locations`);
  await expect(page).not.toHaveURL(/scene-not-in-outline/);
  await expect(page.getByRole("heading", { name: "Localizações" })).toBeVisible();
});
