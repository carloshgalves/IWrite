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
  dirty: boolean;
}

type DraftChanges = Partial<Omit<ProfileDraft, "email" | "dirty">>;

interface ProfileDraftContextValue {
  drafts: Readonly<Record<string, ProfileDraft>>;
  loadProfile: (profile: Profile) => void;
  updateDraft: (email: string, changes: DraftChanges) => void;
  applySavedProfile: (profile: Profile) => void;
}

const ProfileDraftContext = createContext<ProfileDraftContextValue | null>(null);

function draftFromProfile(profile: Profile): ProfileDraft {
  return {
    email: profile.email,
    displayName: profile.displayName,
    timeZone: profile.timeZone,
    selectedPersonas: profile.personas.map((persona) => persona.type),
    primaryPersona: profile.personas.find((persona) => persona.primary)?.type ?? profile.personas[0].type,
    dirty: false,
  };
}

function isValidLocalDraft(draft: ProfileDraft | undefined, email: string): draft is ProfileDraft {
  return Boolean(
    draft?.dirty
      && draft.email === email
      && draft.selectedPersonas.length > 0
      && draft.selectedPersonas.includes(draft.primaryPersona),
  );
}

/** In-memory only and mounted above SessionGuard. Email comes from the profile response and is used
 *  solely to keep one identity's unsaved fields separate from another identity's fields. */
export function ProfileDraftProvider({ children }: { children: ReactNode }) {
  const [drafts, setDrafts] = useState<Record<string, ProfileDraft>>({});

  const loadProfile = useCallback((profile: Profile) => {
    setDrafts((current) => {
      if (isValidLocalDraft(current[profile.email], profile.email)) {
        return current;
      }
      return { ...current, [profile.email]: draftFromProfile(profile) };
    });
  }, []);

  const updateDraft = useCallback((email: string, changes: DraftChanges) => {
    setDrafts((current) => {
      const draft = current[email];
      if (!draft) return current;
      return { ...current, [email]: { ...draft, ...changes, dirty: true } };
    });
  }, []);

  const applySavedProfile = useCallback((profile: Profile) => {
    setDrafts((current) => ({ ...current, [profile.email]: draftFromProfile(profile) }));
  }, []);

  const value = useMemo(
    () => ({ drafts, loadProfile, updateDraft, applySavedProfile }),
    [drafts, loadProfile, updateDraft, applySavedProfile],
  );

  return <ProfileDraftContext.Provider value={value}>{children}</ProfileDraftContext.Provider>;
}

export function useProfileDraft(profile: Profile | undefined) {
  const context = useContext(ProfileDraftContext);
  if (!context) {
    throw new Error("useProfileDraft must be used inside ProfileDraftProvider");
  }
  const { drafts, loadProfile, updateDraft, applySavedProfile } = context;

  useEffect(() => {
    if (profile) loadProfile(profile);
  }, [profile, loadProfile]);

  return {
    draft: profile ? drafts[profile.email] : undefined,
    updateDraft,
    applySavedProfile,
  };
}
