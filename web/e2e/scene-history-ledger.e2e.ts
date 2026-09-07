import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import type { UpdatePersonalBookWritingGoalRequest } from "@/features/writing-goal/types";

// Relative, so calls go through the frontend proxy on baseURL and carry the session cookie.
// Hitting the backend port directly would be cross-site: no cookie, and CORS in the way.
const API_URL = "";
const AUTOSAVE_DEBOUNCE_BUFFER_MS = 1_600;

type Book = {
  id: string;
  title: string;
};

type Section = {
  id: string;
  title: string;
};

type Chapter = {
  id: string;
  title: string;
};

type Scene = {
  id: string;
  title: string;
  contentText: string | null;
  contentRevision: number;
  wordCount: number;
};

type SceneVersionSummary = {
  id: string;
  contentTextPreview: string;
  wordCount: number;
};

type SceneVersionPage = {
  items: SceneVersionSummary[];
  hasNext: boolean;
};

type Dashboard = {
  totalWordCount: number;
  myWriting: { progress: {
    today: {
      productiveWordCountChange: number;
      manuscriptAdjustmentWordCount: number;
      endingManuscriptWordCount: number;
    };
    recentDays: Array<{
      productiveWordCountChange: number;
      manuscriptAdjustmentWordCount: number;
    }>;
    consistency: {
      currentStreakDays: number;
    };
  } };
};

type SeededBook = {
  book: Book;
  section: Section;
  chapter: Chapter;
  scene: Scene;
};

