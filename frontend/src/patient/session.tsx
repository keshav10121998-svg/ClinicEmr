import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, setToken, setUnauthorizedHandler } from './api';

interface Session {
  token: string;
  expiresAt: string;
  accountId: number;
  phone: string;
  pinSet: boolean;
}

/** What every sign-in endpoint returns. */
export interface SignedIn {
  accessToken: string;
  expiresAt: string;
  accountId: number;
  phone: string;
  profiles: number;
  pinSet: boolean;
}

interface SessionApi {
  session?: Session;
  signIn(s: SignedIn): void;
  signOut(): void;
}

const KEY = 'clinic-emr.patient-session';
const Ctx = createContext<SessionApi | null>(null);

// A patient's own phone: stay signed in (90 days, renewed each time the app opens) unless they sign out.
function load(): Session | undefined {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return undefined;
    const s = JSON.parse(raw) as Session;
    return new Date(s.expiresAt) > new Date() ? { ...s, pinSet: s.pinSet ?? true } : undefined;
  } catch {
    return undefined;
  }
}

function save(s?: Session) {
  try {
    if (s) localStorage.setItem(KEY, JSON.stringify(s));
    else localStorage.removeItem(KEY);
  } catch {
    // storage blocked: session lasts until reload
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | undefined>(() => {
    const s = load();
    setToken(s?.token);
    return s;
  });

  const update = useCallback((s?: Session) => {
    setToken(s?.token);
    save(s);
    setSession(s);
  }, []);
  const signIn = useCallback((r: SignedIn) => update({
    token: r.accessToken, expiresAt: r.expiresAt, accountId: r.accountId, phone: r.phone, pinSet: r.pinSet,
  }), [update]);
  const signOut = useCallback(() => update(undefined), [update]);
  useEffect(() => setUnauthorizedHandler(signOut), [signOut]);

  // Opening the app renews the sign-in, so someone who uses it at least every 90 days never signs in again
  const renewed = useRef(false);
  useEffect(() => {
    if (!session || renewed.current) return;
    renewed.current = true;
    api.post<SignedIn>('/patient/session/renew').then(signIn).catch(() => {});
  }, [session, signIn]);

  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession() {
  const c = useContext(Ctx);
  if (!c) throw new Error('useSession outside SessionProvider');
  return c;
}
