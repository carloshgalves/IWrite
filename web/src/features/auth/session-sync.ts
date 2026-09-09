"use client";

import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  beginSessionReconciliation,
  finishSessionReconciliation,
  purgeAuthenticatedCaches,
} from "@/features/auth/session-cache";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";

const CHANNEL_NAME = "iwrite-session-sync";
const STORAGE_KEY = "iwrite-session-sync";
/** A broadcast and a focus/visibility event landing within this window are one real-world event,
 *  not two — the broadcast path already runs the strictest possible reaction. */
const DEDUPE_WINDOW_MS = 300;

/** Identifies this tab's own BroadcastChannel messages, nothing else about it. A `BroadcastChannel`
 *  only exempts the exact instance that sent a message from receiving it back — a second instance in
 *  the very same tab (which is what the listener below is) gets it like any other tab would. Without
 *  this, a tab announcing its own login/logout would immediately react to itself: wipe the cache it
 *  just populated and ask /api/auth/me again for no reason. One random token per page load, never a
 *  user, tenant, or session identifier. */
const TAB_ID = `${Date.now()}-${Math.random().toString(36).slice(2)}`;

/** A monotonically distinct suffix for every storage write. `storage` events only fire in other tabs
 *  when the value actually *changes* — writing the same constant TAB_ID on every call (the original
 *  bug) means only the very first announcement in a tab's lifetime ever produces an event, and every
 *  later login/logout from that same tab silently writes the value it already wrote before. A
 *  counter is enough on its own (it never repeats within a tab's lifetime), but `crypto.randomUUID`
 *  is preferred where available so the value carries no positional information either. */
let storageNonceCounter = 0;
function randomNonce(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  storageNonceCounter += 1;
  return `${Date.now()}-${storageNonceCounter}-${Math.random().toString(36).slice(2)}`;
}

/** Tells every other same-origin tab "go check your session again" — nothing else. No email, no
 *  user or tenant id, no cookie or CSRF token: the event is opaque by design, so there is nothing in
 *  it worth intercepting, and nothing for a receiving tab to retransmit. */
export function announceSessionChanged(): void {
  if (typeof window === "undefined") return;
  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel(CHANNEL_NAME);
    channel.postMessage(TAB_ID);
    channel.close();
    return;
  }
  try {
    // Only OTHER tabs receive a `storage` event for this write — the tab that wrote it never does,
    // which is exactly the property that keeps this from looping. The `TAB_ID:` prefix is only there
    // so a receiving tab can still recognize (and, in principle, ignore) its own writes; the nonce
    // after it exists purely to make every single write a distinct value so the event actually fires
    // — this never depends on a prior removeItem, and never touches any other key.
    window.localStorage.setItem(STORAGE_KEY, `${TAB_ID}:${randomNonce()}`);
  } catch {
    // Storage disabled (private browsing, quota exceeded) — no cross-tab signal, same degraded
    // posture as a browser with neither BroadcastChannel nor a working `storage` event.
  }
}

async function refetchSession(client: QueryClient) {
  await client.refetchQueries({ queryKey: SESSION_QUERY_KEY, exact: true });
}

/**
 * The one reaction every trigger below performs — a remote tab's opaque announcement and this tab
 * regaining focus alike. `AuthenticatedSession` deliberately carries no stable tenant identity (no
 * userId, no tenantId — the browser is never told either), so email + workspace name was the only
 * thing ever available to compare on focus, and workspace names are not unique: the same person
 * moved to a different tenant that happens to share a display name with the old one would compare
 * equal, leaving the old tenant's cache intact while every new request already runs under the new
 * one. There is no field or combination of fields in this payload that makes "did the identity
 * change" a safe question to answer client-side, so this purges unconditionally on every call
 * instead of trying to answer it — the same eager reaction a remote announcement already required,
 * since it too cannot tell what changed from the trigger alone.
 */
