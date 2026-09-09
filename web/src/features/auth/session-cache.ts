import { hashKey, type Mutation, type QueryClient } from "@tanstack/react-query";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";

function isDomainQuery(queryKey: readonly unknown[]) {
  return hashKey(queryKey) !== hashKey(SESSION_QUERY_KEY);
}

/** Every cache entry fetched under a session that is ending, the session key itself excluded so the
 *  route guard can redirect from its data instead of racing a fresh /api/auth/me. Not enumerated by
 *  name, so no current or future domain query can be missed.
 *
 *  Uses `query.reset()` rather than `client.removeQueries()`: removing deletes the Query object from
 *  the cache outright, and a component that stays mounted through the swap (rather than genuinely
 *  unmounting and remounting - which React is free to skip entirely if the whole reconciliation
 *  settles inside one batch, as it does whenever /api/auth/me resolves fast) keeps its observer
 *  pointed at that now-orphaned object forever, showing the old tenant's last data with nothing left
 *  to ever refetch it. `reset()` clears the same query's state in place, so the observer that is
 *  still watching it is notified immediately - no remount required - and the query stays in the
 *  cache for refetchActiveDomainQueries to find afterwards. */
export function purgeAuthenticatedCaches(client: QueryClient): void {
  client
    .getQueryCache()
    .findAll({ predicate: (query) => isDomainQuery(query.queryKey) })
    .forEach((query) => query.reset());
  client.getMutationCache().clear();
}

/** The other half of purgeAuthenticatedCaches: once reconciliation has landed on a session worth
 *  keeping (a real identity, not a logout), whatever domain queries are still actively observed need
 *  to actually reload for it. Deliberately not automatic — purging ahead of a session ending in
 *  logout must not spend a retried, doomed-to-401 refetch on data about to be abandoned anyway. */
export async function refetchActiveDomainQueries(client: QueryClient): Promise<void> {
  await client.refetchQueries({ type: "active", predicate: (query) => isDomainQuery(query.queryKey) });
}

/**
 * A generation counter per QueryClient, bumped every time a reconciliation actually discards the
 * cache. Deliberately not a timestamp comparison: `Date.now()` is millisecond-resolution, so a
 * mutation's `submittedAt` and a reconciliation's cutoff can land on the exact same millisecond, and
 * a mutation started at the same instant reconciliation began must never be treated as belonging to
 * the identity reconciliation is switching *to* — an inequality like `submittedAt < cutoff` gets that
 * wrong on a tie. An integer generation sidesteps the question entirely: a mutation is stamped with
 * whatever generation was current at the moment it started (mutationCache's global `onMutate`, in
 * query-provider.tsx, calls stampMutationGeneration for exactly that), and it is stale if that stamp
 * is behind the client's generation *now* — no clock, no tie to break.
 */
const reconciliationGenerations = new WeakMap<QueryClient, number>();
const mutationGenerations = new WeakMap<Mutation<unknown, unknown, unknown, unknown>, number>();

interface AuthenticatedCacheRecoveryState {
  reconciliationInProgress: boolean;
  stalePurgeRevision: number;
  recoveredStalePurgeRevision: number;
  recoveryPromise?: Promise<void>;
}

const authenticatedCacheRecoveryStates = new WeakMap<QueryClient, AuthenticatedCacheRecoveryState>();

function recoveryState(client: QueryClient): AuthenticatedCacheRecoveryState {
  const existing = authenticatedCacheRecoveryStates.get(client);
  if (existing) return existing;
  const created = {
    reconciliationInProgress: false,
    stalePurgeRevision: 0,
    recoveredStalePurgeRevision: 0,
  };
  authenticatedCacheRecoveryStates.set(client, created);
  return created;
}

function currentGeneration(client: QueryClient): number {
  return reconciliationGenerations.get(client) ?? 0;
}

/** Captures the same reconciliation generation used by the global mutation cache. Components that
 *  must avoid their own stale onSuccess side effects can retain this token for the mutation. */
export function captureSessionGeneration(client: QueryClient): number {
  return currentGeneration(client);
}

/** True only while no reconciliation has started since the supplied generation was captured. */
export function isSessionGenerationCurrent(client: QueryClient, generation: number): boolean {
  return generation === currentGeneration(client);
}

