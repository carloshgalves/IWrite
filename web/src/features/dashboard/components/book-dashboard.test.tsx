import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { characterAda, dashboardWithScenes, emptyDashboard, itemKey, locationLibrary } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useBookDashboard: vi.fn(),
  useBookContributions: vi.fn(),
  useCharacter: vi.fn(),
  useLocation: vi.fn(),
  useItem: vi.fn(),
  updateBook: vi.fn(),
  updateWritingGoal: vi.fn(),
}));

vi.mock("@/features/dashboard/api/dashboard-hooks", () => ({
  useBookDashboard: mocks.useBookDashboard,
  useBookContributions: mocks.useBookContributions,
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacter: mocks.useCharacter,
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocation: mocks.useLocation,
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItem: mocks.useItem,
}));

vi.mock("@/features/books/api/books-api", () => ({
  updateBook: mocks.updateBook,
}));

vi.mock("@/features/writing-goal/api/writing-goal-api", () => ({
  updateWritingGoal: mocks.updateWritingGoal,
}));

describe("BookDashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useCharacter.mockReturnValue({ isLoading: false, isError: false, data: characterAda });
    mocks.useLocation.mockReturnValue({ isLoading: false, isError: false, data: locationLibrary });
    mocks.useItem.mockReturnValue({ isLoading: false, isError: false, data: itemKey });
    mocks.useBookContributions.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
        scope: "ALL_CONTRIBUTORS",
        selectedContributor: null,
        availableContributors: [{ userId: "user-1", displayName: "Carlos" }],
        summary: {
          productiveWords: 300,
          manuscriptAdjustments: 100,
          writingDays: 1,
          contributorsCount: 1,
        },
        dailySeries: [
          { date: "2026-05-14", productiveWords: 300, manuscriptAdjustments: 100 },
        ],
      },
    });
    mocks.updateBook.mockResolvedValue({});
    mocks.updateWritingGoal.mockResolvedValue({});
  });

  test("renderiza estado de loading", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: true, isError: false, data: undefined });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText(/Carregando/)).toBeInTheDocument();
  });

  test("renderiza estado de erro", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: true, data: undefined });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText(/backend/)).toBeInTheDocument();
  });

  test("renderiza livro sem cenas e estado sem meta", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: emptyDashboard });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Total de palavras")).toBeInTheDocument();
    expect(screen.getByText("Total de cenas")).toBeInTheDocument();
    expect(screen.getByText(/ainda.*cenas/i)).toBeInTheDocument();
    expect(screen.getByText("Nenhuma meta de palavras definida.")).toBeInTheDocument();
    expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument();
  });

  test("mostra cards principais, meta existente e meta ultrapassada", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Total de palavras")).toBeInTheDocument();
    expect(screen.getByText("1.200 / 1.000 palavras")).toBeInTheDocument();
    expect(screen.getByText("Meta ultrapassada em 200 palavras")).toBeInTheDocument();
    expect(screen.getByText("Hoje: 300 / 500 palavras")).toBeInTheDocument();
    expect(screen.getByText("60% da meta diária")).toBeInTheDocument();
    expect(screen.getByText("Escrita no período")).toBeInTheDocument();
    expect(screen.getByText("Total no período")).toBeInTheDocument();
    expect(screen.getByText("Média por bucket")).toBeInTheDocument();
    expect(screen.getByText("Buckets com escrita")).toBeInTheDocument();
    expect(screen.getByText("Dias em que bateu a meta")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "7 dias" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "15 dias" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "30 dias" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "3 meses" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "6 meses" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "12 meses" })).toBeInTheDocument();
    const chart = screen.getByRole("img", { name: /vertical.*escrita/ });
    const summaryGrid = screen.getByTestId("daily-progress-summary-grid");
    expect(chart.compareDocumentPosition(summaryGrid)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(within(summaryGrid).getByText("Total no período")).toBeInTheDocument();
    expect(within(summaryGrid).getByText("Média por bucket")).toBeInTheDocument();
    expect(within(summaryGrid).getByText("Buckets com escrita")).toBeInTheDocument();
    expect(within(summaryGrid).getByText("Melhor bucket")).toBeInTheDocument();
    expect(within(summaryGrid).getByText("Dias em que bateu a meta")).toBeInTheDocument();
    expect(within(chart).queryByRole("list")).not.toBeInTheDocument();
    expect(screen.getByText("08/05 - 14/05")).toBeInTheDocument();
    expect(within(chart).getByText("14/05")).toBeInTheDocument();
    expect(within(chart).getByText("300")).toBeInTheDocument();
    expect(within(chart).getByText("13/05")).toBeInTheDocument();
    expect(within(chart).queryByText("-100")).not.toBeInTheDocument();
    expect(screen.queryByTestId("manuscript-adjustment-summary")).not.toBeInTheDocument();
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(7);
    expect(screen.getAllByTestId("daily-progress-vertical-bar")).toHaveLength(7);
    expect(screen.getAllByTestId("daily-progress-timeline-dot")).toHaveLength(7);
    expect(screen.getByTestId("daily-progress-x-axis")).toBeInTheDocument();
    expect(screen.queryByTestId("daily-progress-trend-line")).not.toBeInTheDocument();
    expect(screen.getByText("Planejamento narrativo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ver cenas com status Rascunho" })).toBeInTheDocument();
  });

  test("contribuicoes usam escopo de todos por padrao", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(mocks.useBookContributions).toHaveBeenLastCalledWith("book-1", "7d", undefined);
    expect(screen.getByText("Contribuição da equipe")).toBeInTheDocument();
    expect(screen.getByText("Contribuidores")).toBeInTheDocument();
  });

  test("selecionar contribuidor passa o id para o hook e renderiza os dados retornados", async () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });
    mocks.useBookContributions.mockImplementation((_bookId: string, _period: string, contributorId?: string) => ({
      isLoading: false,
      isError: false,
      data: contributorId === "user-2"
        ? {
            period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
            scope: "SINGLE_CONTRIBUTOR",
            selectedContributor: { userId: "user-2", displayName: "Bruna" },
            availableContributors: [
              { userId: "user-1", displayName: "Carlos" },
              { userId: "user-2", displayName: "Bruna" },
            ],
            summary: { productiveWords: 7, manuscriptAdjustments: -2, writingDays: 1, contributorsCount: 1 },
            dailySeries: [{ date: "2026-05-14", productiveWords: 7, manuscriptAdjustments: -2 }],
          }
        : {
            period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
            scope: "ALL_CONTRIBUTORS",
            selectedContributor: null,
            availableContributors: [
              { userId: "user-1", displayName: "Carlos" },
              { userId: "user-2", displayName: "Bruna" },
            ],
            summary: { productiveWords: 30, manuscriptAdjustments: 0, writingDays: 2, contributorsCount: 2 },
            dailySeries: [{ date: "2026-05-14", productiveWords: 30, manuscriptAdjustments: 0 }],
          },
    }));

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.change(screen.getByLabelText("Contribuidor"), { target: { value: "user-2" } });

    await waitFor(() => expect(mocks.useBookContributions).toHaveBeenLastCalledWith("book-1", "7d", "user-2"));
    expect(screen.getByText("7 produtivas · -2 ajustes")).toBeInTheDocument();
  });

  test("contribuicoes vazias exibem estado vazio", () => {
    mocks.useBookDashboard.mockImplementation((bookId: string) => ({
      isLoading: false,
      isError: false,
      data: { ...dashboardWithScenes, bookId },
    }));
    mocks.useBookContributions.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
        scope: "SINGLE_CONTRIBUTOR",
        selectedContributor: { userId: "user-1", displayName: "Carlos" },
        availableContributors: [],
        summary: { productiveWords: 0, manuscriptAdjustments: 0, writingDays: 0, contributorsCount: 0 },
        dailySeries: [],
      },
    });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Nenhuma contribuição registrada no período.")).toBeInTheDocument();
  });

  test("limpa contribuidor selecionado quando o livro muda sem desmontar", async () => {
    mocks.useBookDashboard.mockImplementation((bookId: string) => ({
      isLoading: false,
      isError: false,
      data: { ...dashboardWithScenes, bookId },
    }));
    mocks.useBookContributions.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        period: { value: "7d", startDate: "2026-05-08", endDate: "2026-05-14" },
        scope: "ALL_CONTRIBUTORS",
        selectedContributor: null,
        availableContributors: [
          { userId: "user-1", displayName: "Carlos" },
          { userId: "user-2", displayName: "Bruna" },
        ],
        summary: { productiveWords: 30, manuscriptAdjustments: 0, writingDays: 2, contributorsCount: 2 },
        dailySeries: [{ date: "2026-05-14", productiveWords: 30, manuscriptAdjustments: 0 }],
      },
    });

    const { rerender } = renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.change(screen.getByLabelText("Contribuidor"), { target: { value: "user-2" } });
    await waitFor(() => expect(mocks.useBookContributions).toHaveBeenLastCalledWith("book-1", "7d", "user-2"));

    rerender(<BookDashboard bookId="book-2" />);

    await waitFor(() => expect(mocks.useBookContributions).toHaveBeenLastCalledWith("book-2", "7d", undefined));
    expect(mocks.useBookContributions).not.toHaveBeenCalledWith("book-2", "7d", "user-2");
  });

  test("grafico de produtividade usa apenas palavras produtivas", () => {
    const dashboardWithLargeAdjustment = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        today: {
          ...dashboardWithScenes.myWriting.progress.today,
          productiveWordCountChange: 20,
          manuscriptAdjustmentWordCount: 700,
          progressPercent: 4,
        },
        recentDays: [
          {
            ...dashboardWithScenes.myWriting.progress.today,
            productiveWordCountChange: 20,
            manuscriptAdjustmentWordCount: 700,
            progressPercent: 4,
          },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithLargeAdjustment });

    renderWithClient(<BookDashboard bookId="book-1" />);

    const chart = screen.getByRole("img", { name: /vertical.*escrita/ });
    expect(within(chart).getByText("20")).toBeInTheDocument();
    expect(within(chart).queryByText("720")).not.toBeInTheDocument();
    expect(screen.getByText("Hoje: 20 / 500 palavras")).toBeInTheDocument();
    expect(screen.getByText(/4% da meta di.ria/)).toBeInTheDocument();
  });

  test("mostra resumo de ajustes do manuscrito quando houver ajuste no periodo", () => {
    const dashboardWithAdjustment = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          {
            ...dashboardWithScenes.myWriting.progress.today,
            productiveWordCountChange: 20,
            manuscriptAdjustmentWordCount: 700,
          },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithAdjustment });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByTestId("manuscript-adjustment-summary")).toBeInTheDocument();
    expect(screen.getAllByText("Ajustes do manuscrito").length).toBeGreaterThan(0);
    expect(screen.getByText("Alterações causadas por restaurações, exclusões ou importações. Não contam como produtividade.")).toBeInTheDocument();
    expect(screen.getByText("700 palavras")).toBeInTheDocument();
  });

  test("trocar período mantém analytics visível durante refetch", async () => {
    mocks.useBookDashboard.mockImplementation((_bookId: string, period: string) => ({
      isLoading: false,
      isFetching: period === "30d",
      isError: false,
      data: dashboardWithScenes,
    }));

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getAllByTestId("daily-progress-vertical-bar")).toHaveLength(7);
    fireEvent.click(screen.getByRole("button", { name: "30 dias" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "30d"));
    expect(screen.queryByText("Carregando visão geral...")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: /30 dias/ })).toBeInTheDocument();
    expect(screen.getByText("Atualizando período...")).toBeInTheDocument();
    expect(screen.getAllByTestId("daily-progress-vertical-bar")).toHaveLength(30);
    expect(screen.queryByTestId("daily-progress-trend-line")).not.toBeInTheDocument();
  });

  test("7, 15 e 30 dias renderizam buckets diarios fixos", async () => {
    const staleRecentDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        today: {
          ...dashboardWithScenes.myWriting.progress.today,
          date: "2026-05-14",
          productiveWordCountChange: 0,
          manuscriptAdjustmentWordCount: 0,
        },
        recentDays: [
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-10", productiveWordCountChange: 120, manuscriptAdjustmentWordCount: 0 },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    const thirtyDayDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-12", productiveWordCountChange: 120, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-13", productiveWordCountChange: 220, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-14", productiveWordCountChange: 320, manuscriptAdjustmentWordCount: 0 },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    mocks.useBookDashboard.mockImplementation((_bookId: string, period: string) => ({
      isLoading: false,
      isError: false,
      data: period === "30d" ? thirtyDayDashboard : period === "7d" ? staleRecentDashboard : dashboardWithScenes,
    }));

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(7);
    expect(screen.getByText("08/05 - 14/05")).toBeInTheDocument();
    let chart = screen.getByRole("img", { name: /7 dias/ });
    expect(within(chart).getByText("10/05")).toBeInTheDocument();
    expect(within(chart).getByText("14/05")).toBeInTheDocument();
    expect(within(chart).getByText("120")).toBeInTheDocument();
    expect(within(chart).getAllByText("0").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("button", { name: "15 dias" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "15d"));
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(15);
    expect(screen.getByText("30/04 - 14/05")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "30 dias" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "30d"));
    await waitFor(() => expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(30));
    expect(screen.getByText("15/04 - 14/05")).toBeInTheDocument();
    chart = screen.getByRole("img", { name: /30 dias/ });
    expect(screen.getByTestId("daily-progress-chart-grid")).toHaveStyle({
      gridTemplateColumns: "repeat(30, minmax(0, 1fr))",
    });
    expect(within(chart).getByText("14/05")).toBeInTheDocument();
    expect(within(chart).getAllByText("0").length).toBeGreaterThan(0);
  });

  test("3, 6 e 12 meses renderizam buckets mensais fixos", async () => {
    const threeMonthDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-14", productiveWordCountChange: 300, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-05-01", productiveWordCountChange: 200, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-04-20", productiveWordCountChange: 100, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-03-10", productiveWordCountChange: 50, manuscriptAdjustmentWordCount: 0 },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    const sixMonthDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          ...threeMonthDashboard.myWriting.progress.recentDays,
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-02-07", productiveWordCountChange: 40, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-01-03", productiveWordCountChange: 30, manuscriptAdjustmentWordCount: 0 },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    const twelveMonthDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
        ...dashboardWithScenes.myWriting.progress,
        recentDays: [
          { ...dashboardWithScenes.myWriting.progress.today, date: "2026-04-20", productiveWordCountChange: 100, manuscriptAdjustmentWordCount: 0 },
          { ...dashboardWithScenes.myWriting.progress.today, date: "2025-06-15", productiveWordCountChange: 10, manuscriptAdjustmentWordCount: 0 },
        ],
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    mocks.useBookDashboard.mockImplementation((_bookId: string, period: string) => ({
      isLoading: false,
      isError: false,
      data: period === "12m" ? twelveMonthDashboard : period === "6m" ? sixMonthDashboard : period === "3m" ? threeMonthDashboard : dashboardWithScenes,
    }));

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "3 meses" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "3m"));
    expect(screen.getByText("mar./2026 - mai./2026")).toBeInTheDocument();
    let chart = screen.getByRole("img", { name: /3 meses/ });
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(3);
    expect(within(chart).getByText("mar.")).toBeInTheDocument();
    expect(within(chart).getByText("abr.")).toBeInTheDocument();
    expect(within(chart).getByText("mai.")).toBeInTheDocument();
    expect(within(chart).getByText("500")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "6 meses" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "6m"));
    expect(screen.getByText("dez./2025 - mai./2026")).toBeInTheDocument();
    chart = screen.getByRole("img", { name: /6 meses/ });
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(6);
    expect(within(chart).getByText("dez.")).toBeInTheDocument();
    expect(within(chart).getByText("jan.")).toBeInTheDocument();
    expect(within(chart).getByText("mai.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "12 meses" }));

    await waitFor(() => expect(mocks.useBookDashboard).toHaveBeenLastCalledWith("book-1", "12m"));
    expect(screen.getByText("jun./2025 - mai./2026")).toBeInTheDocument();
    chart = screen.getByRole("img", { name: /12 meses/ });
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(12);
    expect(within(chart).getByText("jun.")).toBeInTheDocument();
    expect(within(chart).getByText("mai.")).toBeInTheDocument();
  });

  test("salvar meta diária troca estado vazio por progresso diário", async () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: emptyDashboard });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Definir meta diária" }));
    fireEvent.change(screen.getByLabelText("Meta diária de palavras"), { target: { value: "750" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar meta diária" }));

    await waitFor(() => expect(mocks.updateWritingGoal).toHaveBeenCalledWith("book-1", { dailyTargetWordCount: 750 }));
    expect(mocks.updateBook).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText("Nenhuma meta diária definida.")).not.toBeInTheDocument());
    expect(screen.getByText("Hoje: 0 / 750 palavras")).toBeInTheDocument();
  });

  test("mostra resumo da rotina e salva predefinicao de dias uteis", async () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("7 dias/semana")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Editar rotina" }));
    fireEvent.click(screen.getByRole("button", { name: "Dias uteis" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar rotina" }));

    await waitFor(() => expect(mocks.updateWritingGoal).toHaveBeenCalledWith("book-1", {
      plannedWritingDays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    }));
    expect(mocks.updateBook).not.toHaveBeenCalled();
    expect(screen.getByText(/mudanca passa a valer amanha/i)).toBeInTheDocument();
  });

  test("rotina personalizada exige pelo menos um dia selecionado", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Editar rotina" }));
    for (const label of ["Seg", "Ter", "Qua", "Qui", "Sex", "Sab", "Dom"]) {
      fireEvent.click(screen.getByRole("button", { name: label }));
    }
    fireEvent.click(screen.getByRole("button", { name: "Salvar rotina" }));

    expect(screen.getByText("Selecione pelo menos um dia de escrita.")).toBeInTheDocument();
    expect(mocks.updateWritingGoal).not.toHaveBeenCalled();
  });

  test("mostra dia de descanso com progresso extra", () => {
    const restDayDashboard = {
      ...dashboardWithScenes,
      myWriting: {
        progress: {
          ...dashboardWithScenes.myWriting.progress,
          today: {
            ...dashboardWithScenes.myWriting.progress.today,
            productiveWordCountChange: 120,
            manuscriptAdjustmentWordCount: 0,
          },
        },
        schedule: {
          plannedWritingDays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
          plannedWritingDaysPerWeek: 5,
          restDays: ["SATURDAY", "SUNDAY"],
          todayPlannedWritingDay: false,
          currentScheduleEffectiveFrom: "2026-05-14",
        },
      },
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: restDayDashboard });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText(/5 dias\/semana/)).toBeInTheDocument();
    expect(screen.getByText("Hoje e um dia de descanso planejado.")).toBeInTheDocument();
    expect(screen.getByText("Extra hoje: 120 palavras")).toBeInTheDocument();
    expect(screen.queryByText("Hoje: 120 / 500 palavras")).not.toBeInTheDocument();
  });

  test("remover meta diária volta para estado sem meta", async () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Editar meta diária" }));
    fireEvent.click(screen.getByRole("button", { name: "Remover meta diária" }));

    await waitFor(() => expect(mocks.updateWritingGoal).toHaveBeenCalledWith("book-1", { dailyTargetWordCount: null }));
    await waitFor(() => expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument());
  });

  test("nao oferece controles de meta quando as capabilities nao autorizam", () => {
    // An Editor sees the book dashboard but manages no personal goal and no shared book setting.
    const editorDashboard = {
      ...dashboardWithScenes,
      dailyTargetWordCount: null,
      capabilities: ["READ_MANUSCRIPT", "VIEW_BOOK_CONTRIBUTOR_PROGRESS"] as const,
      contextualCapabilities: [],
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: editorDashboard });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.queryByRole("button", { name: "Editar rotina" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Definir meta diária" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar meta diária" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar meta" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Definir meta" })).not.toBeInTheDocument();
    // Absence of a target is stated as an absence, never as failure or zero performance.
    expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument();
    expect(
      screen.getByText("Sem meta diária não significa progresso zero: ela é opcional e pessoal.")
    ).toBeInTheDocument();
  });

  test("meta diária removida não reaparece a partir do snapshot histórico de hoje", () => {
    const dashboardWithRemovedCurrentGoal = {
      ...dashboardWithScenes,
      dailyTargetWordCount: null,
      myWriting: {
        progress: {
          ...dashboardWithScenes.myWriting.progress,
          today: {
            ...dashboardWithScenes.myWriting.progress.today,
            dailyTargetWordCount: 500,
          },
        },
        schedule: dashboardWithScenes.myWriting.schedule,
      },
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithRemovedCurrentGoal });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument();
    expect(screen.getByText("Hoje: 300 palavras")).toBeInTheDocument();
    expect(screen.queryByText("Hoje: 300 / 500 palavras")).not.toBeInTheDocument();
  });

  test("clicar em card de status abre modal e clicar em cena troca o conteudo do mesmo modal", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Ver cenas com status Rascunho" }));

    let dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Cenas em Rascunho")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /A chave aparece/ })).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /A chave aparece/ }));

    dialog = screen.getByRole("dialog");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(within(dialog).getAllByRole("heading", { name: "A chave aparece" })).toHaveLength(2);
    expect(within(dialog).getByText("Encontrar a chave")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Abrir no editor" })).toBeInTheDocument();
  });

  test("clicar em lacuna abre lista de cenas afetadas", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: /Sem objetivo/ }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Cenas sem objetivo")).toBeInTheDocument();
    expect(within(dialog).getByText("1 cenas afetadas.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Cena sem objetivo/ })).toBeInTheDocument();
  });

  test("nao renderiza o dashboard de outro livro enquanto o livro atual carrega", () => {
    // keepPreviousData can hand back the previous Book's snapshot while the new Book still loads.
    mocks.useBookDashboard.mockReturnValue({
      isLoading: false,
      isFetching: true,
      isError: false,
      data: { ...dashboardWithScenes, bookId: "book-1", title: "Livro anterior" },
    });

    renderWithClient(<BookDashboard bookId="book-2" />);

    expect(screen.getByText("Carregando visão geral...")).toBeInTheDocument();
    expect(screen.queryByText("Progresso do manuscrito")).not.toBeInTheDocument();
  });

  test("troca de livro com rascunho de meta aberto descarta o editor e nunca grava no livro errado", async () => {
    mocks.useBookDashboard.mockImplementation((bookId: string) => ({
      isLoading: false,
      isError: false,
      data: { ...dashboardWithScenes, bookId },
    }));

    const { rerender } = renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Editar meta diária" }));
    fireEvent.change(screen.getByLabelText("Meta diária de palavras"), { target: { value: "9999" } });
    expect(screen.getByRole("button", { name: "Salvar meta diária" })).toBeInTheDocument();

    rerender(<BookDashboard bookId="book-2" />);

    expect(screen.queryByRole("button", { name: "Salvar meta diária" })).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("9999")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Editar meta diária" }));
    expect(screen.getByLabelText("Meta diária de palavras")).toHaveValue(500);
    fireEvent.change(screen.getByLabelText("Meta diária de palavras"), { target: { value: "750" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar meta diária" }));

    await waitFor(() =>
      expect(mocks.updateWritingGoal).toHaveBeenCalledWith("book-2", { dailyTargetWordCount: 750 })
    );
    expect(mocks.updateWritingGoal).not.toHaveBeenCalledWith("book-2", { dailyTargetWordCount: 9999 });
    expect(mocks.updateWritingGoal).not.toHaveBeenCalledWith("book-1", expect.anything());
  });

  test("perda de capability fecha um editor de meta ja aberto", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    const { rerender } = renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Editar meta diária" }));
    expect(screen.getByRole("button", { name: "Salvar meta diária" })).toBeInTheDocument();

    mocks.useBookDashboard.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        ...dashboardWithScenes,
        dailyTargetWordCount: null,
        capabilities: ["READ_MANUSCRIPT", "VIEW_BOOK_CONTRIBUTOR_PROGRESS"],
        contextualCapabilities: [],
      },
    });
    rerender(<BookDashboard bookId="book-1" />);

    expect(screen.queryByRole("button", { name: "Salvar meta diária" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar meta diária" })).not.toBeInTheDocument();
  });

  test("clicar em personagem, localizacao e item abre seus detalhes", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getAllByRole("button", { name: /Ada/ })[0]);
    let dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Cenas como POV")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Ver em Personagens" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Fechar" }));

    fireEvent.click(screen.getByRole("button", { name: /Biblioteca/ }));
    dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/Cenas como localiza/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Ver em Localizações" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Fechar" }));

    fireEvent.click(screen.getByRole("button", { name: /Chave de prata/ }));
    const itemDialog = screen.getByRole("dialog");
    expect(within(itemDialog).getByText("Dono atual")).toBeInTheDocument();
    expect(within(itemDialog).getByText("Ada")).toBeInTheDocument();
    expect(within(itemDialog).getByRole("button", { name: "Ver em Itens" })).toBeInTheDocument();
    expect(within(itemDialog).queryByText(characterAda.id)).not.toBeInTheDocument();
  });
});
