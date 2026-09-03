import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { ProfileSettingsForm } from "@/features/profile/components/profile-settings-form";
import { ProfileDraftProvider } from "@/features/profile/profile-draft";
import type { Profile } from "@/features/profile/api/profile-api";

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const profileApi = vi.hoisted(() => ({ fetchProfile: vi.fn(), updateProfile: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/settings" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("@/features/profile/api/profile-api", () => profileApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

const sessionA = {
  user: { displayName: "Ana Autora", email: "ana@iwrite.local" },
  activeWorkspace: { name: "Espaço de Ana", role: "OWNER" },
};

const profileA: Profile = {
  displayName: "Ana Autora",
  email: "ana@iwrite.local",
  timeZone: "America/Sao_Paulo",
  personas: [{ type: "WRITER", primary: true }],
};

const sessionB = {
  user: { displayName: "Bruna Editora", email: "bruna@iwrite.local" },
  activeWorkspace: { name: "Espaço de Bruna", role: "OWNER" },
};

const profileB: Profile = {
  displayName: "Bruna Editora",
  email: "bruna@iwrite.local",
  timeZone: "UTC",
  personas: [{ type: "EDITOR", primary: true }],
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function renderSettings() {
  return render(
    <QueryProvider>
      <ProfileDraftProvider>
        <SessionGuard>
          <ProfileSettingsForm />
        </SessionGuard>
      </ProfileDraftProvider>
    </QueryProvider>,
  );
}

describe("draft de profile durante reconciliação real de sessão", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authApi.fetchSession.mockResolvedValue(sessionA);
    authApi.logout.mockResolvedValue(undefined);
    profileApi.fetchProfile.mockResolvedValue(profileA);
    profileApi.updateProfile.mockResolvedValue(profileA);
  });

  test("reconciliação por foco da mesma conta desmonta a tela e restaura o draft local", async () => {
    renderSettings();
    fireEvent.change(await screen.findByLabelText("Nome de exibição"), {
      target: { value: "Ana ainda editando" },
    });

    let resolveSession!: (session: typeof sessionA) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => { resolveSession = resolve; }),
    );
    act(() => window.dispatchEvent(new Event("focus")));

    await screen.findByText("Verificando sessão…");
    expect(screen.queryByLabelText("Nome de exibição")).not.toBeInTheDocument();

    act(() => resolveSession({ ...sessionA }));

    expect(await screen.findByLabelText("Nome de exibição")).toHaveValue("Ana ainda editando");
    expect(screen.getByLabelText("Email")).toHaveValue("ana@iwrite.local");
  });

  test("PATCH continua bloqueando todos os controles depois de desmontar e remontar a mesma identidade", async () => {
    const pendingUpdate = deferred<Profile>();
    profileApi.updateProfile.mockReturnValueOnce(pendingUpdate.promise);
    renderSettings();

    fireEvent.click(await screen.findByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalledTimes(1));

    let resolveSession!: (session: typeof sessionA) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => { resolveSession = resolve; }),
    );
    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Verificando sessão…");
    act(() => resolveSession({ ...sessionA }));

    expect(await screen.findByLabelText("Nome de exibição")).toBeDisabled();
    expect(screen.getByLabelText("Fuso horário")).toBeDisabled();
    screen.getAllByRole("checkbox").forEach((control) => expect(control).toBeDisabled());
    screen.getAllByRole("radio").forEach((control) => expect(control).toBeDisabled());
    const save = screen.getByRole("button", { name: "Salvando…" });
    expect(save).toBeDisabled();
    fireEvent.click(save);
    expect(profileApi.updateProfile).toHaveBeenCalledTimes(1);

    const savedProfile = { ...profileA, displayName: "Ana salva" };
    profileApi.fetchProfile.mockResolvedValue(savedProfile);
    act(() => pendingUpdate.resolve(savedProfile));

    await waitFor(() => expect(screen.getByLabelText("Nome de exibição")).toBeEnabled());
    expect(screen.getByRole("button", { name: "Salvar alterações" })).toBeEnabled();
  });

  test("falha do PATCH depois do remount limpa pending e preserva o draft da identidade", async () => {
    const pendingUpdate = deferred<Profile>();
    profileApi.updateProfile.mockReturnValueOnce(pendingUpdate.promise);
    renderSettings();

    fireEvent.change(await screen.findByLabelText("Nome de exibição"), {
      target: { value: "Ana ainda não salva" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalledTimes(1));

    let resolveSession!: (session: typeof sessionA) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => { resolveSession = resolve; }),
    );
    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Verificando sessão…");
    act(() => resolveSession({ ...sessionA }));
    expect(await screen.findByLabelText("Nome de exibição")).toBeDisabled();

    act(() => pendingUpdate.reject(new Error("Falha tardia")));

    await waitFor(() => expect(screen.getByLabelText("Nome de exibição")).toBeEnabled());
    expect(screen.getByLabelText("Nome de exibição")).toHaveValue("Ana ainda não salva");
    expect(screen.getByRole("button", { name: "Salvar alterações" })).toBeEnabled();
  });

  test("PATCH pendente de A não bloqueia B e seu resultado stale não altera o perfil de B", async () => {
    const pendingUpdate = deferred<Profile>();
    profileApi.updateProfile.mockReturnValueOnce(pendingUpdate.promise);
    renderSettings();

    fireEvent.click(await screen.findByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalledTimes(1));

    authApi.fetchSession.mockResolvedValueOnce(sessionB);
    profileApi.fetchProfile.mockResolvedValue(profileB);
    act(() => window.dispatchEvent(new Event("focus")));

    expect(await screen.findByLabelText("Email")).toHaveValue("bruna@iwrite.local");
    expect(screen.getByLabelText("Nome de exibição")).toHaveValue("Bruna Editora");
    expect(screen.getByLabelText("Nome de exibição")).toBeEnabled();
    expect(screen.getByRole("button", { name: "Salvar alterações" })).toBeEnabled();

    act(() => pendingUpdate.resolve({ ...profileA, displayName: "Ana atrasada" }));

    await waitFor(() => expect(profileApi.fetchProfile.mock.calls.length).toBeGreaterThanOrEqual(3));
    expect(screen.getByLabelText("Email")).toHaveValue("bruna@iwrite.local");
    expect(screen.getByLabelText("Nome de exibição")).toHaveValue("Bruna Editora");
    expect(screen.getByRole("button", { name: "Salvar alterações" })).toBeEnabled();
  });
});
