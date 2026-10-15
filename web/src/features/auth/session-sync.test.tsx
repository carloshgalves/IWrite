import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { isStaleMutation } from "@/features/auth/session-cache";
import { announceSessionChanged } from "@/features/auth/session-sync";

const SYNC_KEY = "iwrite-session-sync";

/** What a real other tab's storage-fallback announcement looks like on the wire: `<its
 *  TAB_ID>:<nonce>`. `tabId` stands in for a foreign tab's id — this test process never has two of
 *  those for real, so the emitter identity is just a fixed label distinct from anything this tab's
 *  own announceSessionChanged() could produce. */
function dispatchForeignStorageAnnouncement(tabId: string, nonce: string) {
  const value = `${tabId}:${nonce}`;
  act(() => {
    window.localStorage.setItem(SYNC_KEY, value);
    window.dispatchEvent(new StorageEvent("storage", { key: SYNC_KEY, newValue: value }));
  });
}

/**
 * Stands in for a genuinely separate tab's announceSessionChanged(): same channel/storage key, but
 * a token unrelated to this test process's own. Calling the real announceSessionChanged() here
 * would not do, precisely because it shares this module's TAB_ID with the tab under test — the
 * self-filtering that correctly stops a tab from reacting to its own login/logout would swallow it.
 */
function simulateOtherTabAnnouncement() {
  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel(SYNC_KEY);
    channel.postMessage("outra-aba");
    channel.close();
  } else {
    window.localStorage.setItem(SYNC_KEY, "outra-aba");
  }
}

