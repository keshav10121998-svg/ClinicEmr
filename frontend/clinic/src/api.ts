import type { Problem } from './types';

/** An error response from the API, carrying the server's problem details (code, fields, candidates…). */
export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail ?? `Request failed (${problem.status})`);
  }
  get code() {
    return this.problem.code;
  }
  get status() {
    return this.problem.status;
  }
}

interface Auth {
  token?: string;
  clinicId?: number;
}

let auth: Auth = {};
let onUnauthorized: () => void = () => {};

export function setAuth(next: Auth) {
  auth = next;
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

function headers(json: boolean): HeadersInit {
  const h: Record<string, string> = {};
  if (json) h['Content-Type'] = 'application/json';
  if (auth.token) h['Authorization'] = `Bearer ${auth.token}`;
  if (auth.clinicId) h['X-Clinic-Id'] = String(auth.clinicId);
  return h;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, {
      method,
      headers: headers(body !== undefined),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK', detail: "Can't reach the server. Check your connection." });
  }
  if (res.status === 401 && auth.token) {
    onUnauthorized();
  }
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = { ...(await res.json()), status: res.status };
    } catch {
      // not JSON
    }
    throw new ApiError(problem);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
};

/**
 * Opens a PDF that needs auth headers. The tab is opened first, inside the click, so popup blockers allow it.
 */
export async function openPdf(path: string) {
  const tab = window.open('', '_blank');
  try {
    const res = await fetch(`/api/v1${path}`, { headers: headers(false) });
    if (!res.ok) throw new ApiError({ status: res.status, detail: "The prescription couldn't be generated." });
    const url = URL.createObjectURL(await res.blob());
    if (tab) {
      tab.location.href = url;
    } else {
      window.location.href = url;
    }
  } catch (e) {
    tab?.close();
    throw e;
  }
}
