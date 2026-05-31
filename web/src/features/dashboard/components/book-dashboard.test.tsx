import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { characterAda, dashboardWithScenes, emptyDashboard, itemKey, locationLibrary } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useBookDashboard: vi.fn(),
  useCharacter: vi.fn(),
  useLocation: vi.fn(),
  useItem: vi.fn(),
  updateBook: vi.fn(),
}));

vi.mock("@/features/dashboard/api/dashboard-hooks", () => ({
  useBookDashboard: mocks.useBookDashboard,
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

describe("BookDashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useCharacter.mockReturnValue({ isLoading: false, isError: false, data: characterAda });
    mocks.useLocation.mockReturnValue({ isLoading: false, isError: false, data: locationLibrary });
    mocks.useItem.mockReturnValue({ isLoading: false, isError: false, data: itemKey });
    mocks.updateBook.mockResolvedValue({});
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
    const consistency = screen.getByRole("region", { name: "Consistência" });
    expect(within(consistency).getByText("Sequência atual")).toBeInTheDocument();
    expect(within(consistency).getByText("Melhor sequência")).toBeInTheDocument();
    expect(within(consistency).getByText("Dias escritos no mês")).toBeInTheDocument();
    expect(within(consistency).getByText("Últimos 7 dias")).toBeInTheDocument();
    expect(within(consistency).getAllByText("0 dias")).toHaveLength(2);
    expect(within(consistency).getByText("0 / 7 dias")).toBeInTheDocument();
    expect(within(consistency).getAllByText("0%").length).toBeGreaterThan(0);
  });

  test("mostra cards principais, meta existente e meta ultrapassada", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Total de palavras")).toBeInTheDocument();
    expect(screen.getByText("1.200 / 1.000 palavras")).toBeInTheDocument();
    expect(screen.getByText("Meta ultrapassada em 200 palavras")).toBeInTheDocument();
    expect(screen.getByText("Hoje: 300 / 500 palavras")).toBeInTheDocument();
    expect(screen.getByText("60% da meta diária")).toBeInTheDocument();
    const consistency = screen.getByRole("region", { name: "Consistência" });
    expect(within(consistency).getByText("Sequência atual")).toBeInTheDocument();
    expect(within(consistency).getByText("3 dias")).toBeInTheDocument();
    expect(within(consistency).getByText("Melhor sequência")).toBeInTheDocument();
    expect(within(consistency).getByText("12 dias")).toBeInTheDocument();
    expect(within(consistency).getByText("Dias escritos no mês")).toBeInTheDocument();
    expect(within(consistency).getByText("9")).toBeInTheDocument();
    expect(within(consistency).getByText("Últimos 7 dias")).toBeInTheDocument();
    expect(within(consistency).getByText("5 / 7 dias")).toBeInTheDocument();
    expect(within(consistency).getAllByText("71%").length).toBeGreaterThan(0);
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
    expect(within(chart).getByText("-100")).toBeInTheDocument();
    expect(screen.getAllByTestId("daily-progress-bucket")).toHaveLength(7);
    expect(screen.getAllByTestId("daily-progress-vertical-bar")).toHaveLength(7);
    expect(screen.getAllByTestId("daily-progress-timeline-dot")).toHaveLength(7);
    expect(screen.getByTestId("daily-progress-x-axis")).toBeInTheDocument();
    expect(screen.queryByTestId("daily-progress-trend-line")).not.toBeInTheDocument();
    expect(screen.getByText("Planejamento narrativo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ver cenas com status Rascunho" })).toBeInTheDocument();
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
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        today: {
          ...dashboardWithScenes.writingProgress.today,
          date: "2026-05-14",
          netWordCountChange: 0,
        },
        recentDays: [
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-10", netWordCountChange: 120 },
        ],
      },
    };
    const thirtyDayDashboard = {
      ...dashboardWithScenes,
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        recentDays: [
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-12", netWordCountChange: 120 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-13", netWordCountChange: 220 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-14", netWordCountChange: 320 },
        ],
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
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        recentDays: [
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-14", netWordCountChange: 300 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-05-01", netWordCountChange: 200 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-04-20", netWordCountChange: 100 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-03-10", netWordCountChange: 50 },
        ],
      },
    };
    const sixMonthDashboard = {
      ...dashboardWithScenes,
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        recentDays: [
          ...threeMonthDashboard.writingProgress.recentDays,
          { ...dashboardWithScenes.writingProgress.today, date: "2026-02-07", netWordCountChange: 40 },
          { ...dashboardWithScenes.writingProgress.today, date: "2026-01-03", netWordCountChange: 30 },
        ],
      },
    };
    const twelveMonthDashboard = {
      ...dashboardWithScenes,
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        recentDays: [
          { ...dashboardWithScenes.writingProgress.today, date: "2026-04-20", netWordCountChange: 100 },
          { ...dashboardWithScenes.writingProgress.today, date: "2025-06-15", netWordCountChange: 10 },
        ],
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

    await waitFor(() => expect(mocks.updateBook).toHaveBeenCalledWith("book-1", { dailyTargetWordCount: 750 }));
    await waitFor(() => expect(screen.queryByText("Nenhuma meta diária definida.")).not.toBeInTheDocument());
    expect(screen.getByText("Hoje: 0 / 750 palavras")).toBeInTheDocument();
  });

  test("remover meta diária volta para estado sem meta", async () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Editar meta diária" }));
    fireEvent.click(screen.getByRole("button", { name: "Remover meta diária" }));

    await waitFor(() => expect(mocks.updateBook).toHaveBeenCalledWith("book-1", { dailyTargetWordCount: null }));
    await waitFor(() => expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument());
  });

  test("meta diária removida não reaparece a partir do snapshot histórico de hoje", () => {
    const dashboardWithRemovedCurrentGoal = {
      ...dashboardWithScenes,
      dailyTargetWordCount: null,
      writingProgress: {
        ...dashboardWithScenes.writingProgress,
        today: {
          ...dashboardWithScenes.writingProgress.today,
          dailyTargetWordCount: 500,
        },
      },
    };
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithRemovedCurrentGoal });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Nenhuma meta diária definida.")).toBeInTheDocument();
    expect(screen.getByText("Hoje: 300 palavras")).toBeInTheDocument();
    expect(screen.queryByText("Hoje: 300 / 500 palavras")).not.toBeInTheDocument();
    const consistency = screen.getByRole("region", { name: "Consistência" });
    expect(within(consistency).getByText("Sequência atual")).toBeInTheDocument();
    expect(within(consistency).getByText("3 dias")).toBeInTheDocument();
    expect(within(consistency).getByText("5 / 7 dias")).toBeInTheDocument();
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
