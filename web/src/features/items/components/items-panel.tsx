"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { useCharacters } from "@/features/characters/api/characters-hooks";
import { useCreateItem, useDeleteItem, useItems, useUpdateItem } from "@/features/items/api/items-hooks";
import { ItemForm } from "@/features/items/components/item-form";
import { ItemsList } from "@/features/items/components/items-list";
import type { ItemRequest, ItemResponse } from "@/features/items/types";
import { ApiError } from "@/lib/api/client";

type ItemsPanelProps = {
  bookId: string;
  openItemIntent?: EntityOpenIntent | null;
};

type DetailMode = "empty" | "create" | "edit";

export type EntityOpenIntent = {
  id: string;
  requestId: number;
};

export function ItemsPanel({ bookId, openItemIntent = null }: ItemsPanelProps) {
  const itemsQuery = useItems(bookId);
  const charactersQuery = useCharacters(bookId);
  const createMutation = useCreateItem(bookId);
  const updateMutation = useUpdateItem(bookId);
  const deleteMutation = useDeleteItem(bookId);
  const [selectedItem, setSelectedItem] = useState<ItemResponse | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("empty");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const consumedOpenRequestIdRef = useRef<number | null>(null);

  const activeMutation = detailMode === "edit" ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getItemErrorMessage(activeMutation.error), [activeMutation.error]);
  const deleteErrorMessage = useMemo(() => getItemErrorMessage(deleteMutation.error), [deleteMutation.error]);
  const charactersErrorMessage = charactersQuery.isError
    ? "Não foi possível carregar os personagens para selecionar o dono atual."
    : null;

  useEffect(() => {
    if (!openItemIntent || consumedOpenRequestIdRef.current === openItemIntent.requestId) {
      return;
    }

    const item = itemsQuery.data?.find((value) => value.id === openItemIntent.id);
    if (!item) {
      return;
    }

    consumedOpenRequestIdRef.current = openItemIntent.requestId;
    startEdit(item);
  }, [itemsQuery.data, openItemIntent]);

  function startCreate() {
    setSelectedItem(null);
    setDetailMode("create");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function startEdit(item: ItemResponse) {
    setSelectedItem(item);
    setDetailMode("edit");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function clearDetail() {
    setSelectedItem(null);
    setDetailMode("empty");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function handleSubmit(payload: ItemRequest) {
    setSuccessMessage(null);

    if (detailMode === "edit" && selectedItem) {
      updateMutation.mutate(
        { itemId: selectedItem.id, payload },
        {
          onSuccess: (item) => {
            setSelectedItem(item);
            setSuccessMessage("Item atualizado com sucesso.");
          },
        },
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (item) => {
        setSelectedItem(item);
        setDetailMode("edit");
        setSuccessMessage("Item criado com sucesso.");
      },
    });
  }

  function handleDeleteItem(item: ItemResponse) {
    const confirmed = window.confirm(`Excluir o item "${item.name}"? Esta ação não pode ser desfeita.`);
    if (!confirmed) {
      return;
    }

    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();

    deleteMutation.mutate(item.id, {
      onSuccess: () => {
        if (selectedItem?.id === item.id) {
          setSelectedItem(null);
          setDetailMode("empty");
        }
        setSuccessMessage("Item excluído com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-zinc-950">Itens</h1>
            <p className="mt-1 text-sm text-zinc-500">Organize objetos, pistas e artefatos deste livro.</p>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Novo item
          </Button>
        </header>

        {itemsQuery.isLoading ? <LoadingState label="Carregando itens..." /> : null}

        {itemsQuery.isError ? (
          <ErrorState message="Não foi possível carregar os itens. Verifique o backend e tente novamente." />
        ) : null}

        {charactersErrorMessage ? <ErrorState message={charactersErrorMessage} /> : null}
        {deleteErrorMessage ? <ErrorState message={deleteErrorMessage} /> : null}
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

        {itemsQuery.data ? (
          <div className="grid min-h-[calc(100vh-180px)] gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
            <aside className="min-h-0 min-w-0 lg:max-w-[340px]">
              <ItemsList
                items={itemsQuery.data}
                characters={charactersQuery.data ?? []}
                selectedItemId={detailMode === "edit" ? selectedItem?.id ?? null : null}
                deletePendingItemId={deleteMutation.variables ?? null}
                onEditItem={startEdit}
                onDeleteItem={handleDeleteItem}
              />
            </aside>

            <div className="min-w-0">
              {detailMode === "empty" ? (
                <EmptyState title="Selecione um item para visualizar ou editar." className="h-full" />
              ) : (
                <ItemForm
                  item={detailMode === "edit" ? selectedItem : null}
                  characters={charactersQuery.data ?? []}
                  charactersPending={charactersQuery.isLoading}
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

function getItemErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar o item agora.";
  }

  return "Não foi possível salvar o item. Verifique a API e tente novamente.";
}
