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
  // React can render the book heading before Next commits the browser URL.
  // Capture the deep link only after the navigation itself has finished.
  await expect(page).toHaveURL(/\/books\/[^/?#]+(?:[?#]|$)/);
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

  // Regaining browser focus runs SessionGuard reconciliation, temporarily unmounting
  // the entire protected workspace. The selected tab must survive that real remount.
  let releaseSessionRequest: () => void = () => undefined;
  const sessionGate = new Promise<void>((resolve) => {
    releaseSessionRequest = resolve;
  });
  await page.route("**/api/auth/me", async (route) => {
    await sessionGate;
    await route.continue();
  });
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page.getByText("Verificando sessão…")).toBeVisible();
  releaseSessionRequest();
  await expect(page.getByRole("heading", { name: "Caderno", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/tab=notebook/);
  await page.unroute("**/api/auth/me");

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

  // The Scene has two independently persisted scroll surfaces. Which one actually
  // overflows depends on the responsive layout, viewport and the expanded panels.
  // Select a *genuinely scrollable* surface rather than assuming the inner editor
  // always has a fixed height (it can legitimately expand to its full content height).
  const surfaces = [
    { name: "scene-shell-scrollport", storageSuffix: "scene-shell" },
    { name: "scene-content-scrollport", storageSuffix: "scene-content" },
  ] as const;
  const ranges = await Promise.all(
    surfaces.map(async (surface) => ({
      ...surface,
      max: await page.getByTestId(surface.name).evaluate((element) =>
        Math.max(0, element.scrollHeight - element.clientHeight)
      ),
    }))
  );
  const chosen = ranges.sort((a, b) => b.max - a.max)[0];
  expect(chosen.max, "the persisted manuscript must offer a real scrollable reading surface").toBeGreaterThan(30);
  const readingScrollport = page.getByTestId(chosen.name);
  const observedScroll = await readingScrollport.evaluate((element) => {
    element.scrollTop = 220;
    element.dispatchEvent(new Event("scroll"));
    return element.scrollTop;
  });
  expect(observedScroll, "reading position must actually move").toBeGreaterThan(30);

  const bookId = new URL(page.url()).pathname.split("/").pop()!;
  const scrollStorageKey = `iwrite.sceneScroll.v1:${encodeURIComponent(bookId)}:${encodeURIComponent(sceneId!)}:${chosen.storageSuffix}`;
  await expect.poll(
    () => page.evaluate((key) => window.sessionStorage.getItem(key), scrollStorageKey)
  ).toBe(String(Math.round(observedScroll)));

  await page.reload();
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();
  await expect(page.getByTestId("scene-content-editor")).toContainText("Synthetic paragraph 120");
  // A different viewport/layout after hydration can cap the restored position at
  // its new maximum. Still demand a nonzero restoration on the same reading surface.
  await expect.poll(async () => readingScrollport.evaluate((element, savedPosition) => {
    const actualLimit = Math.max(0, element.scrollHeight - element.clientHeight);
    const expected = Math.min(savedPosition, actualLimit);
    return actualLimit > 30 && element.scrollTop >= expected - 2 && element.scrollTop > 30;
  }, observedScroll)).toBe(true);

  // Unknown tabs fall back to Scenes, and a scene from another book is never trusted locally.
  await page.goto(`${bookPath}?sceneId=${encodeURIComponent(sceneId!)}&tab=unknown-tab`);
  await expect(page.getByRole("heading", { name: sceneTitle })).toBeVisible();
  await page.goto(`${bookPath}?sceneId=scene-not-in-outline&tab=locations`);
  await expect(page).not.toHaveURL(/scene-not-in-outline/);
  await expect(page.getByRole("heading", { name: "Localizações" })).toBeVisible();
});
