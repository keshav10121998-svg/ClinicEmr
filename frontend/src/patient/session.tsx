import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { setToken, setUnauthorizedHandler } from './api';

interface Session {
  token: string;
  expiresAt: string;
  accountId: number;
  phone: string;
}

interface SessionApi {
  session?: Session;
  signIn(s: Session): void;
  signOut(): void;
}

const KEY = 'clinic-emr.patient-session';
const Ctx = createContext<SessionApi | null>(null);

// A patient's own phone: stay signed in (the token lasts 30 days) unless they sign out.
function load(): Session | undefined {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return undefined;
    const s = JSON.parse(raw) as Session;
    return new Date(s.expiresAt) > new Date() ? s : undefined;
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
  const signOut = useCallback(() => update(undefined), [update]);
  useEffect(() => setUnauthorizedHandler(signOut), [signOut]);

  const value = useMemo(() => ({ session, signIn: update, signOut }), [session, update, signOut]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession() {
  const c = useContext(Ctx);
  if (!c) throw new Error('useSession outside SessionProvider');
  return c;
}
