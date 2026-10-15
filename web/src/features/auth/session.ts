"use client";

import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import {
  fetchSession,
  login,
  logout,
  register,
  type AuthenticatedSession,
  type RegisterInput,
} from "@/features/auth/api/auth-api";
import { markReconciliationStart, purgeAuthenticatedCaches } from "@/features/auth/session-cache";
import { announceSessionChanged } from "@/features/auth/session-sync";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";

export { SESSION_QUERY_KEY };

export function useSession() {
  return useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchSession,
    // Refetching on an interval would just be a way to log the user out at random. Focus and
    // cross-tab revalidation still happen, deliberately, but through useSessionReconciliation
    // (session-sync.ts) rather than this query's own options — that path purges the rest of the
    // cache unconditionally on every check, since this payload carries no stable tenant identity to
    // compare against (see reconcile() in session-sync.ts for why that comparison isn't safe).
    staleTime: Infinity,
    retry: false,
  });
}

/**
 * Shared by {@link useLogin} and {@link useRegister}: both end with a brand-new, freshly
 * authenticated session that must replace whatever the client had cached before, exactly the same
 * way in both cases — everything cached belongs to the previous tenant (even a login/register as
 * the same account, since the client has no trustworthy tenantId to prove otherwise). Cancel
 * in-flight work first so a stale response can't repopulate the cache after the purge below,
 * advance the reconciliation generation so any straggler mutation still in flight recognizes
 * itself as stale (session-cache.ts), then purge — all of it before the new session is ever stored,
 * so no
 * content from the old tenant can exist between storing the new session and the library's first
 * render under it.
 */
async function applyNewSession(queryClient: QueryClient, router: ReturnType<typeof useRouter>, session: AuthenticatedSession) {
  await queryClient.cancelQueries();
  markReconciliationStart(queryClient);
  purgeAuthenticatedCaches(queryClient);

  queryClient.setQueryData(SESSION_QUERY_KEY, session);
  announceSessionChanged();
  router.replace("/library");
}

export function useLogin() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
    // A 401 from this mutation means invalid credentials, not a revoked session — query-provider.tsx's
    // mutationCache.onError checks this flag before treating any 401 as a global session expiry, so a
    // mistyped-password attempt to switch accounts never ends (or announces the end of) a session that
    // is still valid on the server.
    meta: { ignoreGlobalSessionExpiry: true },
    onSuccess: (session) => applyNewSession(queryClient, router, session),
  });
}

export function useRegister() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: (input: RegisterInput) => register(input),
    // Same rationale as useLogin: an email-already-used (409) or a validation failure (400) from
    // this mutation is a local form error, never a revoked session.
    meta: { ignoreGlobalSessionExpiry: true },
    onSuccess: (session) => applyNewSession(queryClient, router, session),
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      // Everything cached was fetched as this user in this tenant. Dropping the whole cache is the
      // only version of "clear authenticated data" that cannot miss a key.
      queryClient.clear();
      announceSessionChanged();
      router.replace("/login");
    },
  });
}
