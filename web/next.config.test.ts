import { afterEach, describe, expect, it, vi } from "vitest";
import { resolveBackendOrigin } from "./next.config.mjs";

const LOCAL_FALLBACK = "http://127.0.0.1:8085";

describe("resolveBackendOrigin", () => {
  afterEach(() => {
    delete process.env.BACKEND_ORIGIN;
    delete process.env.NEXT_PUBLIC_API_URL;
    vi.resetModules();
  });

  it("falls back to the local default when neither variable is set", () => {
    expect(resolveBackendOrigin()).toBe(LOCAL_FALLBACK);
  });

  it("prefers BACKEND_ORIGIN over the legacy variable", () => {
    process.env.BACKEND_ORIGIN = "https://api.example.com";
    process.env.NEXT_PUBLIC_API_URL = "https://legacy.example.com";
    expect(resolveBackendOrigin()).toBe("https://api.example.com");
  });

  it("falls back to the deprecated NEXT_PUBLIC_API_URL when BACKEND_ORIGIN is absent", () => {
    process.env.NEXT_PUBLIC_API_URL = "https://legacy.example.com";
    expect(resolveBackendOrigin()).toBe("https://legacy.example.com");
  });

  it("throws instead of silently falling back when BACKEND_ORIGIN is set but invalid", () => {
    process.env.BACKEND_ORIGIN = "not-a-url";
    expect(() => resolveBackendOrigin()).toThrow(/BACKEND_ORIGIN/);
  });

  it("throws instead of silently falling back when the legacy variable is set but invalid", () => {
    process.env.NEXT_PUBLIC_API_URL = "not-a-url";
    expect(() => resolveBackendOrigin()).toThrow(/NEXT_PUBLIC_API_URL/);
  });

  it("rewrites ping and API requests through the same backend origin", async () => {
    process.env.BACKEND_ORIGIN = "https://backend.example.com/base-path";
    const { default: config } = await import("./next.config.mjs");

    await expect(config.rewrites()).resolves.toEqual([
      {
        source: "/api/ping",
        destination: "https://backend.example.com/ping",
      },
      {
        source: "/api/:path*",
        destination: "https://backend.example.com/api/:path*",
      },
    ]);
  });
});
