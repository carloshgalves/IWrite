"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import type { AuthenticatedSession } from "@/features/auth/api/auth-api";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";
import {
  fetchProfile,
  updateProfile,
  type PersonaType,
  type Profile,
} from "@/features/profile/api/profile-api";
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
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [timeZone, setTimeZone] = useState("");
  const [selectedPersonas, setSelectedPersonas] = useState<PersonaType[]>([]);
  const [primaryPersona, setPrimaryPersona] = useState<PersonaType>("WRITER");
  const errorRef = useRef<HTMLDivElement>(null);

  const updateMutation = useMutation({
    mutationFn: updateProfile,
    onSuccess: (profile) => {
      applyProfile(profile);
      queryClient.setQueryData(queryKeys.profile, profile);
      queryClient.setQueryData<AuthenticatedSession | null>(SESSION_QUERY_KEY, (current) =>
        current
          ? { ...current, user: { ...current.user, displayName: profile.displayName } }
          : current,
      );
    },
  });

  function applyProfile(profile: Profile) {
    setDisplayName(profile.displayName);
    setEmail(profile.email);
    setTimeZone(profile.timeZone);
    setSelectedPersonas(profile.personas.map((persona) => persona.type));
    setPrimaryPersona(profile.personas.find((persona) => persona.primary)?.type ?? profile.personas[0].type);
  }

  useEffect(() => {
    if (profileQuery.data) {
      applyProfile(profileQuery.data);
    }
  }, [profileQuery.data]);

  useEffect(() => {
    if (updateMutation.isError) {
      errorRef.current?.focus();
    }
  }, [updateMutation.isError]);

  function togglePersona(type: PersonaType) {
    updateMutation.reset();
    if (selectedPersonas.includes(type)) {
      if (selectedPersonas.length === 1) {
        return;
      }
      const remaining = selectedPersonas.filter((persona) => persona !== type);
      setSelectedPersonas(remaining);
      if (primaryPersona === type) {
        setPrimaryPersona(remaining[0]);
      }
      return;
    }
    setSelectedPersonas([...selectedPersonas, type]);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (updateMutation.isPending || selectedPersonas.length === 0) {
      return;
    }
    const personasInCanonicalOrder = PERSONAS
      .map(({ type }) => type)
      .filter((type) => selectedPersonas.includes(type));
    updateMutation.mutate({ displayName, timeZone, personas: personasInCanonicalOrder, primaryPersona });
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

  return (
    <form className="grid gap-6" onSubmit={handleSubmit} noValidate>
      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-display-name">Nome de exibição</label>
        <Input
          id="profile-display-name"
          autoComplete="name"
          value={displayName}
          onChange={(event) => {
            updateMutation.reset();
            setDisplayName(event.target.value);
          }}
        />
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-email">Email</label>
        <Input id="profile-email" type="email" value={email} readOnly aria-describedby="profile-email-help" />
        <p id="profile-email-help" className="text-xs text-zinc-500">O email não pode ser alterado nesta tela.</p>
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="profile-time-zone">Fuso horário</label>
        <Input
          id="profile-time-zone"
          list="profile-time-zones"
          value={timeZone}
          onChange={(event) => {
            updateMutation.reset();
            setTimeZone(event.target.value);
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
            const checked = selectedPersonas.includes(type);
            return (
              <label key={type} className="flex min-h-10 items-center gap-3 rounded-md border border-zinc-200 px-3 py-2 text-sm">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={checked && selectedPersonas.length === 1}
                  onChange={() => togglePersona(type)}
                />
                <span>{label}</span>
              </label>
            );
          })}
        </fieldset>

        <fieldset className="grid gap-2" role="radiogroup" aria-label="Persona principal">
          <legend className="text-sm font-medium text-zinc-700">Escolha a persona principal</legend>
          {PERSONAS.filter(({ type }) => selectedPersonas.includes(type)).map(({ type, label }) => (
            <label key={type} className="flex items-center gap-3 text-sm text-zinc-700">
              <input
                type="radio"
                name="primaryPersona"
                aria-label={`${label} como principal`}
                checked={primaryPersona === type}
                onChange={() => {
                  updateMutation.reset();
                  setPrimaryPersona(type);
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

      <Button type="submit" disabled={updateMutation.isPending}>
        {updateMutation.isPending ? "Salvando…" : "Salvar alterações"}
      </Button>
    </form>
  );
}