/**
 * Covers thread #139-review-3's finding: cookies and the HttpSession are shared across same-origin
 * tabs, but each tab keeps its own QueryClient — so a login or logout in one tab left every other
 * tab showing the previous identity's cache until it happened to refetch something and hit a 401.
 * These tests drive a single rendered tab (the one under test) and simulate "another tab" the way
 * a real one would be observed from here: an opaque announceSessionChanged() broadcast (or, for the
 * fallback test, the `storage` event it degrades to) plus whatever /api/auth/me now answers -
 * exactly what a receiving tab has to work with. web/e2e/cross-tab-session-sync.e2e.ts drives two
 * real pages end to end for the same scenario.
 */

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const booksApi = vi.hoisted(() => ({ fetchBooks: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

const sessionA = {
  user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
};
const sessionB = {
  user: { displayName: "Autor B", email: "autor-b@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor B", role: "OWNER" },
};

/** Stands in for the library screen: one query, keyed exactly like the real one, scoped server-side. */
function Library() {
  const { data } = useQuery({ queryKey: ["books"], queryFn: () => booksApi.fetchBooks() });
  return <ul>{(data ?? []).map((title) => <li key={title}>{title}</li>)}</ul>;
}

/** A write in flight when reconciliation lands - e.g. saving a scene - resolved by the test whenever
 *  it chooses, so the race ("does its result repopulate the cache after the swap?") is observable. */
function DelayedMutation({
  resolveRef,
  onLocalSuccess,
}: {
  resolveRef: { current: (() => void) | null };
  onLocalSuccess?: () => void;
}) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      new Promise<void>((resolve) => {
        resolveRef.current = resolve;
      }),
    onSuccess: () => {
      queryClient.setQueryData(["books"], ["Rascunho não salvo do Autor A"]);
      onLocalSuccess?.();
    },
  });
  useEffect(() => {
    if (resolveRef.current) return;
    mutation.mutate();
    // Fired once, right as this tab renders - before any reconciliation has a chance to run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return null;
}

function App({ extra }: { extra?: React.ReactNode }) {
  return (
    <SessionGuard>
      {navigation.pathname === "/login" ? <p>Formulário de login</p> : <Library />}
      {extra}
    </SessionGuard>
  );
}

function ClientProbe({ onClient }: { onClient: (client: QueryClient) => void }) {
  const client = useQueryClient();
  useEffect(() => {
    onClient(client);
  }, [client, onClient]);
  return null;
}

function renderTab(extra?: React.ReactNode) {
  let client!: QueryClient;
  const utils = render(
    <QueryProvider>
      <ClientProbe onClient={(c) => (client = c)} />
      <App extra={extra} />
    </QueryProvider>,
  );
  return { ...utils, getClient: () => client };
}

describe("sincronização de sessão entre abas", () => {
  let originalBroadcastChannel: typeof BroadcastChannel | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    navigation.pathname = "/library";
    authApi.fetchSession.mockResolvedValue(sessionA);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor A"]);
    originalBroadcastChannel = window.BroadcastChannel;
  });

  afterEach(() => {
    if (originalBroadcastChannel) {
      vi.stubGlobal("BroadcastChannel", originalBroadcastChannel);
    }
  });

  test("1. outra aba faz logout: este tab recebe o evento, perde os dados de A e volta ao login", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);
    act(() => simulateOtherTabAnnouncement());

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(getClient().getQueryData(["auth", "session"])).toBeNull();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("2. outra aba entra como B: o cache é limpo antes da revalidação, e só B aparece — nunca A transitoriamente", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    let resolveFetchSession!: (value: typeof sessionB) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => (resolveFetchSession = resolve)),
    );
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);

    act(() => simulateOtherTabAnnouncement());

    // Mid-flight: the broadcast is opaque, so the cache is purged before /api/auth/me even answers.
    await screen.findByText("Verificando sessão…");
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    resolveFetchSession(sessionB);

    await screen.findByText("Livro do Autor B");
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalledWith(expect.stringContaining("/login"));
  });

  test("3. evento perdido: o foco revalida mesmo quando A e B compartilham o mesmo nome de workspace, e o cache de A é apagado", async () => {
    // Fresh evidence after the cross-tab fix: AuthenticatedSession carries no userId/tenantId, so
    // email + workspace name was the only thing focus reconciliation ever had to compare — and
    // workspace names are not unique. The same person moved to a different tenant that happens to
    // share the old tenant's display name must never be mistaken for "nothing changed".
    const sessionOtherTenantSameWorkspaceName = {
      user: sessionA.user,
      activeWorkspace: { ...sessionA.activeWorkspace }, // identical email AND workspace name on purpose
    };
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    let resolveFetchSession!: (value: typeof sessionOtherTenantSameWorkspaceName) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => (resolveFetchSession = resolve)),
    );
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do outro tenant"]);

    act(() => window.dispatchEvent(new Event("focus")));

    // Purged before /api/auth/me even answers — there is no identity comparison left to fool.
    await screen.findByText("Verificando sessão…");
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    resolveFetchSession(sessionOtherTenantSameWorkspaceName);

    await screen.findByText("Livro do outro tenant");
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("4. sessão e tenant inalterados: o foco ainda purga conservadoramente e recarrega as queries ativas", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    let resolveFetchSession!: (value: typeof sessionA) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => (resolveFetchSession = resolve)),
    );
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do Autor A"]);

    act(() => window.dispatchEvent(new Event("focus")));

    // Blocked while in flight, exactly like any other reconciliation — never a cheaper "nothing
    // changed" path, since there is nothing safe left to compare that could tell it apart.
    await screen.findByText("Verificando sessão…");
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    resolveFetchSession({ ...sessionA });

    await screen.findByText("Livro do Autor A");
    // Refetched, not merely preserved from before the focus event: fetchBooks ran a second time
    // even though nothing about the session actually changed.
    expect(booksApi.fetchBooks).toHaveBeenCalledTimes(2);
  });

  test("foco: mudança apenas de role no mesmo tenant não preserva o cache antigo", async () => {
    const sessionACollaborator = {
      user: sessionA.user,
      activeWorkspace: { ...sessionA.activeWorkspace, role: "COLLABORATOR" },
    };
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValueOnce(sessionACollaborator);
    booksApi.fetchBooks.mockResolvedValueOnce(["Catálogo reduzido de colaborador"]);

    act(() => window.dispatchEvent(new Event("focus")));

    await screen.findByText("Catálogo reduzido de colaborador");
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("foco: query antiga em voo não repopula o cache após a reconciliação", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    // A slow-resolving "books" fetch already in flight — e.g. a background refetch someone else
    // triggered — right as the focus reconciliation itself begins.
    let resolveStaleBooks!: (value: string[]) => void;
    booksApi.fetchBooks.mockImplementationOnce(() => new Promise((resolve) => (resolveStaleBooks = resolve)));
    act(() => {
      void getClient().refetchQueries({ queryKey: ["books"] });
    });

    authApi.fetchSession.mockResolvedValueOnce(sessionB);
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do Autor B"]);
    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Livro do Autor B");

    // The slow fetch from before the swap finally resolves — too late, and must never land.
    act(() => resolveStaleBooks(["Livro obsoleto do Autor A"]));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByText("Livro obsoleto do Autor A")).not.toBeInTheDocument();
    expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor B"]);
  });

  test("foco: mutation stale concluída depois da reconciliação purga e recarrega as queries ativas de B", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const onLocalSuccess = vi.fn();
    const { getClient } = renderTab(
      <DelayedMutation resolveRef={resolveRef} onLocalSuccess={onLocalSuccess} />,
    );
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());
    const mutationA = getClient().getMutationCache().getAll()[0];

    authApi.fetchSession.mockResolvedValueOnce(sessionB);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);
    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Livro do Autor B");
    expect(isStaleMutation(getClient(), mutationA)).toBe(true);

    // Only now does A's stale write land — after B's identity has already been accepted.
    act(() => resolveRef.current!());

    await waitFor(() => expect(onLocalSuccess).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(booksApi.fetchBooks).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor B"]));
    expect(getClient().getQueryData(["auth", "session"])).toEqual(sessionB);
    expect(screen.getByText("Livro do Autor B")).toBeInTheDocument();
    expect(screen.queryByText("Rascunho não salvo do Autor A")).not.toBeInTheDocument();
  });

  test("foco: mutation stale concluída durante a reconciliação converge com um único refetch para B", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const onLocalSuccess = vi.fn();
    const { getClient } = renderTab(
      <DelayedMutation resolveRef={resolveRef} onLocalSuccess={onLocalSuccess} />,
    );
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());

    let resolveFetchSession!: (value: typeof sessionB) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => (resolveFetchSession = resolve)),
    );
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do Autor B"]);
    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Verificando sessão…");

    act(() => resolveRef.current!());
    await waitFor(() => expect(onLocalSuccess).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(getClient().getQueryData(["books"])).toBeUndefined());

    act(() => resolveFetchSession(sessionB));
    expect(await screen.findByText("Livro do Autor B")).toBeInTheDocument();
    expect(booksApi.fetchBooks).toHaveBeenCalledTimes(2);
    expect(getClient().getQueryData(["auth", "session"])).toEqual(sessionB);
    expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor B"]);
    expect(screen.queryByText("Rascunho não salvo do Autor A")).not.toBeInTheDocument();
  });

  test("logout: mutation stale concluída após sessão null não refaz queries de domínio", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const onLocalSuccess = vi.fn();
    const { getClient } = renderTab(
      <DelayedMutation resolveRef={resolveRef} onLocalSuccess={onLocalSuccess} />,
    );
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());

    authApi.fetchSession.mockResolvedValueOnce(null);
    act(() => window.dispatchEvent(new Event("focus")));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    const booksCallsAfterLogout = booksApi.fetchBooks.mock.calls.length;

    act(() => resolveRef.current!());
    await waitFor(() => expect(onLocalSuccess).toHaveBeenCalledTimes(1));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(booksApi.fetchBooks).toHaveBeenCalledTimes(booksCallsAfterLogout);
    expect(getClient().getQueryData(["auth", "session"])).toBeNull();
    expect(getClient().getQueryData(["books"])).toBeUndefined();
  });

  test("foco: focus e visibilitychange disparados juntos produzem uma única reconciliação efetiva", async () => {
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    const callsBefore = authApi.fetchSession.mock.calls.length;

    authApi.fetchSession.mockResolvedValueOnce({ ...sessionA });
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do Autor A"]);

    act(() => {
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange"));
    });

    await waitFor(() => expect(authApi.fetchSession.mock.calls.length).toBe(callsBefore + 1));
    // Gives an accidental second trigger time to have fired (past the dedupe window), then confirms
    // it never did.
    await new Promise((resolve) => setTimeout(resolve, 350));
    expect(authApi.fetchSession.mock.calls.length).toBe(callsBefore + 1);
  });

  test("foco: 401 durante a reconciliação mantém o cache vazio, redireciona, e não refaz nenhuma query de domínio", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    const booksCallsBefore = booksApi.fetchBooks.mock.calls.length;

    authApi.fetchSession.mockResolvedValueOnce(null);
    act(() => window.dispatchEvent(new Event("focus")));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(getClient().getQueryData(["auth", "session"])).toBeNull();
    // refetchActiveDomainQueries only ever runs for a session worth keeping — none was attempted.
    expect(booksApi.fetchBooks).toHaveBeenCalledTimes(booksCallsBefore);
  });

  test("5. mutation atrasada de A não repopula o cache depois da troca para B", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const { getClient } = renderTab(<DelayedMutation resolveRef={resolveRef} />);
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());

    authApi.fetchSession.mockResolvedValue(sessionB);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);
    act(() => simulateOtherTabAnnouncement());
    await screen.findByText("Livro do Autor B");

    // Only now does A's stale write land - after B's identity has already been accepted.
    act(() => resolveRef.current!());

    await waitFor(() => expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor B"]));
    expect(screen.queryByText("Rascunho não salvo do Autor A")).not.toBeInTheDocument();
  });

  test("6. 401 durante a reconciliação limpa o cache, redireciona ao login e não entra em loop", async () => {
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);
    act(() => simulateOtherTabAnnouncement());

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(navigation.replace).toHaveBeenCalledTimes(1);

    const callsAfterSettling = authApi.fetchSession.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 50));
    // No polling and no automatic retry: nothing re-invokes /api/auth/me on its own once the tab has
    // settled on "no session".
    expect(authApi.fetchSession.mock.calls.length).toBe(callsAfterSettling);
  });

  test("7. sem BroadcastChannel, o fallback por storage event ainda reconcilia a sessão", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);

    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);

    // `storage` events never fire in the writing document, so this is dispatched by hand to stand in
    // for the event this tab would receive from a genuinely separate one — with a foreign token,
    // proving the listener does not merely react to any storage write.
    dispatchForeignStorageAnnouncement("outra-aba", "nonce-1");

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("8a. announceSessionChanged() sem BroadcastChannel grava um valor diferente a cada chamada", () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    const setItemSpy = vi.spyOn(Storage.prototype, "setItem");

    announceSessionChanged();
    announceSessionChanged();
    announceSessionChanged();

    const written = setItemSpy.mock.calls
      .filter(([key]) => key === SYNC_KEY)
      .map(([, value]) => value as string);

    // Writing the same tab's own constant TAB_ID on every call (the original bug) means only the
    // first write ever differs from what's already stored — a browser skips the `storage` event
    // entirely when a write doesn't change the value, so a second and third announcement from the
    // same tab would otherwise go completely unnoticed by every other tab.
    expect(written).toHaveLength(3);
    expect(new Set(written).size).toBe(3);
    // Still the same emitting tab throughout: the identifying prefix never changes, only the nonce.
    const emitterIds = written.map((value) => value.split(":")[0]);
    expect(new Set(emitterIds).size).toBe(1);
  });

  test("8b. três anúncios sucessivos de outra aba (login remoto, logout remoto, login remoto de novo) geram três reconciliações", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    expect(authApi.fetchSession).toHaveBeenCalledTimes(1);

    // 1) another tab logs in as B.
    authApi.fetchSession.mockResolvedValueOnce(sessionB);
    booksApi.fetchBooks.mockResolvedValueOnce(["Livro do Autor B"]);
    dispatchForeignStorageAnnouncement("outra-aba", "nonce-1");
    await screen.findByText("Livro do Autor B");
    expect(authApi.fetchSession).toHaveBeenCalledTimes(2);

    // 2) that tab logs out.
    authApi.fetchSession.mockResolvedValueOnce(null);
    dispatchForeignStorageAnnouncement("outra-aba", "nonce-2");
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(authApi.fetchSession).toHaveBeenCalledTimes(3);
    navigation.pathname = "/login";

    // 3) that tab logs in again. Three distinct storage values, three distinct reconciliations — none
    // of the later two were silently swallowed because an earlier write "already happened".
    authApi.fetchSession.mockResolvedValueOnce(sessionA);
    dispatchForeignStorageAnnouncement("outra-aba", "nonce-3");
    await waitFor(() => expect(authApi.fetchSession).toHaveBeenCalledTimes(4));
  });

  test("9. eventos com o próprio TAB_ID continuam ignorados mesmo com nonces diferentes", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    const callsBeforeSelfEvents = authApi.fetchSession.mock.calls.length;

    // Discovers this tab's own TAB_ID the same way any other tab would produce it — by calling the
    // real announceSessionChanged() and reading back what it actually wrote — rather than hardcoding
    // an assumed value that could drift from the real implementation.
    act(() => announceSessionChanged());
    const ownWrite = window.localStorage.getItem(SYNC_KEY);
    expect(ownWrite).toBeTruthy();
    const ownTabId = ownWrite!.split(":")[0];

    // Three more "announcements" carrying this same tab's id but a fresh nonce each time — exactly
    // what this tab's own future logins/logouts would produce. A nonce that changes on every write
    // must not be mistaken for a different, genuinely external, tab.
    dispatchForeignStorageAnnouncement(ownTabId, "self-nonce-1");
    dispatchForeignStorageAnnouncement(ownTabId, "self-nonce-2");
    dispatchForeignStorageAnnouncement(ownTabId, "self-nonce-3");

    // Nothing reacted: no extra /api/auth/me call beyond the one announceSessionChanged() itself may
    // have triggered indirectly, no reconciliation screen, Autor A's data untouched.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(authApi.fetchSession.mock.calls.length).toBe(callsBeforeSelfEvents);
    expect(screen.queryByText("Verificando sessão…")).not.toBeInTheDocument();
    expect(screen.getByText("Livro do Autor A")).toBeInTheDocument();
  });
});
