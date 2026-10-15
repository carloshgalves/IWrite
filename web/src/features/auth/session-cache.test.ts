import { QueryClient, type Mutation } from "@tanstack/react-query";
import { describe, expect, test } from "vitest";
import {
  captureSessionGeneration,
  isSessionGenerationCurrent,
  isStaleMutation,
  markReconciliationStart,
  stampMutationGeneration,
} from "@/features/auth/session-cache";

/**
 * Covers the mutation-staleness edge case directly, with no rendering and no timers: the previous
 * implementation compared `mutation.state.submittedAt < cutoff`, both millisecond-resolution
 * `Date.now()` values, so a mutation that started in the exact same millisecond a reconciliation
 * began could tie rather than lose — and a tie must never be read as "belongs to the new identity".
 * The generation counter this replaced it with has no clock in it at all, so there is no tie to
 * construct: these tests exercise the ordering directly instead of racing a timestamp.
 */
function fakeMutation() {
  // isStaleMutation/stampMutationGeneration only ever use the mutation as a WeakMap key — any object
  // identity stands in for a real Mutation instance here.
  return {} as Mutation<unknown, unknown, unknown, unknown>;
}

describe("session-cache — geração de reconciliação", () => {
  test("um token capturado deixa de ser atual quando a reconciliação começa", () => {
    const client = new QueryClient();
    const generation = captureSessionGeneration(client);

    expect(isSessionGenerationCurrent(client, generation)).toBe(true);
    markReconciliationStart(client);
    expect(isSessionGenerationCurrent(client, generation)).toBe(false);
  });

  test("uma mutation carimbada antes de uma reconciliação é tratada como obsoleta assim que a reconciliação começa", () => {
    const client = new QueryClient();
    const mutation = fakeMutation();

    stampMutationGeneration(client, mutation);
    expect(isStaleMutation(client, mutation)).toBe(false);

    markReconciliationStart(client);

    expect(isStaleMutation(client, mutation)).toBe(true);
  });

  test("uma mutation carimbada depois de uma reconciliação não é obsoleta", () => {
    const client = new QueryClient();
    markReconciliationStart(client);

    const mutation = fakeMutation();
    stampMutationGeneration(client, mutation);

    expect(isStaleMutation(client, mutation)).toBe(false);
  });

  test("uma segunda reconciliação torna obsoleta até uma mutation que sobreviveu à primeira", () => {
    const client = new QueryClient();
    markReconciliationStart(client);

    const mutation = fakeMutation();
    stampMutationGeneration(client, mutation);
    expect(isStaleMutation(client, mutation)).toBe(false);

    markReconciliationStart(client);
    expect(isStaleMutation(client, mutation)).toBe(true);
  });

  test("clientes diferentes nunca compartilham geração", () => {
    const clientA = new QueryClient();
    const clientB = new QueryClient();

    markReconciliationStart(clientA);

    const mutation = fakeMutation();
    stampMutationGeneration(clientB, mutation);

    // clientB never reconciled, so a mutation stamped under it is never stale there — clientA's
    // reconciliation must not leak across.
    expect(isStaleMutation(clientB, mutation)).toBe(false);
  });
});
