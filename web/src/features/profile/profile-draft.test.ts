import { describe, expect, test } from "vitest";
import type { Profile } from "@/features/profile/api/profile-api";
import {
  draftFromProfile,
  rebaseDraft,
  type ProfileDraft,
} from "@/features/profile/profile-draft";

const baselineProfile: Profile = {
  displayName: "Ana",
  email: "ana@iwrite.local",
  timeZone: "America/Sao_Paulo",
  personas: [{ type: "WRITER", primary: true }],
};

function edited(draft: ProfileDraft, changes: Partial<ProfileDraft>): ProfileDraft {
  return { ...draft, ...changes };
}

describe("rebase do draft de perfil", () => {
  test("preserva somente o nome local e aceita o timezone remoto novo", () => {
    const local = edited(draftFromProfile(baselineProfile), { displayName: "Ana Maria" });
    const remote = { ...baselineProfile, timeZone: "America/Fortaleza" };

    const rebased = rebaseDraft(local, remote);

    expect(rebased.displayName).toBe("Ana Maria");
    expect(rebased.timeZone).toBe("America/Fortaleza");
  });

  test("exemplo obrigatório: preserva nome local e incorpora timezone, personas e primary remotos", () => {
    const local = edited(draftFromProfile(baselineProfile), { displayName: "Ana Maria" });
    const remote: Profile = {
      ...baselineProfile,
      timeZone: "America/Fortaleza",
      personas: [
        { type: "WRITER", primary: false },
        { type: "EDITOR", primary: true },
      ],
    };

    const rebased = rebaseDraft(local, remote);

    expect(rebased).toMatchObject({
      displayName: "Ana Maria",
      timeZone: "America/Fortaleza",
      selectedPersonas: ["WRITER", "EDITOR"],
      primaryPersona: "EDITOR",
    });
  });

  test("combina persona adicionada localmente com persona adicionada remotamente", () => {
    const local = edited(draftFromProfile(baselineProfile), {
      selectedPersonas: ["WRITER", "REVIEWER"],
    });
    const remote: Profile = {
      ...baselineProfile,
      personas: [
        { type: "WRITER", primary: false },
        { type: "EDITOR", primary: true },
      ],
    };

    const rebased = rebaseDraft(local, remote);

    expect(rebased.selectedPersonas).toEqual(["WRITER", "EDITOR", "REVIEWER"]);
    expect(rebased.primaryPersona).toBe("EDITOR");
  });

  test("preserva remoção local e incorpora alteração remota não conflitante", () => {
    const original: Profile = {
      ...baselineProfile,
      personas: [
        { type: "WRITER", primary: true },
        { type: "REVIEWER", primary: false },
      ],
    };
    const local = edited(draftFromProfile(original), { selectedPersonas: ["WRITER"] });
    const remote: Profile = {
      ...original,
      personas: [
        { type: "WRITER", primary: true },
        { type: "EDITOR", primary: false },
        { type: "REVIEWER", primary: false },
      ],
    };

    expect(rebaseDraft(local, remote).selectedPersonas).toEqual(["WRITER", "EDITOR"]);
  });

  test("primary sem alteração local acompanha a nova primary remota", () => {
    const local = draftFromProfile(baselineProfile);
    const remote: Profile = {
      ...baselineProfile,
      personas: [
        { type: "WRITER", primary: false },
        { type: "EDITOR", primary: true },
      ],
    };

    expect(rebaseDraft(local, remote).primaryPersona).toBe("EDITOR");
  });

  test("primary alterada localmente é preservada quando continua selecionada", () => {
    const original: Profile = {
      ...baselineProfile,
      personas: [
        { type: "WRITER", primary: true },
        { type: "REVIEWER", primary: false },
      ],
    };
    const local = edited(draftFromProfile(original), { primaryPersona: "REVIEWER" });
    const remote: Profile = {
      ...original,
      personas: [
        { type: "WRITER", primary: false },
        { type: "EDITOR", primary: true },
        { type: "REVIEWER", primary: false },
      ],
    };

    expect(rebaseDraft(local, remote).primaryPersona).toBe("REVIEWER");
  });

  test("primary local ausente do conjunto final cai deterministicamente para uma opção válida", () => {
    const original: Profile = {
      ...baselineProfile,
      personas: [
        { type: "WRITER", primary: true },
        { type: "REVIEWER", primary: false },
      ],
    };
    const local = edited(draftFromProfile(original), { primaryPersona: "REVIEWER" });
    const remote: Profile = {
      ...baselineProfile,
      personas: [{ type: "WRITER", primary: true }],
    };

    const rebased = rebaseDraft(local, remote);

    expect(rebased.selectedPersonas).toEqual(["WRITER"]);
    expect(rebased.primaryPersona).toBe("WRITER");
    expect(rebased.selectedPersonas).toContain(rebased.primaryPersona);
  });

  test("sem mudanças locais adota integralmente o snapshot remoto e o torna baseline", () => {
    const local = draftFromProfile(baselineProfile);
    const remote: Profile = {
      displayName: "Ana Atualizada",
      email: baselineProfile.email,
      timeZone: "UTC",
      personas: [
        { type: "EDITOR", primary: true },
        { type: "OTHER", primary: false },
      ],
    };

    const rebased = rebaseDraft(local, remote);

    expect(rebased).toMatchObject({
      displayName: "Ana Atualizada",
      timeZone: "UTC",
      selectedPersonas: ["EDITOR", "OTHER"],
      primaryPersona: "EDITOR",
    });
    expect(rebased.baseline).toEqual(draftFromProfile(remote).baseline);
  });
});
