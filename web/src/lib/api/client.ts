// Relative on purpose. Every call goes to /api on the origin that served the page, and Next
// rewrites it to the backend (see next.config.mjs). The browser therefore never makes a cross-site
// request, which is what lets the SameSite=Lax session cookie ride along and lets the CSRF
// double-submit read its own cookie.
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_PATH = "/api/auth/csrf";

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

type RequestOptions = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
};

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = {};

  if (options.body) {
    headers["Content-Type"] = "application/json";
  }

  if (method !== "GET") {
    headers[CSRF_HEADER] = await requireCsrfToken();
  }

  const response = await fetch(path, {
    method,
    headers: Object.keys(headers).length > 0 ? headers : undefined,
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: options.signal,
    credentials: "same-origin",
  });

  if (!response.ok) {
    const message = await readErrorMessage(response);
    throw new ApiError(message, response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export async function readErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { messages?: string[]; error?: string };
    return data.messages?.join(", ") ?? data.error ?? `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}

/**
 * The backend issues the token as a readable cookie and expects it echoed as a header. Fetching
 * {@link CSRF_PATH} is what mints it, so an unauthenticated first mutation (the login itself) still
 * has one.
 */
async function requireCsrfToken(): Promise<string> {
  const existing = readCsrfCookie();
  if (existing) {
    return existing;
  }

  await fetch(CSRF_PATH, { credentials: "same-origin" });
  return readCsrfCookie() ?? "";
}

function readCsrfCookie(): string | undefined {
  const match = document.cookie.split("; ").find((entry) => entry.startsWith(`${CSRF_COOKIE}=`));
  return match ? decodeURIComponent(match.slice(CSRF_COOKIE.length + 1)) : undefined;
}
