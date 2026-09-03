import { apiRequest } from "@/lib/api/client";

export type PersonaType = "WRITER" | "EDITOR" | "REVIEWER" | "BETA_READER" | "OTHER";

export type Profile = {
  displayName: string;
  email: string;
  timeZone: string;
  personas: { type: PersonaType; primary: boolean }[];
};

export type ProfileUpdateInput = {
  displayName: string;
  timeZone: string;
  personas: PersonaType[];
  primaryPersona: PersonaType;
};

export function fetchProfile() {
  return apiRequest<Profile>("/api/profile");
}

export function updateProfile(input: ProfileUpdateInput) {
  return apiRequest<Profile>("/api/profile", { method: "PATCH", body: input });
}
