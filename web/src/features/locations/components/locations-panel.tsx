"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import {
  useCreateLocation,
  useDeleteLocation,
  useLocations,
  useUpdateLocation,
} from "@/features/locations/api/locations-hooks";
import { LocationForm } from "@/features/locations/components/location-form";
import { LocationsList } from "@/features/locations/components/locations-list";
import type { LocationRequest, LocationResponse } from "@/features/locations/types";
import { ApiError } from "@/lib/api/client";

type LocationsPanelProps = {
  bookId: string;
  openLocationIntent?: EntityOpenIntent | null;
};

type DetailMode = "empty" | "create" | "edit";

export type EntityOpenIntent = {
  id: string;
  requestId: number;
};

export function LocationsPanel({ bookId, openLocationIntent = null }: LocationsPanelProps) {
  const locationsQuery = useLocations(bookId);
  const createMutation = useCreateLocation(bookId);
  const updateMutation = useUpdateLocation(bookId);
  const deleteMutation = useDeleteLocation(bookId);
  const [selectedLocation, setSelectedLocation] = useState<LocationResponse | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("empty");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const consumedOpenRequestIdRef = useRef<number | null>(null);

  const activeMutation = detailMode === "edit" ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getLocationErrorMessage(activeMutation.error), [activeMutation.error]);
  const deleteErrorMessage = useMemo(() => getLocationErrorMessage(deleteMutation.error), [deleteMutation.error]);

  useEffect(() => {
    if (!openLocationIntent || consumedOpenRequestIdRef.current === openLocationIntent.requestId) {
      return;
    }

    const location = locationsQuery.data?.find((value) => value.id === openLocationIntent.id);
    if (!location) {
      return;
    }

    consumedOpenRequestIdRef.current = openLocationIntent.requestId;
    startEdit(location);
  }, [locationsQuery.data, openLocationIntent]);

  function startCreate() {
    setSelectedLocation(null);
    setDetailMode("create");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function startEdit(location: LocationResponse) {
    setSelectedLocation(location);
    setDetailMode("edit");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function clearDetail() {
    setSelectedLocation(null);
    setDetailMode("empty");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function handleSubmit(payload: LocationRequest) {
    setSuccessMessage(null);

    if (detailMode === "edit" && selectedLocation) {
      updateMutation.mutate(
        { locationId: selectedLocation.id, payload },
        {
          onSuccess: (location) => {
            setSelectedLocation(location);
            setSuccessMessage("Localização atualizada com sucesso.");
          },
        },
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (location) => {
        setSelectedLocation(location);
        setDetailMode("edit");
        setSuccessMessage("Localização criada com sucesso.");
      },
    });
  }

  function handleDeleteLocation(location: LocationResponse) {
    const confirmed = window.confirm(`Excluir a localização "${location.name}"? Esta ação não pode ser desfeita.`);
    if (!confirmed) {
      return;
    }

    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();

    deleteMutation.mutate(location.id, {
      onSuccess: () => {
        if (selectedLocation?.id === location.id) {
          setSelectedLocation(null);
          setDetailMode("empty");
        }
        setSuccessMessage("Localização excluída com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-zinc-950">Localizações</h1>
            <p className="mt-1 text-sm text-zinc-500">Organize os cenários e espaços narrativos deste livro.</p>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Nova localização
          </Button>
        </header>

        {locationsQuery.isLoading ? <LoadingState label="Carregando localizações..." /> : null}

        {locationsQuery.isError ? (
          <ErrorState message="Não foi possível carregar as localizações. Verifique o backend e tente novamente." />
        ) : null}

        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}
        {deleteErrorMessage ? <ErrorState message={deleteErrorMessage} /> : null}

        {locationsQuery.data ? (
          <div className="grid min-h-[calc(100vh-180px)] gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
            <aside className="min-h-0 min-w-0 lg:max-w-[340px]">
              <LocationsList
                locations={locationsQuery.data}
                selectedLocationId={detailMode === "edit" ? selectedLocation?.id ?? null : null}
                deletePendingLocationId={deleteMutation.variables ?? null}
                onEditLocation={startEdit}
                onDeleteLocation={handleDeleteLocation}
              />
            </aside>

            <div className="min-w-0">
              {detailMode === "empty" ? (
                <EmptyState title="Selecione uma localização para visualizar ou editar." className="h-full" />
              ) : (
                <LocationForm
                  location={detailMode === "edit" ? selectedLocation : null}
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

function getLocationErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar a localização agora.";
  }

  return "Não foi possível salvar a localização. Verifique a API e tente novamente.";
}
