import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LoginPage } from "@/features/auth/LoginPage";

function renderLogin() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/anmelden", element: <LoginPage /> },
      { path: "/", element: <div>Startseite</div> },
    ],
    { initialEntries: ["/anmelden"] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

function fill() {
  fireEvent.change(screen.getByLabelText("Benutzername oder E-Mail"), { target: { value: "jakob" } });
  fireEvent.change(screen.getByLabelText("Passwort"), { target: { value: "geheim-geheim" } });
  fireEvent.click(screen.getByRole("button", { name: "Anmelden" }));
}

afterEach(() => vi.unstubAllGlobals());

describe("LoginPage", () => {
  it("shows the German backend error on wrong credentials", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(JSON.stringify({ code: "invalid_credentials", detail: "x" }), { status: 401 }),
      ),
    );
    renderLogin();
    fill();
    expect(await screen.findByRole("alert")).toHaveTextContent("Benutzername/E-Mail oder Passwort ist falsch.");
  });

  it("sends credentials as JSON and navigates home on success", async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ id: "1", username: "jakob", display_name: "Jakob" }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderLogin();
    fill();
    expect(await screen.findByText("Startseite")).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/v1/auth/login");
    expect(JSON.parse(init.body as string)).toEqual({ login: "jakob", password: "geheim-geheim" });
  });
});
