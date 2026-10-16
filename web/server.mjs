// Custom server instead of `next start`: this process terminates the browser's TCP connection
// directly (nothing sits in front of it in either deployment), so the socket peer is the real
// client. next.config.mjs's rewrites() forward request headers as-is when proxying /api to the
// backend but never set a forwarding header themselves, so without this the backend would see
// every browser request as coming from this container.
//
// The browser is never a proxy here, so every forwarding header it sends is forged. Both forms are
// deleted before Next sees them - never merged, appended to, or passed through. ClientAddressResolver
// gives `Forwarded` priority over `X-Forwarded-For`, so leaving `Forwarded` in place would let an
// unauthenticated caller pick a fresh rate-limit bucket on every request just by varying it.
import { createServer } from "node:http";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// The Next app lives next to this file rather than at the process's cwd: /app in web/Dockerfile,
// /app/frontend in the combined root image, whose start.sh runs from /app.
const appDir = path.dirname(fileURLToPath(import.meta.url));

/**
 * Replaces every client-supplied forwarding header with a single canonical `X-Forwarded-For` built
 * from the socket peer. A peer with no address (a socket already closed, or not an IP socket) gets
 * no header at all rather than an invented value: the backend then falls back to its own peer, so
 * those requests share one bucket - restrictive, and still not chooseable by the caller.
 */
export function applyClientAddress(headers, remoteAddress) {
  delete headers["forwarded"];
  delete headers["x-forwarded-for"];
  if (remoteAddress) {
    headers["x-forwarded-for"] = remoteAddress;
  }
  return headers;
}

/** Exported so tests exercise the real wiring, not a copy of it. */
export function requestListener(handle) {
  return (req, res) => {
    applyClientAddress(req.headers, req.socket.remoteAddress);
    handle(req, res);
  };
}

async function startServer() {
  const port = Number(process.env.PORT) || 3000;
  const { default: next } = await import("next");
  const app = next({ dev: process.env.NODE_ENV !== "production", dir: appDir });
  await app.prepare();
  createServer(requestListener(app.getRequestHandler())).listen(port, () => {
    console.log(`> Ready on port ${port}`);
  });
}

// Importing this file (the tests do) must not bind a port or boot Next.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  startServer();
}
