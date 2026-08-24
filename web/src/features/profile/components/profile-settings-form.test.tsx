import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ProfileSettingsForm } from "@/features/profile/components/profile-settings-form";
import { useSession } from "@/features/auth/session";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";
import { markReconciliationStart } from "@/features/auth/session-cache";
import type { AuthenticatedSession } from "@/features/auth/api/auth-api";
import type { Profile } from "@/features/profile/api/profile-api";
import { ProfileDraftProvider } from "@/features/profile/profile-draft";
import { queryKeys } from "@/lib/query/keys";

const profileApi = vi.hoisted(() => ({
  fetchProfile: vi.fn(),
  updateProfile: vi.fn(),
}));

const authApi = vi.hoisted(() => ({
  fetchSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
}));

vi.mock("@/features/profile/api/profile-api", () => profileApi);
vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const currentProfile: Profile = {
  displayName: "Ana Autora",
  email: "ana@iwrite.local",
  timeZone: "America/Sao_Paulo",
  personas: [
    { type: "WRITER", primary: true },
    { type: "REVIEWER", primary: false },
  ],
};

const session: AuthenticatedSession = {
  user: { displayName: "Ana Autora", email: "ana@iwrite.local" },
  activeWorkspace: { name: "Espaço de Ana", role: "OWNER" },
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

function SessionName() {
  const { data } = useSession();
  return <span data-testid="session-name">{data?.user.displayName}</span>;
}

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(SESSION_QUERY_KEY, session);

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ProfileDraftProvider>{children}</ProfileDraftProvider>
      </QueryClientProvider>
    );
  }

  return {
    queryClient,
    ...render(
      <>
        <SessionName />
        <ProfileSettingsForm />
      </>,
      { wrapper: Wrapper },
    ),
  };
}

