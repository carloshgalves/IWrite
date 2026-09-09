"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { PersonaType, Profile } from "@/features/profile/api/profile-api";

export interface ProfileDraft {
  email: string;
  displayName: string;
  timeZone: string;
  selectedPersonas: PersonaType[];
  primaryPersona: PersonaType;
  baseline: ProfileDraftBaseline;
}

export interface ProfileDraftBaseline {
  displayName: string;
  timeZone: string;
  selectedPersonas: PersonaType[];
  primaryPersona: PersonaType;
}

type DraftChanges = Partial<Omit<ProfileDraft, "email" | "baseline">>;

interface ProfileDraftContextValue {
  drafts: Readonly<Record<string, ProfileDraft>>;
  pendingSaves: Readonly<Record<string, boolean>>;
  loadProfile: (profile: Profile) => void;
  updateDraft: (email: string, changes: DraftChanges) => void;
  applySavedProfile: (profile: Profile) => void;
  setSavePending: (email: string, pending: boolean) => void;
}

const ProfileDraftContext = createContext<ProfileDraftContextValue | null>(null);

const PERSONA_ORDER: PersonaType[] = ["WRITER", "EDITOR", "REVIEWER", "BETA_READER", "OTHER"];

function baselineFromProfile(profile: Profile): ProfileDraftBaseline {
  const selectedPersonas = profile.personas.map((persona) => persona.type);
  return {
    displayName: profile.displayName,
    timeZone: profile.timeZone,
    selectedPersonas,
    primaryPersona: profile.personas.find((persona) => persona.primary)?.type ?? profile.personas[0].type,
  };
}

export function draftFromProfile(profile: Profile): ProfileDraft {
  const baseline = baselineFromProfile(profile);
  return { email: profile.email, ...baseline, baseline };
}

function canonicalPersonas(personas: Iterable<PersonaType>): PersonaType[] {
  const selected = new Set(personas);
  return PERSONA_ORDER.filter((persona) => selected.has(persona));
}

/** Rebases only local deltas onto the latest trusted profile response. The old baseline answers
 *  which fields this tab actually changed; the new response supplies every untouched value. */
export function rebaseDraft(localDraft: ProfileDraft, newProfile: Profile): ProfileDraft {
  if (localDraft.email !== newProfile.email) return draftFromProfile(newProfile);

  const oldBaseline = localDraft.baseline;
  const newBaseline = baselineFromProfile(newProfile);
  const locallyAdded = localDraft.selectedPersonas.filter(
    (persona) => !oldBaseline.selectedPersonas.includes(persona),
  );
  const locallyRemoved = oldBaseline.selectedPersonas.filter(
    (persona) => !localDraft.selectedPersonas.includes(persona),
  );
  const rebasedPersonas = new Set(newBaseline.selectedPersonas);
  locallyRemoved.forEach((persona) => rebasedPersonas.delete(persona));
  locallyAdded.forEach((persona) => rebasedPersonas.add(persona));

  if (rebasedPersonas.size === 0) {
    const localFallback = localDraft.selectedPersonas.includes(localDraft.primaryPersona)
      ? localDraft.primaryPersona
      : canonicalPersonas(localDraft.selectedPersonas)[0];
    rebasedPersonas.add(localFallback ?? newBaseline.selectedPersonas[0] ?? PERSONA_ORDER[0]);
  }
  const selectedPersonas = canonicalPersonas(rebasedPersonas);
  const primaryChangedLocally = localDraft.primaryPersona !== oldBaseline.primaryPersona;
  const primaryPersona = primaryChangedLocally && selectedPersonas.includes(localDraft.primaryPersona)
    ? localDraft.primaryPersona
    : selectedPersonas.includes(newBaseline.primaryPersona)
      ? newBaseline.primaryPersona
      : selectedPersonas[0];

  return {
    email: newProfile.email,
    displayName: localDraft.displayName !== oldBaseline.displayName
      ? localDraft.displayName
      : newBaseline.displayName,
    timeZone: localDraft.timeZone !== oldBaseline.timeZone
      ? localDraft.timeZone
      : newBaseline.timeZone,
    selectedPersonas,
    primaryPersona,
    baseline: newBaseline,
  };
}

/** In-memory only and mounted above SessionGuard. Email comes from the profile response and is used
 *  solely to keep one identity's unsaved fields separate from another identity's fields. */
export function ProfileDraftProvider({ children }: { children: ReactNode }) {
  const [drafts, setDrafts] = useState<Record<string, ProfileDraft>>({});
  const [pendingSaves, setPendingSaves] = useState<Record<string, boolean>>({});

  const loadProfile = useCallback((profile: Profile) => {
    setDrafts((current) => {
      const existing = current[profile.email];
      return {
        ...current,
        [profile.email]: existing ? rebaseDraft(existing, profile) : draftFromProfile(profile),
      };
    });
  }, []);

  const updateDraft = useCallback((email: string, changes: DraftChanges) => {
    setDrafts((current) => {
      const draft = current[email];
      if (!draft) return current;
      return { ...current, [email]: { ...draft, ...changes } };
    });
  }, []);

  const applySavedProfile = useCallback((profile: Profile) => {
    setDrafts((current) => ({ ...current, [profile.email]: draftFromProfile(profile) }));
  }, []);

  const setSavePending = useCallback((email: string, pending: boolean) => {
    setPendingSaves((current) => {
      if (pending) return { ...current, [email]: true };
      if (!current[email]) return current;
      const next = { ...current };
      delete next[email];
      return next;
    });
  }, []);

  const value = useMemo(
    () => ({ drafts, pendingSaves, loadProfile, updateDraft, applySavedProfile, setSavePending }),
    [drafts, pendingSaves, loadProfile, updateDraft, applySavedProfile, setSavePending],
  );

  return <ProfileDraftContext.Provider value={value}>{children}</ProfileDraftContext.Provider>;
}

export function useProfileDraft(profile: Profile | undefined) {
  const context = useContext(ProfileDraftContext);
  if (!context) {
    throw new Error("useProfileDraft must be used inside ProfileDraftProvider");
  }
  const { drafts, pendingSaves, loadProfile, updateDraft, applySavedProfile, setSavePending } = context;

  useEffect(() => {
    if (profile) loadProfile(profile);
  }, [profile, loadProfile]);

  return {
    draft: profile ? drafts[profile.email] : undefined,
    isSavePending: profile ? Boolean(pendingSaves[profile.email]) : false,
    updateDraft,
    applySavedProfile,
    setSavePending,
  };
}
