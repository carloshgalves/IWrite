import { expect, test } from "@playwright/test";

/**
 * Issue #247: a separate blank "Entrar" window was observed after successful sign-in.
 *
 * This is an intentionally narrow browser-level regression probe. It detects popup/new-page
 * events (including a popup closed before the final assertion) and JavaScript dialogs without
 * collecting their URLs, contents, cookies, credentials, or manuscript data.
 *
 * It cannot detect native Edge/Windows account prompts or extension UI outside Playwright's
 * browser context. A green Chromium result is NOT proof that the original window is fixed.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("signing in as A, signing out, and signing in as B opens no extra window", async ({ page, context }) => {
  test.setTimeout(90_000);

  const passwordA = process.env.IWRITE_DEMO_AUTOR_A_PASSWORD;
  const passwordB = process.env.IWRITE_DEMO_AUTOR_B_PASSWORD;
  expect(passwordA, "IWRITE_DEMO_AUTOR_A_PASSWORD must be set").toBeTruthy();
  expect(passwordB, "IWRITE_DEMO_AUTOR_B_PASSWORD must be set").toBeTruthy();

  let phase = "initial navigation";
  const unexpectedEvents: string[] = [];

  // Listen before the first navigation: inspecting context.pages() only at the end would
  // miss a blank popup opened and closed during the login redirect.
  context.on("page", (opened) => {
    if (opened !== page) unexpectedEvents.push(`new page during ${phase}`);
  });
  page.on("popup", () => unexpectedEvents.push(`popup during ${phase}`));
  page.on("dialog", (dialog) => {
    unexpectedEvents.push(`JavaScript ${dialog.type()} during ${phase}`);
    // A listener must handle the dialog; otherwise Playwright can block subsequent actions.
    void dialog.dismiss();
  });

  async function assertNoExtraWindow() {
    // The recorded symptom appeared shortly AFTER the library was already visible. Absence is
    // inherently time-bounded, so keep the listeners active for an explicit observation window.
    await page.waitForTimeout(3_500);
    expect(unexpectedEvents, `Unexpected window/dialog detected through ${phase}`).toEqual([]);
    expect(context.pages(), `An extra browser page remains open after ${phase}`).toHaveLength(1);
  }

  phase = "Autor A login";
  await page.goto("/login");
  await page.getByLabel("Email").fill("autor-a@iwrite.local");
  await page.getByLabel("Senha").fill(passwordA!);
  await page.getByRole("button", { name: "Entrar" }).click();

  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText("A Cidade de Vidro")).toBeVisible();
  const sessionA = await page.request.get("/api/auth/me");
  expect(sessionA.status()).toBe(200);
  expect((await sessionA.json()).user.email).toBe("autor-a@iwrite.local");
  await assertNoExtraWindow();

  phase = "Autor A logout";
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/login/);
  expect((await page.request.get("/api/auth/me")).status()).toBe(401);
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
  await assertNoExtraWindow();

  phase = "Autor B login";
  await page.getByLabel("Email").fill("autor-b@iwrite.local");
  await page.getByLabel("Senha").fill(passwordB!);
  await page.getByRole("button", { name: "Entrar" }).click();

  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText("O Jardim Submerso")).toBeVisible();
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
  const sessionB = await page.request.get("/api/auth/me");
  expect(sessionB.status()).toBe(200);
  expect((await sessionB.json()).user.email).toBe("autor-b@iwrite.local");
  await assertNoExtraWindow();
});