export function markReconciliationStart(client: QueryClient): void {
  reconciliationGenerations.set(client, currentGeneration(client) + 1);
}

/** Starts the focus/cross-tab reconciliation window in addition to advancing its identity
 *  generation. Direct login/register session replacement advances the generation too, but does not
 *  use this in-flight coordination because it has no session refetch/domain-refetch window. */
export function beginSessionReconciliation(client: QueryClient): void {
  markReconciliationStart(client);
  recoveryState(client).reconciliationInProgress = true;
}

async function refetchCurrentIdentityUntilStable(client: QueryClient): Promise<void> {
  const state = recoveryState(client);
  if (state.recoveryPromise) {
    await state.recoveryPromise;
    if (client.getQueryData(SESSION_QUERY_KEY)
        && state.recoveredStalePurgeRevision !== state.stalePurgeRevision) {
      await refetchCurrentIdentityUntilStable(client);
    }
    return;
  }

  const recoveryPromise = (async () => {
    while (client.getQueryData(SESSION_QUERY_KEY)) {
      const revisionBeingRecovered = state.stalePurgeRevision;
      await refetchActiveDomainQueries(client);
      if (revisionBeingRecovered === state.stalePurgeRevision) {
        state.recoveredStalePurgeRevision = revisionBeingRecovered;
        return;
      }
    }
  })();
  state.recoveryPromise = recoveryPromise;
  try {
    await recoveryPromise;
  } finally {
    if (state.recoveryPromise === recoveryPromise) {
      state.recoveryPromise = undefined;
    }
  }
}

/** Finishes a successful or failed session reconciliation. A stale mutation that purged before the
 *  ordinary domain refetch is covered by that one refetch; if it purges while the refetch is in
 *  flight, the revision changes and the serialized loop performs exactly one more pass. */
export async function finishSessionReconciliation(
  client: QueryClient,
  restoreAuthenticatedQueries: boolean,
): Promise<void> {
  const state = recoveryState(client);
  try {
    if (restoreAuthenticatedQueries && client.getQueryData(SESSION_QUERY_KEY)) {
      await refetchCurrentIdentityUntilStable(client);
    }
  } finally {
    state.reconciliationInProgress = false;
  }
  // A query can publish B's data immediately before its refetch Promise resolves. If a stale
  // mutation purges in that narrow boundary, it observes reconciliationInProgress=true and defers;
  // this post-finalization check closes the handoff without leaving that purge unrecovered.
  if (restoreAuthenticatedQueries
      && client.getQueryData(SESSION_QUERY_KEY)
      && state.recoveredStalePurgeRevision !== state.stalePurgeRevision) {
    await refetchCurrentIdentityUntilStable(client);
  }
}

/** Removes any writes made by a mutation from an older identity, then restores active queries for
 *  the currently authenticated identity. During reconciliation the restore is deferred to its
 *  coordinated final refetch, so domain requests never race the still-unresolved session and a
 *  logout never causes authenticated data to be fetched again. */
export async function recoverAuthenticatedCachesAfterStaleMutation(client: QueryClient): Promise<void> {
  purgeAuthenticatedCaches(client);
  const state = recoveryState(client);
  state.stalePurgeRevision += 1;
  if (state.reconciliationInProgress || !client.getQueryData(SESSION_QUERY_KEY)) return;
  await refetchCurrentIdentityUntilStable(client);
}

/** Called from mutationCache's global `onMutate`, before the mutation's own mutationFn runs. */
export function stampMutationGeneration(
  client: QueryClient,
  mutation: Mutation<unknown, unknown, unknown, unknown>,
): void {
  mutationGenerations.set(mutation, currentGeneration(client));
}

/** A mutation stamped with a generation older than the client's current one started before or during
 *  the reconciliation that produced that current generation — it can only be a straggler from the
 *  identity being discarded, no matter what it wrote on its own way out or exactly when it settles. */
export function isStaleMutation(
  client: QueryClient,
  mutation: Mutation<unknown, unknown, unknown, unknown>,
): boolean {
  const stamped = mutationGenerations.get(mutation) ?? 0;
  return stamped < currentGeneration(client);
}
