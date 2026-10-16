import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { OutlineLocalPlaceholder, SceneLocalPlaceholder } from "@/features/workspace/components/workspace-local-placeholder";

describe("authenticated local workspace loading states (#250)", () => {
  test("outline loading retains sidebar geometry without exposing a previous book", () => {
    render(<OutlineLocalPlaceholder state="loading" />);
    const sidebar = screen.getByRole("complementary", { name: "Esboço do livro" });
    expect(sidebar).toHaveAttribute("aria-busy", "true");
    expect(sidebar).toHaveClass("h-full", "flex-col");
    expect(screen.getByRole("status")).toHaveTextContent("Carregando outline...");
    expect(screen.queryByRole("button", { name: "Tentar novamente" })).not.toBeInTheDocument();
    expect(sidebar.querySelector("[aria-hidden='true']")).not.toBeNull();
    expect(sidebar).not.toHaveTextContent("Manuscrito de outro usuário");
  });

  test("outline error has an accessible retry without showing stale domain content", () => {
    const onRetry = vi.fn();
    render(<OutlineLocalPlaceholder state="error" onRetry={onRetry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Não foi possível carregar o outline");
    fireEvent.click(screen.getByRole("button", { name: "Tentar novamente" }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(screen.queryByText("Conteúdo antigo")).not.toBeInTheDocument();
  });

  test("empty outline and scene remain localized with no retry storm", () => {
    const view = render(<OutlineLocalPlaceholder state="empty" />);
    expect(screen.getByRole("status")).toHaveTextContent("Outline indisponível");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    view.rerender(<SceneLocalPlaceholder state="empty" />);
    expect(screen.getByRole("region", { name: "Área da cena" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("A cena solicitada não está disponível");
  });

  test("scene loading preserves pane and body dimensions, with no fabricated edit controls", () => {
    const view = render(<SceneLocalPlaceholder state="loading" label="Preparando editor..." />);
    const scene = screen.getByRole("region", { name: "Área da cena" });
    expect(scene).toHaveAttribute("aria-busy", "true");
    expect(scene).toHaveClass("h-full");
    expect(screen.getByRole("status")).toHaveTextContent("Preparando editor...");
    expect(screen.queryByRole("button", { name: /Salvar/ })).not.toBeInTheDocument();

    view.rerender(<SceneLocalPlaceholder state="error" onRetry={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Não foi possível carregar a cena");
    expect(screen.getByRole("region", { name: "Área da cena" })).toHaveAttribute("aria-busy", "false");
  });
});