async function reconcile(queryClient: QueryClient): Promise<void> {
  await queryClient.cancelQueries();
  beginSessionReconciliation(queryClient);
  purgeAuthenticatedCaches(queryClient);
  let restoreAuthenticatedQueries = false;
  try {
    await refetchSession(queryClient);
    restoreAuthenticatedQueries = Boolean(queryClient.getQueryData(SESSION_QUERY_KEY));
  } finally {
    // A session worth keeping, not a logout: whatever domain queries are still actively observed
    // reload for it. The coordinated finish also absorbs a stale-mutation purge that lands during
    // this reconciliation without racing a domain request against the unresolved session.
    await finishSessionReconciliation(queryClient, restoreAuthenticatedQueries);
  }
}

/**
 * Mounted once at the app root (inside SessionGuard). Reacts to another tab's login/logout
 * (BroadcastChannel, or a `storage` event when it is unavailable) and to this tab regaining focus,
 * in both cases by asking /api/auth/me again rather than trusting anything carried in the trigger.
 * Returns whether that check is in flight, so the caller can block the protected UI meanwhile.
 */
export function useSessionReconciliation(): boolean {
  const queryClient = useQueryClient();
  const [isReconciling, setIsReconciling] = useState(false);

  // Refs, not state: these coordinate overlapping async triggers within one effect's lifetime and
  // must never themselves cause a render.
  const runningRef = useRef(false);
  const rerunPendingRef = useRef(false);
  const lastRunRef = useRef(0);
  const unmountedRef = useRef(false);

  useEffect(() => {
    unmountedRef.current = false;

    async function runReconciliation() {
      if (runningRef.current) {
        // A second (or third) trigger arrived mid-check — e.g. a fast logout-then-login in another
        // tab, or focus and visibilitychange landing back to back. Its answer might already be stale
        // by the time the current check resolves, so one more pass is queued instead of trusting
        // whichever fetch happens to land first.
        rerunPendingRef.current = true;
        return;
      }
      runningRef.current = true;
      setIsReconciling(true);
      try {
        do {
          rerunPendingRef.current = false;
          await reconcile(queryClient);
        } while (rerunPendingRef.current);
      } finally {
        lastRunRef.current = Date.now();
        runningRef.current = false;
        if (!unmountedRef.current) setIsReconciling(false);
      }
    }

    let channel: BroadcastChannel | undefined;
    function onStorage(event: StorageEvent) {
      if (event.key !== STORAGE_KEY || event.newValue == null) return;
      // Every value is "<TAB_ID>:<nonce>" now (see announceSessionChanged) — only the prefix before
      // the first colon identifies the emitter, so a fresh nonce on every publication from another
      // tab still reaches here, while this tab's own writes are still recognized and skipped.
      const emitterTabId = event.newValue.split(":", 1)[0];
      if (emitterTabId === TAB_ID) return;
      void runReconciliation();
    }

    if (typeof BroadcastChannel !== "undefined") {
      channel = new BroadcastChannel(CHANNEL_NAME);
      channel.onmessage = (event) => {
        if (event.data === TAB_ID) return;
        void runReconciliation();
      };
    } else {
      window.addEventListener("storage", onStorage);
    }

    function onFocusLike() {
      if (document.visibilityState === "hidden") return;
      // A broadcast/storage trigger is never deduped this way — every distinct remote announcement
      // still runs its own reconciliation (or queues one, above). Only focus and visibilitychange,
      // which routinely fire back to back, synchronously, for the same real-world event, are
      // collapsed into one. lastRunRef is stamped right here, before runReconciliation() is even
      // called — not only in its own finally block once the run completes — precisely so that the
      // second of the pair (fired moments later in the same tick) sees a fresh stamp and returns
      // here instead of reaching runReconciliation() at all. Reaching it would not start a second
      // concurrent run (runningRef already guards that), but it would queue a real rerun for when
      // the first one finishes — correct for a genuinely new remote event arriving mid-check, wrong
      // for what is really one user action reported twice.
      if (Date.now() - lastRunRef.current < DEDUPE_WINDOW_MS) return;
      lastRunRef.current = Date.now();
      void runReconciliation();
    }
    window.addEventListener("focus", onFocusLike);
    document.addEventListener("visibilitychange", onFocusLike);

    return () => {
      unmountedRef.current = true;
      channel?.close();
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("focus", onFocusLike);
      document.removeEventListener("visibilitychange", onFocusLike);
    };
  }, [queryClient]);

  return isReconciling;
}