describe("configurações de dados pessoais", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    profileApi.fetchProfile.mockResolvedValue(currentProfile);
    profileApi.updateProfile.mockResolvedValue(currentProfile);
  });

  test("mostra loading inicial e preenche todos os dados atuais", async () => {
    let resolveProfile: ((profile: Profile) => void) | undefined;
    profileApi.fetchProfile.mockReturnValueOnce(new Promise<Profile>((resolve) => { resolveProfile = resolve; }));

    renderForm();
    expect(screen.getByRole("status")).toHaveTextContent("Carregando perfil");

    resolveProfile?.(currentProfile);

    expect(await screen.findByLabelText("Nome de exibição")).toHaveValue("Ana Autora");
    expect(screen.getByLabelText("Email")).toHaveValue("ana@iwrite.local");
    expect(screen.getByLabelText("Email")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("Fuso horário")).toHaveValue("America/Sao_Paulo");
    expect(screen.getByRole("checkbox", { name: "Escritor(a)" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Revisor(a)" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Escritor(a) como principal" })).toBeChecked();
  });

  test("edita nome, timezone, múltiplas personas e a principal em um único envio", async () => {
    const updated: Profile = {
      ...currentProfile,
      displayName: "Ana Editora",
      timeZone: "America/Fortaleza",
      personas: [
        { type: "WRITER", primary: false },
        { type: "EDITOR", primary: true },
        { type: "REVIEWER", primary: false },
      ],
    };
    profileApi.updateProfile.mockResolvedValueOnce(updated);
    renderForm();

    fireEvent.change(await screen.findByLabelText("Nome de exibição"), { target: { value: "Ana Editora" } });
    fireEvent.change(screen.getByLabelText("Fuso horário"), { target: { value: "America/Fortaleza" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Editor(a)" }));
    fireEvent.click(screen.getByRole("radio", { name: "Editor(a) como principal" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));

    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalledWith(
      {
        displayName: "Ana Editora",
        timeZone: "America/Fortaleza",
        personas: ["WRITER", "EDITOR", "REVIEWER"],
        primaryPersona: "EDITOR",
      },
      expect.any(Object),
    ));
    expect(await screen.findByText("Perfil atualizado com sucesso.")).toBeInTheDocument();
  });

  test("impede remover a última persona", async () => {
    profileApi.fetchProfile.mockResolvedValueOnce({
      ...currentProfile,
      personas: [{ type: "WRITER", primary: true }],
    });
    renderForm();

    const writer = await screen.findByRole("checkbox", { name: "Escritor(a)" });
    expect(writer).toBeDisabled();
    fireEvent.click(writer);
    expect(writer).toBeChecked();
  });

  test("preserva o estado editado quando a API falha e move foco para o erro", async () => {
    profileApi.updateProfile.mockRejectedValueOnce(new Error("Falha ao salvar o perfil."));
    renderForm();

    const name = await screen.findByLabelText("Nome de exibição");
    fireEvent.change(name, { target: { value: "Nome ainda não salvo" } });
    fireEvent.change(screen.getByLabelText("Fuso horário"), { target: { value: "America/Fortaleza" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Editor(a)" }));
    fireEvent.click(screen.getByRole("radio", { name: "Editor(a) como principal" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Falha ao salvar o perfil.");
    expect(alert).toHaveFocus();
    expect(name).toHaveValue("Nome ainda não salvo");
    expect(screen.getByLabelText("Fuso horário")).toHaveValue("America/Fortaleza");
    expect(screen.getByRole("checkbox", { name: "Editor(a)" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Editor(a) como principal" })).toBeChecked();
  });

  test("mantém todos os controles editáveis bloqueados enquanto o PATCH está pendente", async () => {
    const pendingUpdate = deferred<Profile>();
    profileApi.updateProfile.mockReturnValueOnce(pendingUpdate.promise);
    renderForm();

    fireEvent.click(await screen.findByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalled());

    expect(screen.getByLabelText("Nome de exibição")).toBeDisabled();
    expect(screen.getByLabelText("Fuso horário")).toBeDisabled();
    screen.getAllByRole("checkbox").forEach((checkbox) => expect(checkbox).toBeDisabled());
    screen.getAllByRole("radio").forEach((radio) => expect(radio).toBeDisabled());
    expect(screen.getByRole("button", { name: "Salvando…" })).toBeDisabled();

    pendingUpdate.resolve(currentProfile);
    expect(await screen.findByText("Perfil atualizado com sucesso.")).toBeInTheDocument();
  });

  test("ignora sucesso de mutation da conta A depois que a reconciliação avançou para B", async () => {
    const pendingUpdate = deferred<Profile>();
    profileApi.updateProfile.mockReturnValueOnce(pendingUpdate.promise);
    const { queryClient } = renderForm();

    fireEvent.change(await screen.findByLabelText("Nome de exibição"), { target: { value: "Ana atrasada" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(profileApi.updateProfile).toHaveBeenCalled());

    const profileB: Profile = {
      displayName: "Bruna B",
      email: "bruna@iwrite.local",
      timeZone: "UTC",
      personas: [{ type: "EDITOR", primary: true }],
    };
    const sessionB: AuthenticatedSession = {
      user: { displayName: "Bruna B", email: "bruna@iwrite.local" },
      activeWorkspace: { name: "Espaço de Bruna", role: "OWNER" },
    };
    act(() => {
      markReconciliationStart(queryClient);
      queryClient.setQueryData(SESSION_QUERY_KEY, sessionB);
      queryClient.setQueryData(queryKeys.profile, profileB);
    });

    pendingUpdate.resolve({ ...currentProfile, displayName: "Ana atrasada" });

    // Prove the mutation lifecycle reached onSuccess before inspecting the caches. Without this
    // await, the assertions could run while the deferred Promise was resolved but its callbacks had
    // not executed yet, allowing a stale write to escape the test.
    expect(await screen.findByText("Perfil atualizado com sucesso.")).toBeInTheDocument();
    expect(queryClient.getQueryData(SESSION_QUERY_KEY)).toEqual(sessionB);
    expect(queryClient.getQueryData(queryKeys.profile)).toEqual(profileB);
  });

  test("draft sobrevive à desmontagem protegida de uma reconciliação por foco da mesma conta", async () => {
    const rendered = renderForm();
    fireEvent.change(await screen.findByLabelText("Nome de exibição"), { target: { value: "Ana em edição" } });
    fireEvent.change(screen.getByLabelText("Fuso horário"), { target: { value: "America/Fortaleza" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Editor(a)" }));

    rendered.rerender(<div role="status">Verificando sessão…</div>);
    expect(screen.queryByLabelText("Nome de exibição")).not.toBeInTheDocument();
    rendered.rerender(<ProfileSettingsForm />);

    expect(await screen.findByLabelText("Nome de exibição")).toHaveValue("Ana em edição");
    expect(screen.getByLabelText("Fuso horário")).toHaveValue("America/Fortaleza");
    expect(screen.getByRole("checkbox", { name: "Editor(a)" })).toBeChecked();
  });

  test("troca real de A para B nunca reaproveita o draft de A", async () => {
    const rendered = renderForm();
    fireEvent.change(await screen.findByLabelText("Nome de exibição"), { target: { value: "Draft secreto de Ana" } });

    rendered.rerender(<div role="status">Verificando sessão…</div>);
    const profileB: Profile = {
      displayName: "Bruna B",
      email: "bruna@iwrite.local",
      timeZone: "UTC",
      personas: [{ type: "EDITOR", primary: true }],
    };
    act(() => rendered.queryClient.setQueryData(queryKeys.profile, profileB));
    rendered.rerender(<ProfileSettingsForm />);

    expect(await screen.findByLabelText("Nome de exibição")).toHaveValue("Bruna B");
    expect(screen.getByLabelText("Email")).toHaveValue("bruna@iwrite.local");
    expect(screen.getByLabelText("Nome de exibição")).not.toHaveValue("Draft secreto de Ana");
  });

  test("atualiza o nome exibido pela sessão imediatamente após salvar", async () => {
    profileApi.updateProfile.mockResolvedValueOnce({ ...currentProfile, displayName: "Ana Renovada" });
    renderForm();

    fireEvent.change(await screen.findByLabelText("Nome de exibição"), { target: { value: "Ana Renovada" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));

    await waitFor(() => expect(screen.getByTestId("session-name")).toHaveTextContent("Ana Renovada"));
  });

  test("explica que personas não concedem permissão e usa controles nativos rotulados", async () => {
    renderForm();
    const form = (await screen.findByRole("button", { name: "Salvar alterações" })).closest("form")!;

    expect(within(form).getByText(/personas descrevem como você usa o IWrite/i)).toBeInTheDocument();
    expect(within(form).getByText(/não concedem acesso a livros/i)).toBeInTheDocument();
    expect(within(form).getByRole("group", { name: "Personas" })).toBeInTheDocument();
    expect(within(form).getByRole("radiogroup", { name: "Persona principal" })).toBeInTheDocument();
  });

  test("mostra falha de carregamento com ação de tentar novamente", async () => {
    profileApi.fetchProfile.mockRejectedValueOnce(new Error("offline"));
    renderForm();

    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível carregar seu perfil.");
    expect(screen.getByRole("button", { name: "Tentar novamente" })).toBeInTheDocument();
  });
});
