"use client";

import { useEffect, useRef, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import type { AuthenticatedSession } from "@/features/auth/api/auth-api";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";
import {
  captureSessionGeneration,
  isSessionGenerationCurrent,
} from "@/features/auth/session-cache";
import {
  fetchProfile,
  updateProfile,
  type PersonaType,
} from "@/features/profile/api/profile-api";
import { useProfileDraft } from "@/features/profile/profile-draft";
import { queryKeys } from "@/lib/query/keys";

const PERSONAS: { type: PersonaType; label: string }[] = [
  { type: "WRITER", label: "Escritor(a)" },
  { type: "EDITOR", label: "Editor(a)" },
  { type: "REVIEWER", label: "Revisor(a)" },
  { type: "BETA_READER", label: "Beta reader" },
  { type: "OTHER", label: "Outro" },
];

const COMMON_TIME_ZONES = ["America/Fortaleza", "America/Sao_Paulo", "UTC"];

export function ProfileSettingsForm() {
  const queryClient = useQueryClient();
  const profileQuery = useQuery({
    queryKey: queryKeys.profile,
    queryFn: fetchProfile,
    retry: false,
  });
  const {
    draft,
    isSavePending,
    updateDraft,
    applySavedProfile,
    setSavePending,
  } = useProfileDraft(profileQuery.data);
  const errorRef = useRef<HTMLDivElement>(null);
  const activeMutationEmailRef = useRef<string | null>(null);

  const updateMutation = useMutation({
    mutationFn: updateProfile,
    onMutate: () => {
      const email = draft!.email;
      activeMutationEmailRef.current = email;
      setSavePending(email, true);
      return { email, generation: captureSessionGeneration(queryClient) };
    },
    onSuccess: (profile, _variables, mutationContext) => {
      if (!isSessionGenerationCurrent(queryClient, mutationContext.generation)) {
        return;
      }
      applySavedProfile(profile);
      queryClient.setQueryData(queryKeys.profile, profile);
      queryClient.setQueryData<AuthenticatedSession | null>(SESSION_QUERY_KEY, (current) =>
        current
          ? { ...current, user: { ...current.user, displayName: profile.displayName } }
          : current,
      );
    },
    onSettled: (_profile, _error, _variables, mutationContext) => {
      if (mutationContext) {
        setSavePending(mutationContext.email, false);
        if (activeMutationEmailRef.current === mutationContext.email) {
          activeMutationEmailRef.current = null;
        }
      }
    },
  });
  const savePending = isSavePending
    || (updateMutation.isPending && activeMutationEmailRef.current === draft?.email);

  useEffect(() => {
    if (updateMutation.isError) {
      errorRef.current?.focus();
    }
  }, [updateMutation.isError]);

  function togglePersona(type: PersonaType) {
    if (!draft) return;
    updateMutation.reset();
    if (draft.selectedPersonas.includes(type)) {
      if (draft.selectedPersonas.length === 1) {
        return;
      }
      const remaining = draft.selectedPersonas.filter((persona) => persona !== type);
      updateDraft(draft.email, {
        selectedPersonas: remaining,
        primaryPersona: draft.primaryPersona === type ? remaining[0] : draft.primaryPersona,
      });
      return;
    }
    updateDraft(draft.email, { selectedPersonas: [...draft.selectedPersonas, type] });
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft || savePending || draft.selectedPersonas.length === 0) {
      return;
    }
    const personasInCanonicalOrder = PERSONAS
      .map(({ type }) => type)
      .filter((type) => draft.selectedPersonas.includes(type));
    updateMutation.mutate({
      displayName: draft.displayName,
      timeZone: draft.timeZone,
      personas: personasInCanonicalOrder,
      primaryPersona: draft.primaryPersona,
    });
  }

  if (profileQuery.isPending) {
    return <FeedbackMessage>Carregando perfil…</FeedbackMessage>;
  }

  if (profileQuery.isError) {
    return (
      <div className="grid justify-items-start gap-3">
        <FeedbackMessage variant="error">Não foi possível carregar seu perfil.</FeedbackMessage>
        <Button type="button" variant="secondary" onClick={() => profileQuery.refetch()}>
          Tentar novamente
        </Button>
      </div>
    );
  }

  if (!draft) {
    return <FeedbackMessage>Carregando perfil…</FeedbackMessage>;
  }

  return (
    <form className="grid gap-6" onSubmit={handleSubmit} noValidate>
      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-display-name">Nome de exibição</label>
        <Input
          id="profile-display-name"
          autoComplete="name"
          value={draft.displayName}
          disabled={savePending}
          onChange={(event) => {
            updateMutation.reset();
            updateDraft(draft.email, { displayName: event.target.value });
          }}
        />
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-email">Email</label>
        <Input id="profile-email" type="email" value={draft.email} readOnly aria-describedby="profile-email-help" />
        <p id="profile-email-help" className="text-xs text-zinc-500">O email não pode ser alterado nesta tela.</p>
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-time-zone">Fuso horário</label>
        <Input
          id="profile-time-zone"
          list="profile-time-zones"
          value={draft.timeZone}
          disabled={savePending}
          onChange={(event) => {
            updateMutation.reset();
            updateDraft(draft.email, { timeZone: event.target.value });
          }}
        />
        <datalist id="profile-time-zones">
          {COMMON_TIME_ZONES.map((zone) => <option key={zone} value={zone} />)}
        </datalist>
      </div>

      <div className="grid gap-4">
        <div className="grid gap-2">
          <h2 className="text-base font-semibold text-zinc-900">Personas profissionais</h2>
          <p className="text-sm leading-6 text-zinc-600">
            Suas personas descrevem como você usa o IWrite. Elas não concedem acesso a livros.
            As permissões de cada livro são definidas separadamente pelo proprietário.
          </p>
        </div>

        <fieldset className="grid gap-2" aria-label="Personas">
          <legend className="text-sm font-medium text-zinc-700">Selecione uma ou mais personas</legend>
          {PERSONAS.map(({ type, label }) => {
            const checked = draft.selectedPersonas.includes(type);
            return (
              <label key={type} className="flex min-h-10 items-center gap-3 rounded-md border border-zinc-200 px-3 py-2 text-sm">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={savePending || (checked && draft.selectedPersonas.length === 1)}
                  onChange={() => togglePersona(type)}
                />
                <span>{label}</span>
              </label>
            );
          })}
        </fieldset>

        <fieldset className="grid gap-2" role="radiogroup" aria-label="Persona principal">
          <legend className="text-sm font-medium text-zinc-700">Escolha a persona principal</legend>
          {PERSONAS.filter(({ type }) => draft.selectedPersonas.includes(type)).map(({ type, label }) => (
            <label key={type} className="flex items-center gap-3 text-sm text-zinc-700">
              <input
                type="radio"
                name="primaryPersona"
                aria-label={`${label} como principal`}
                checked={draft.primaryPersona === type}
                disabled={savePending}
                onChange={() => {
                  updateMutation.reset();
                  updateDraft(draft.email, { primaryPersona: type });
                }}
              />
              <span>{label}</span>
            </label>
          ))}
        </fieldset>
      </div>

      {updateMutation.isError ? (
        <div
          ref={errorRef}
          role="alert"
          tabIndex={-1}
          className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 outline-none focus:ring-2 focus:ring-red-500"
        >
          {updateMutation.error instanceof Error ? updateMutation.error.message : "Não foi possível salvar o perfil."}
        </div>
      ) : null}
      {updateMutation.isSuccess ? (
        <FeedbackMessage variant="success">Perfil atualizado com sucesso.</FeedbackMessage>
      ) : null}

      <Button type="submit" disabled={savePending}>
        {savePending ? "Salvando…" : "Salvar alterações"}
      </Button>
    </form>
  );
}
