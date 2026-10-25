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
 * Opens a PDF in a new tab. {@code linkPath} is an endpoint that makes the PDF and returns a short-lived link to it;
 * a real address works on phones, where a PDF held in page memory can't be shown. The tab is opened first, inside
 * the tap, so pop-up blockers allow it; if one blocks it anyway, this tab goes to the PDF instead.
 */
export async function openPdf(linkPath: string) {
  const tab = window.open('', '_blank');
  try {
    const { url } = await api.post<{ url: string }>(linkPath);
    if (tab) {
      tab.location.href = url;
    } else {
      window.location.href = url;
    }
  } catch (e) {
    tab?.close();
    window.alert(e instanceof ApiError ? e.message : "The prescription couldn't be opened. Try again.");
  }
}

/**
 * Sends a file's bytes with PUT. A relative upload URL is this backend and gets the usual auth headers; an absolute
 * one is a pre-signed storage URL and gets only the Content-Type.
 */
export async function putFile(url: string, file: Blob, contentType: string) {
  const relative = url.startsWith('/');
  let res: Response;
  try {
    res = await fetch(url, {
      method: 'PUT',
      headers: relative ? { ...(headers(false) as Record<string, string>), 'Content-Type': contentType } : { 'Content-Type': contentType },
      body: file,
    });
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK', detail: "Can't reach the server. Check your connection." });
  }
  if (!res.ok) {
    let problem: Problem = { status: res.status, detail: "The file couldn't be uploaded." };
    try {
      problem = { ...problem, ...(await res.json()), status: res.status };
    } catch {
      // not JSON
    }
    throw new ApiError(problem);
  }
}
