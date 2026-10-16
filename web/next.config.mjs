import path from "node:path";
import { fileURLToPath } from "node:url";

const LOCAL_FALLBACK = "http://127.0.0.1:8085";
const configDirectory = path.dirname(fileURLToPath(import.meta.url));

/**
 * Server-side only: the browser never learns the backend origin, it only ever calls /api on the
 * origin it was served from. Same-origin is what makes the session work — a SameSite=Lax cookie is
 * not sent on cross-site XHR, and the CSRF double-submit has to read a cookie the browser would
 * otherwise treat as third-party.
 *
 * `NEXT_PUBLIC_API_URL` was the setting documented and deployed before `BACKEND_ORIGIN` existed;
 * it is read here only as a fallback so already-configured deployments keep rewriting to the right
 * host after an upgrade, and it is deprecated — new configuration should set `BACKEND_ORIGIN`. A
 * variable that is set but not a valid absolute URL fails the build instead of silently falling
 * through to localhost, so a typo in production configuration cannot turn into every request
 * quietly rewriting to the frontend container itself.
 */
export function resolveBackendOrigin() {
  for (const name of ["BACKEND_ORIGIN", "NEXT_PUBLIC_API_URL"]) {
    const value = process.env[name];
    if (!value) {
      continue;
    }
    try {
      return new URL(value).origin;
    } catch {
      throw new Error(
        `${name} is set to "${value}", which is not a valid absolute URL. Fix it or unset it — it will not silently fall back to ${LOCAL_FALLBACK}.`,
      );
    }
  }
  return LOCAL_FALLBACK;
}

const BACKEND_ORIGIN = resolveBackendOrigin();

const nextConfig = {
  outputFileTracingRoot: configDirectory,

  async rewrites() {
    return [
      // The backend's public liveness probe is mapped at /ping, not under /api. Exposing it as
      // /api/ping keeps every backend call on one prefix and gives the proxy a probe that needs no
      // session. Must precede the catch-all.
      {
        source: "/api/ping",
        destination: `${BACKEND_ORIGIN}/ping`,
      },
      {
        source: "/api/:path*",
        destination: `${BACKEND_ORIGIN}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
