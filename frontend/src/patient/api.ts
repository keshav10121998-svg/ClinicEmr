import type { Problem } from './types';
import { showPdf } from '../shared/pdf/PdfViewer';

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
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
};

/**
 * Shows a PDF inside the app first, with Print, Download and Share on that screen (phones would otherwise just
 * download it). {@code linkPath} makes the PDF and returns a short-lived link to it.
 */
export function openAuthed(linkPath: string, title = 'Prescription') {
  showPdf({
    title,
    filename: `${title.toLowerCase().replace(/\s+/g, '-')}.pdf`,
    getUrls: async () => {
      try {
        return [(await api.post<{ url: string }>(linkPath)).url];
      } catch (e) {
        throw new Error(e instanceof ApiError ? e.message : "The prescription couldn't be opened.");
      }
    },
  });
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

/** Sends a form with a file (multipart). The browser sets the Content-Type with its boundary. */
export async function postForm<T>(path: string, form: FormData): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, { method: 'POST', headers: headers(false), body: form });
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK', detail: "Can't reach ClinicEMR. Check your internet connection." });
  }
  if (res.status === 401 && token) onUnauthorized();
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = { ...(await res.json()), status: res.status };
    } catch {
      // not JSON (e.g. the file was too big for the server)
      if (res.status === 413) problem = { status: 413, detail: 'The file is too big. Send a smaller screenshot.' };
    }
    throw new ApiError(problem);
  }
  return (await res.json()) as T;
}
