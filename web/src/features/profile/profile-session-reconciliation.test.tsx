import { act, fireEvent, render, screen } from "@testing-library/react";
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
});
