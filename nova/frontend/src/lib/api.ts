import { errorMessage } from "./errors";

export interface FieldError {
  loc: string[];
  msg: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];

  constructor(status: number, code: string, fieldErrors: FieldError[] = []) {
    super(errorMessage(code));
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }

  /** Name of the first invalid field, e.g. "password". */
  get field(): string | undefined {
    return this.fieldErrors[0]?.loc.at(-1);
  }
}

const BASE = "/api/v1";

export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; signal?: AbortSignal } = {},
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method: options.method ?? "GET",
      credentials: "same-origin",
      headers: options.body !== undefined ? { "Content-Type": "application/json" } : undefined,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") throw err;
    throw new ApiError(0, "network_error");
  }

  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? "internal_error", data?.errors ?? []);
  }
  return data as T;
}

/** Multipart upload of a single file (field name "file"). */
export async function upload<T>(path: string, file: File): Promise<T> {
  const body = new FormData();
  body.append("file", file);
  let res: Response;
  try {
    res = await fetch(BASE + path, { method: "POST", credentials: "same-origin", body });
  } catch {
    throw new ApiError(0, "network_error");
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, data?.code ?? "internal_error", data?.errors ?? []);
  return data as T;
}