test("restores an earlier scene version without inflating productivity", async ({ page }) => {
  test.setTimeout(90_000);

  const suffix = uniqueSuffix();
  const seeded = await createSeededBook(page.request, suffix, { dailyTargetWordCount: 500 });
  const contentA = "red blue gold";
  const contentB = "silver copper iron slate pearl";

  await openScene(page, seeded.book.id, seeded.scene.id, seeded.book.title);
  await saveEditorContent(page, contentA);
  await saveEditorContent(page, contentB);

  await page.getByRole("button", { name: /Hist/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Historico da cena" })).toBeVisible();
  await dialog.getByRole("button").filter({ hasText: contentA }).click();
  await expect(dialog.getByText(contentA, { exact: true })).toBeVisible();

  page.once("dialog", (confirmDialog) => {
    void confirmDialog.accept();
  });
  const restoreResponse = page.waitForResponse((response) =>
    response.url().includes(`/api/scenes/${seeded.scene.id}/versions/`) &&
    response.url().endsWith("/restore") &&
    response.request().method() === "POST"
  );
  await dialog.getByRole("button", { name: "Restaurar versao" }).click();
  await restoreResponse;

  const editor = page.getByTestId("scene-content-editor");
  await expect(editor).toContainText(contentA);
  await expect(editor).not.toContainText(contentB);
  await expect(page.getByText("3 palavras").first()).toBeVisible();

  await page.waitForTimeout(AUTOSAVE_DEBOUNCE_BUFFER_MS);
  await expect(editor).toContainText(contentA);
  await expect(editor).not.toContainText(contentB);

  const dashboard = await getDashboard(page.request, seeded.book.id);
  expect(dashboard.totalWordCount).toBe(3);
  expect(dashboard.myWriting.progress.today.productiveWordCountChange).toBe(5);
  expect(dashboard.myWriting.progress.today.manuscriptAdjustmentWordCount).toBe(-2);
  expect(dashboard.myWriting.progress.today.endingManuscriptWordCount).toBe(3);

  await page.getByRole("button", { name: /Vis/ }).click();
  await expect(page.getByTestId("manuscript-adjustment-summary")).toContainText("-2 palavras");
  await expect(page.getByText("Hoje: 5 / 500 palavras")).toBeVisible();

  const exportedMarkdown = await exportMarkdown(page.request, seeded.book.id);
  expect(exportedMarkdown).toContain(contentA);
  expect(exportedMarkdown).not.toContain(contentB);
});

test("saves dirty local content before restoring and keeps it recoverable", async ({ page }) => {
  test.setTimeout(90_000);

  const suffix = uniqueSuffix();
  const seeded = await createSeededBook(page.request, suffix);
  const contentA = "recoverable original text";
  const contentB = "current persisted replacement";
  const dirtyContent = "dirty local draft survives history";

  const scene = await updateSceneContent(page.request, seeded.scene.id, contentA, seeded.scene.contentRevision, "MANUAL_SAVE");
  await updateSceneContent(page.request, seeded.scene.id, contentB, scene.contentRevision, "MANUAL_SAVE");

  await openScene(page, seeded.book.id, seeded.scene.id, seeded.book.title);
  const editor = page.getByTestId("scene-content-editor");
  await editor.fill(dirtyContent);
  await expect(page.getByText("Digitando...").first()).toBeVisible();

  await page.getByRole("button", { name: /Hist/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Historico da cena" })).toBeVisible();
  await dialog.getByRole("button").filter({ hasText: contentA }).click();
  await dialog.getByRole("button", { name: "Restaurar versao" }).click();

  await expect(dialog.getByRole("button", { name: "Salvar alterações e restaurar" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Descartar alterações locais e restaurar" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Cancelar" })).toBeVisible();

  const restoreResponse = page.waitForResponse((response) =>
    response.url().includes(`/api/scenes/${seeded.scene.id}/versions/`) &&
    response.url().endsWith("/restore") &&
    response.request().method() === "POST"
  );
  await dialog.getByRole("button", { name: "Salvar alterações e restaurar" }).click();
  await restoreResponse;

  await expect(editor).toContainText(contentA);
  await expect(editor).not.toContainText(dirtyContent);

  await page.waitForTimeout(AUTOSAVE_DEBOUNCE_BUFFER_MS);
  await expect(editor).toContainText(contentA);
  await expect(editor).not.toContainText(dirtyContent);

  await page.getByRole("button", { name: /Hist/ }).click();
  const reopenedDialog = page.getByRole("dialog");
  await expect(reopenedDialog.getByText(dirtyContent).first()).toBeVisible();
});

test("scene deletion is a manuscript adjustment without reducing productivity", async ({ page }) => {
  test.setTimeout(90_000);

  const suffix = uniqueSuffix();
  const seeded = await createSeededBook(page.request, suffix, { dailyTargetWordCount: 5 });
  const currentSceneContent = "one two three four five";
  const olderSceneContent = "old words removed";

  let currentScene = await updateSceneContent(page.request, seeded.scene.id, currentSceneContent, seeded.scene.contentRevision, "MANUAL_SAVE");
  const olderScene = await createScene(page.request, seeded.chapter.id, `Older scene ${suffix}`, olderSceneContent);

  await openScene(page, seeded.book.id, olderScene.id, seeded.book.title);
  page.once("dialog", (confirmDialog) => {
    void confirmDialog.accept();
  });
  const deleteResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/api/scenes/${olderScene.id}`) && response.request().method() === "DELETE"
  );
  await page.getByRole("button", { name: "Excluir cena" }).click();
  await deleteResponse;
  currentScene = await getScene(page.request, currentScene.id);

  const dashboard = await getDashboard(page.request, seeded.book.id);
  expect(currentScene.wordCount).toBe(5);
  expect(dashboard.totalWordCount).toBe(5);
  expect(dashboard.myWriting.progress.today.productiveWordCountChange).toBe(8);
  expect(dashboard.myWriting.progress.today.manuscriptAdjustmentWordCount).toBe(-3);
  expect(dashboard.myWriting.progress.today.endingManuscriptWordCount).toBe(5);
  expect(dashboard.myWriting.progress.consistency.currentStreakDays).toBeGreaterThanOrEqual(1);
  expect(dashboard.myWriting.progress.recentDays.some((day) => day.productiveWordCountChange < 0)).toBe(false);

  await page.getByRole("button", { name: /Vis/ }).click();
  await expect(page.getByText("Hoje: 8 / 5 palavras")).toBeVisible();
  await expect(page.getByTestId("manuscript-adjustment-summary")).toContainText("-3 palavras");
});

test("older scene history pages remain accessible", async ({ page }) => {
  test.setTimeout(120_000);

  const suffix = uniqueSuffix();
  const seeded = await createSeededBook(page.request, suffix);
  let scene = seeded.scene;

  for (let index = 0; index <= 24; index += 1) {
    scene = await updateSceneContent(
      page.request,
      scene.id,
      `History page marker ${String(index).padStart(2, "0")}`,
      scene.contentRevision,
      "MANUAL_SAVE"
    );
  }

  const firstPage = await listVersions(page.request, scene.id, 0);
  const secondPage = await listVersions(page.request, scene.id, 1);
  expect(firstPage.items.length).toBe(20);
  expect(firstPage.hasNext).toBe(true);
  expect(secondPage.hasNext).toBe(false);

  const olderVersion = secondPage.items.find((item) => item.contentTextPreview.includes("History page marker"));
  expect(olderVersion, "expected an older non-empty history version on page 2").toBeTruthy();
  const olderPreview = olderVersion?.contentTextPreview ?? "";

  await openScene(page, seeded.book.id, scene.id, seeded.book.title);
  await page.getByRole("button", { name: /Hist/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Historico da cena" })).toBeVisible();
  await expect(dialog.getByText(firstPage.items[0].contentTextPreview).first()).toBeVisible();
  await expect(dialog.getByText(olderPreview).first()).toBeHidden();

  await dialog.getByRole("button", { name: /Carregar vers.es anteriores/ }).click();
  await expect(dialog.getByText(olderPreview).first()).toBeVisible();
  await expect(dialog.getByRole("button", { name: /Carregar vers.es anteriores/ })).toBeHidden();
});

async function openScene(page: Page, bookId: string, sceneId: string, bookTitle: string) {
  await page.goto(`/books/${bookId}?sceneId=${sceneId}`);
  await expect(page.getByRole("heading", { name: bookTitle }).first()).toBeVisible();
  await expect(page.getByTestId("scene-content-editor")).toBeVisible();
}

async function saveEditorContent(page: Page, contentText: string) {
  const editor = page.getByTestId("scene-content-editor");
  await editor.fill(contentText);
  await expect(page.getByText("Digitando...").first()).toBeVisible();

  const saveResponse = page.waitForResponse((response) =>
    response.url().includes("/api/scenes/") &&
    response.url().endsWith("/content") &&
    response.request().method() === "PATCH"
  );
  await page.getByRole("button", { name: /Salvar conte.do/ }).click();
  await expect((await saveResponse).ok()).toBeTruthy();
  await expect(page.getByText("Salvo").first()).toBeVisible();
}

async function createSeededBook(
  request: APIRequestContext,
  suffix: string,
  options: { dailyTargetWordCount?: number } = {}
): Promise<SeededBook> {
  const book = await postJson<Book>(request, "/api/books", {
    title: `E2E History ${suffix}`,
    subtitle: null,
    description: null,
    status: "WRITING",
    targetWordCount: 1000,
  });
  // The daily target is the creator's own Personal Book Writing Goal, not a book setting, so it is
  // set through its own contract.
  await patchWritingGoal(request, book.id, {
    // The book was created moments ago and nobody has saved its goal, so this save is decided
    // against the revision a goal that was never saved reads.
    expectedRevision: 0,
    dailyTargetWordCount: options.dailyTargetWordCount ?? null,
    plannedWritingDays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"],
  });
  const section = await postJson<Section>(request, `/api/books/${book.id}/sections`, {
    title: `Part ${suffix}`,
    type: "PART",
    sortOrder: 0,
  });
  const chapter = await postJson<Chapter>(request, `/api/sections/${section.id}/chapters`, {
    title: `Chapter ${suffix}`,
    summary: null,
    sortOrder: 0,
  });
  const scene = await createScene(request, chapter.id, `Scene ${suffix}`, "");

  return { book, section, chapter, scene };
}

async function createScene(request: APIRequestContext, chapterId: string, title: string, contentText: string) {
  return postJson<Scene>(request, `/api/chapters/${chapterId}/scenes`, {
    title,
    summary: null,
    status: "DRAFT",
    sortOrder: 0,
    contentJson: contentJson(contentText),
    contentText,
  });
}

async function getScene(request: APIRequestContext, sceneId: string) {
  return getJson<Scene>(request, `/api/scenes/${sceneId}`);
}

async function updateSceneContent(
  request: APIRequestContext,
  sceneId: string,
  contentText: string,
  expectedContentRevision: number,
  source: "AUTO_SAVE" | "MANUAL_SAVE"
) {
  return patchJson<Scene>(request, `/api/scenes/${sceneId}/content`, {
    contentJson: contentJson(contentText),
    contentText,
    source,
    expectedContentRevision,
    operationId: randomUUID(),
  });
}

async function listVersions(request: APIRequestContext, sceneId: string, page: number) {
  return getJson<SceneVersionPage>(request, `/api/scenes/${sceneId}/versions?page=${page}&size=20`);
}

async function getDashboard(request: APIRequestContext, bookId: string) {
  return getJson<Dashboard>(request, `/api/books/${bookId}/dashboard?progressPeriod=7d`);
}

async function exportMarkdown(request: APIRequestContext, bookId: string) {
  const response = await request.get(`${API_URL}/api/books/${bookId}/export/markdown?includeSceneTitles=true&includeEmptyScenes=false`);
  expect(response.ok()).toBeTruthy();
  return response.text();
}

/** The double-submit contract: the token already in the cookie jar, echoed as a header. */
async function csrfHeaders(request: APIRequestContext) {
  const cookies = (await request.storageState()).cookies;
  return { "X-XSRF-TOKEN": cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "" };
}

async function getJson<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(`${API_URL}${path}`);
  expect(response.ok(), `${response.status()} ${path}: ${await response.text()}`).toBeTruthy();
  return response.json() as Promise<T>;
}

async function postJson<T>(request: APIRequestContext, path: string, data: unknown): Promise<T> {
  const response = await request.post(`${API_URL}${path}`, { data, headers: await csrfHeaders(request) });
  expect(response.ok(), `${response.status()} ${path}: ${await response.text()}`).toBeTruthy();
  return response.json() as Promise<T>;
}

/**
 * Saves the signed-in user's own writing goal.
 *
 * <p>Typed against the real request contract on purpose: `expectedRevision` is required, and a
 * fixture that forgot it would otherwise only fail at runtime, in a suite that does not gate this
 * PR — a setup step failing with 400 long before the scenario it exists to prepare.
 */
async function patchWritingGoal(
  request: APIRequestContext,
  bookId: string,
  goal: UpdatePersonalBookWritingGoalRequest
): Promise<void> {
  await patchJson(request, `/api/books/${bookId}/writing-goal`, goal);
}

async function patchJson<T>(request: APIRequestContext, path: string, data: unknown): Promise<T> {
  const response = await request.patch(`${API_URL}${path}`, { data, headers: await csrfHeaders(request) });
  expect(response.ok(), `${response.status()} ${path}: ${await response.text()}`).toBeTruthy();
  return response.json() as Promise<T>;
}

function contentJson(contentText: string) {
  if (!contentText.trim()) {
    return JSON.stringify({ type: "doc", content: [] });
  }

  return JSON.stringify({
    type: "doc",
    content: [
      {
        type: "paragraph",
        content: [{ type: "text", text: contentText }],
      },
    ],
  });
}

function uniqueSuffix() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}
