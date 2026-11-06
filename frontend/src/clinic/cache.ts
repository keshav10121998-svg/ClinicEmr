import { useEffect, useState } from 'react';
import { api, ApiError, currentClinicId } from './api';

/**
 * Lists shown at once from the last copy kept on this phone, then refreshed from the server, so screens never wait
 * on an empty "Loading…". Kept per clinic, and wiped on Sign out or when someone else signs in on the phone.
 */
const PREFIX = 'clinic-emr-cache:';
const OWNER = 'clinic-emr-cache-owner';

function keys() {
  const out: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k?.startsWith(PREFIX)) out.push(k);
  }
  return out;
}

/** Wipes the kept copies unless they belong to this staff member (none signed in: always wiped). */
export function setCacheOwner(staffId?: number) {
  try {
    const owner = staffId === undefined ? '' : String(staffId);
    if (localStorage.getItem(OWNER) === owner && owner) return;
    keys().forEach((k) => localStorage.removeItem(k));
    if (owner) localStorage.setItem(OWNER, owner);
    else localStorage.removeItem(OWNER);
  } catch {
    // storage unavailable: nothing is kept
  }
}

function key(path: string) {
  return `${PREFIX}${currentClinicId() ?? 0}:${path}`;
}

function read<T>(path: string): T | undefined {
  try {
    const raw = localStorage.getItem(key(path));
    return raw ? (JSON.parse(raw) as T) : undefined;
  } catch {
    return undefined;
  }
}

function write(path: string, data: unknown) {
  try {
    localStorage.setItem(key(path), JSON.stringify(data));
  } catch {
    // full or unavailable: the screen still works, just without the instant copy next time
  }
}

/**
 * GETs {@code path}, showing the kept copy first. {@code refreshing} is true while a kept copy is shown and the
 * server hasn't answered yet. Change {@code version} to fetch again.
 */
export function useCached<T>(path: string | undefined, fallbackError: string, version = 0) {
  const [data, setData] = useState<T | undefined>(() => (path ? read<T>(path) : undefined));
  const [refreshing, setRefreshing] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!path) { setData(undefined); return; }
    let cancelled = false;
    setData(read<T>(path));
    setRefreshing(true);
    setError(undefined);
    api.get<T>(path)
      .then((d) => {
        if (cancelled) return;
        setData(d);
        write(path, d);
      })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : fallbackError); })
      .finally(() => { if (!cancelled) setRefreshing(false); });
    return () => { cancelled = true; };
  }, [path, version, fallbackError]);

  return { data, refreshing, error };
}
