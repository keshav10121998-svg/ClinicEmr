import type { Problem } from './types';

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail ?? `Request failed (${problem.status})`);
  }
  get code() {
    return this.problem.code;
  }
}

let token: string | undefined;
let onUnauthorized: () => void = () => {};

export function setToken(t?: string) {
  token = t;
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

function headers(json: boolean): HeadersInit {
  const h: Record<string, string> = {};
  if (json) h['Content-Type'] = 'application/json';
  if (token) h['Authorization'] = `Bearer ${token}`;
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
    throw new ApiError({ status: 0, code: 'NETWORK', detail: "Can't reach ClinicEMR. Check your internet connection." });
  }
  if (res.status === 401 && token) onUnauthorized();
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
  del: <T>(path: string) => request<T>('DELETE', path),
};

/**
 * Opens a PDF. {@code linkPath} makes it and returns a short-lived link; phone browsers can open a real address but
 * not a PDF held in page memory. The tab is opened inside the tap so the browser allows it.
 */
export async function openAuthed(linkPath: string) {
  const tab = window.open('', '_blank');
  try {
    const { url } = await api.post<{ url: string }>(linkPath);
    if (tab) tab.location.href = url;
    else window.location.href = url;
  } catch (e) {
    tab?.close();
    throw e;
  }
}

/**
 * Uploads one page. Relative URLs are this backend (which checks the file and needs the sign-in header);
 * absolute URLs are pre-signed storage URLs that must not get it.
 */
export async function putFile(url: string, contentType: string, file: Blob) {
  const isBackend = url.startsWith('/');
  const res = await fetch(url, {
    method: 'PUT',
    headers: isBackend && token ? { 'Content-Type': contentType, Authorization: `Bearer ${token}` } : { 'Content-Type': contentType },
    body: file,
  });
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = { ...(await res.json()), status: res.status };
    } catch {
      // storage errors aren't JSON
    }
    throw new ApiError(problem);
  }
}

/** Opens a pre-signed storage link (already authorised) in a new tab. */
export function openUrl(url: string) {
  window.open(url, '_blank', 'noopener');
}
