import { useQuery } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { renderWithClient } from "@/test/test-utils";
import { ApiError } from "@/lib/api/client";

/** Stands in for any authenticated screen whose data call is the one that finds the session gone. */
function BooksThatFailWith401() {
  useQuery({
    queryKey: ["books"],
    queryFn: () => Promise.reject(new ApiError("Sua sessão expirou.", 401)),
    retry: false,
  });
  return <p>Conteúdo protegido</p>;
}

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

const session = {
  user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
};

describe("SessionGuard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    navigation.pathname = "/library";
    authApi.logout.mockResolvedValue(undefined);
  });

  test("restaura a sessão e mostra usuário e workspace resolvidos pelo backend", async () => {
    authApi.fetchSession.mockResolvedValue(session);

    renderWithClient(
      <SessionGuard>
        <p>Conteúdo protegido</p>
      </SessionGuard>,
    );

    expect(await screen.findByText("Conteúdo protegido")).toBeInTheDocument();
    expect(screen.getByText("Autor A")).toBeInTheDocument();
    expect(screen.getByText("Espaço do Autor A")).toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("não revela o workspace durante a validação inicial de sessão, mesmo com filhos prontos", async () => {
    let resolveSession!: (value: typeof session) => void;
    authApi.fetchSession.mockImplementation(() => new Promise<typeof session>((resolve) => {
      resolveSession = resolve;
    }));

    renderWithClient(
      <SessionGuard>
        <p>Manuscrito privado da sessão anterior</p>
      </SessionGuard>,
    );

    // This full-page state is intentional for security and must not be replaced by a
    // workspace skeleton that could accidentally show previously authenticated content.
    expect(screen.queryByText("Manuscrito privado da sessão anterior")).not.toBeInTheDocument();
    expect(screen.queryByText("Autor A")).not.toBeInTheDocument();
    expect(screen.getByText("Carregando…")).toBeInTheDocument();

    await act(async () => resolveSession(session));
    expect(await screen.findByText("Manuscrito privado da sessão anterior")).toBeInTheDocument();
  });

  test("consulta a sessão uma única vez para toda a aplicação", async () => {
    authApi.fetchSession.mockResolvedValue(session);

    renderWithClient(
      <SessionGuard>
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>
      </SessionGuard>,
    );

    await screen.findByText("Conteúdo protegido");
    expect(authApi.fetchSession).toHaveBeenCalledTimes(1);
  });

  test("rota protegida sem sessão vai para o login, sem mencionar expiração", async () => {
    authApi.fetchSession.mockResolvedValue(null);

    renderWithClient(
      <SessionGuard>
        <p>Conteúdo protegido</p>
      </SessionGuard>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("Conteúdo protegido")).not.toBeInTheDocument();
  });

  test("sessão perdida durante o uso redireciona anunciando expiração", async () => {
    authApi.fetchSession.mockResolvedValue(session);

    const { queryClient } = renderWithClient(
      <SessionGuard>
        <p>Conteúdo protegido</p>
      </SessionGuard>,
    );
    await screen.findByText("Conteúdo protegido");

    // What the global 401 handler does when any request finds the session gone.
    queryClient.setQueryData(["auth", "session"], null);

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
  });

  test("um 401 em qualquer requisição encerra a sessão e leva ao login", async () => {
    authApi.fetchSession.mockResolvedValue(session);

    // The real provider, because the 401 handler that closes the session lives on its caches.
    render(
      <QueryProvider>
        <SessionGuard>
          <BooksThatFailWith401 />
        </SessionGuard>
      </QueryProvider>,
    );
    await screen.findByText("Conteúdo protegido");

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
  });

  test("não redireciona em loop quando já está no login", async () => {
    navigation.pathname = "/login";
    authApi.fetchSession.mockResolvedValue(null);

    renderWithClient(
      <SessionGuard>
        <p>Formulário de login</p>
      </SessionGuard>,
    );

    // The login screen renders regardless of the session, and must not bounce anywhere.
    expect(await screen.findByText("Formulário de login")).toBeInTheDocument();
    await waitFor(() => expect(authApi.fetchSession).toHaveBeenCalled());
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("quem já tem sessão é levado do login para a biblioteca, uma vez só", async () => {
    navigation.pathname = "/login";
    authApi.fetchSession.mockResolvedValue(session);

    renderWithClient(
      <SessionGuard>
        <p>Formulário de login</p>
      </SessionGuard>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/library"));
    expect(navigation.replace).toHaveBeenCalledTimes(1);
  });

  test("não redireciona em loop quando já está no cadastro", async () => {
    navigation.pathname = "/register";
    authApi.fetchSession.mockResolvedValue(null);

    renderWithClient(
      <SessionGuard>
        <p>Formulário de cadastro</p>
      </SessionGuard>,
    );

    expect(await screen.findByText("Formulário de cadastro")).toBeInTheDocument();
    await waitFor(() => expect(authApi.fetchSession).toHaveBeenCalled());
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("quem já tem sessão não permanece em /register", async () => {
    navigation.pathname = "/register";
    authApi.fetchSession.mockResolvedValue(session);

    renderWithClient(
      <SessionGuard>
        <p>Formulário de cadastro</p>
      </SessionGuard>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/library"));
    expect(navigation.replace).toHaveBeenCalledTimes(1);
  });

  test("backend fora do ar não é tratado como falta de sessão", async () => {
    authApi.fetchSession.mockRejectedValue(new TypeError("Failed to fetch"));

    renderWithClient(
      <SessionGuard>
        <p>Conteúdo protegido</p>
      </SessionGuard>,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.",
    );
    // Bouncing to the login screen would suggest the credentials were the problem.
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  describe("recuperação de uma falha transitória", () => {
    const retryButton = () => screen.getByRole("button", { name: "Tentar novamente" });

    test("um 503 na primeira chamada é recuperado pelo retry manual", async () => {
      authApi.fetchSession
        .mockRejectedValueOnce(new ApiError("Serviço indisponível", 503))
        .mockResolvedValueOnce(session);

      renderWithClient(
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>,
      );

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.",
      );

      fireEvent.click(retryButton());

      // Backend de volta: a sessão é restaurada sem nenhum reload manual.
      expect(await screen.findByText("Conteúdo protegido")).toBeInTheDocument();
      expect(screen.getByText("Autor A")).toBeInTheDocument();
      expect(navigation.replace).not.toHaveBeenCalled();
    });

    test("uma falha de rede na primeira chamada também é recuperada pelo retry manual", async () => {
      authApi.fetchSession
        .mockRejectedValueOnce(new TypeError("Failed to fetch"))
        .mockResolvedValueOnce(session);

      renderWithClient(
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>,
      );
      await screen.findByRole("alert");

      fireEvent.click(retryButton());

      expect(await screen.findByText("Conteúdo protegido")).toBeInTheDocument();
    });

    test("retry que responde 401 leva ao login em vez de insistir", async () => {
      // fetchSession resolve 401 como ausência de sessão, e é assim que o retry a enxerga.
      authApi.fetchSession.mockRejectedValueOnce(new ApiError("Serviço indisponível", 503)).mockResolvedValueOnce(null);

      renderWithClient(
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>,
      );
      await screen.findByRole("alert");

      fireEvent.click(retryButton());

      await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login"));
      expect(screen.queryByText("Conteúdo protegido")).not.toBeInTheDocument();
    });

    test("cliques repetidos durante a nova tentativa não disparam requisições duplicadas", async () => {
      let resolveRetry: (value: typeof session) => void = () => {};
      authApi.fetchSession
        .mockRejectedValueOnce(new ApiError("Serviço indisponível", 503))
        .mockImplementationOnce(() => new Promise((resolve) => (resolveRetry = resolve)));

      renderWithClient(
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>,
      );
      await screen.findByRole("alert");

      fireEvent.click(retryButton());

      // Enquanto a tentativa está no ar o botão fica desabilitado e anuncia o carregamento.
      const retrying = await screen.findByRole("button", { name: "Tentando…" });
      expect(retrying).toBeDisabled();

      fireEvent.click(retrying);
      fireEvent.click(retrying);
      expect(authApi.fetchSession).toHaveBeenCalledTimes(2); // a inicial e uma única nova tentativa

      resolveRetry(session);
      expect(await screen.findByText("Conteúdo protegido")).toBeInTheDocument();
      expect(authApi.fetchSession).toHaveBeenCalledTimes(2);
    });

    test("mensagem e botão são acessíveis por leitor de tela e por teclado", async () => {
      authApi.fetchSession.mockRejectedValue(new ApiError("Serviço indisponível", 503));

      renderWithClient(
        <SessionGuard>
          <p>Conteúdo protegido</p>
        </SessionGuard>,
      );

      // role="alert": anunciado sozinho, sem depender de o leitor estar sobre o elemento.
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.",
      );

      // <button> nativo: entra na ordem de tabulação e é acionado por Enter/Espaço, que o jsdom
      // entrega como o mesmo evento de clique verificado aqui.
      const button = retryButton();
      expect(button.tagName).toBe("BUTTON");
      expect(button).toBeEnabled();
      button.focus();
      expect(button).toHaveFocus();

      fireEvent.click(button);
      await waitFor(() => expect(authApi.fetchSession).toHaveBeenCalledTimes(2));
    });
  });

  test("logout encerra a sessão, limpa o cache e volta ao login", async () => {
    authApi.fetchSession.mockResolvedValue(session);

    const { queryClient } = renderWithClient(
      <SessionGuard>
        <p>Conteúdo protegido</p>
      </SessionGuard>,
    );
    await screen.findByText("Conteúdo protegido");

    queryClient.setQueryData(["books"], [{ id: "book-1", title: "Livro do Autor A" }]);

    fireEvent.click(screen.getByRole("button", { name: "Sair" }));

    await waitFor(() => expect(authApi.logout).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login"));
    // Nothing fetched as the previous user may survive the logout.
    expect(queryClient.getQueryData(["books"])).toBeUndefined();
    expect(queryClient.getQueryData(["auth", "session"])).toBeUndefined();
  });
});
