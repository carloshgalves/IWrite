"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { CharacterForm } from "@/features/characters/components/character-form";
import { CharactersList } from "@/features/characters/components/characters-list";
import {
  useCharacters,
  useCreateCharacter,
  useDeleteCharacter,
  useUpdateCharacter,
} from "@/features/characters/api/characters-hooks";
import type { CharacterRequest, CharacterResponse } from "@/features/characters/types";
import { ApiError } from "@/lib/api/client";

type CharactersPanelProps = {
  bookId: string;
  openCharacterIntent?: EntityOpenIntent | null;
};

type DetailMode = "empty" | "create" | "edit";

export type EntityOpenIntent = {
  id: string;
  requestId: number;
};

export function CharactersPanel({ bookId, openCharacterIntent = null }: CharactersPanelProps) {
  const charactersQuery = useCharacters(bookId);
  const createMutation = useCreateCharacter(bookId);
  const updateMutation = useUpdateCharacter(bookId);
  const deleteMutation = useDeleteCharacter(bookId);
  const [selectedCharacter, setSelectedCharacter] = useState<CharacterResponse | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("empty");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const consumedOpenRequestIdRef = useRef<number | null>(null);

  const activeMutation = detailMode === "edit" ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getCharacterErrorMessage(activeMutation.error), [activeMutation.error]);
  const deleteErrorMessage = useMemo(() => getCharacterErrorMessage(deleteMutation.error), [deleteMutation.error]);

  useEffect(() => {
    if (!openCharacterIntent || consumedOpenRequestIdRef.current === openCharacterIntent.requestId) {
      return;
    }

    const character = charactersQuery.data?.find((value) => value.id === openCharacterIntent.id);
    if (!character) {
      return;
    }

    consumedOpenRequestIdRef.current = openCharacterIntent.requestId;
    startEdit(character);
  }, [charactersQuery.data, openCharacterIntent]);

  function startCreate() {
    setSelectedCharacter(null);
    setDetailMode("create");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function startEdit(character: CharacterResponse) {
    setSelectedCharacter(character);
    setDetailMode("edit");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function clearDetail() {
    setSelectedCharacter(null);
    setDetailMode("empty");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function handleSubmit(payload: CharacterRequest) {
    setSuccessMessage(null);

    if (detailMode === "edit" && selectedCharacter) {
      updateMutation.mutate(
        { characterId: selectedCharacter.id, payload },
        {
          onSuccess: (character) => {
            setSelectedCharacter(character);
            setSuccessMessage("Personagem atualizado com sucesso.");
          },
        },
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (character) => {
        setSelectedCharacter(character);
        setDetailMode("edit");
        setSuccessMessage("Personagem criado com sucesso.");
      },
    });
  }

  function handleDeleteCharacter(character: CharacterResponse) {
    const confirmed = window.confirm(`Excluir o personagem "${character.name}"? Esta ação não pode ser desfeita.`);
    if (!confirmed) {
      return;
    }

    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();

    deleteMutation.mutate(character.id, {
      onSuccess: () => {
        if (selectedCharacter?.id === character.id) {
          setSelectedCharacter(null);
          setDetailMode("empty");
        }
        setSuccessMessage("Personagem excluído com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-zinc-950">Personagens</h1>
            <p className="mt-1 text-sm text-zinc-500">Organize o elenco narrativo deste livro.</p>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Novo personagem
          </Button>
        </header>

        {charactersQuery.isLoading ? <LoadingState label="Carregando personagens..." /> : null}

        {charactersQuery.isError ? (
          <ErrorState message="Não foi possível carregar os personagens. Verifique o backend e tente novamente." />
        ) : null}

        {deleteErrorMessage ? <ErrorState message={deleteErrorMessage} /> : null}
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

        {charactersQuery.data ? (
          <div className="grid min-h-[calc(100vh-180px)] gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
            <aside className="min-h-0 min-w-0 lg:max-w-[340px]">
              <CharactersList
                characters={charactersQuery.data}
                selectedCharacterId={detailMode === "edit" ? selectedCharacter?.id ?? null : null}
                deletePendingCharacterId={deleteMutation.variables ?? null}
                onEditCharacter={startEdit}
                onDeleteCharacter={handleDeleteCharacter}
              />
            </aside>

            <div className="min-w-0">
              {detailMode === "empty" ? (
                <EmptyState title="Selecione um personagem para visualizar ou editar." className="h-full" />
              ) : (
                <CharacterForm
                  character={detailMode === "edit" ? selectedCharacter : null}
                  isPending={activeMutation.isPending}
                  errorMessage={errorMessage}
                  successMessage={null}
                  onCancelEdit={clearDetail}
                  onSubmit={handleSubmit}
                />
              )}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function getCharacterErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar o personagem agora.";
  }

  return "Não foi possível salvar o personagem. Verifique a API e tente novamente.";
}
