import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setAuth, setUnauthorizedHandler } from './api';
import type { ClinicMembership, LoginResponse, Role } from './types';

interface Session {
  token: string;
  expiresAt: string;
  staffId: number;
  name: string;
  clinics: ClinicMembership[];
  clinicId?: number;
}

interface SessionApi {
  session?: Session;
  clinic?: ClinicMembership;
  /** {@code username}: email address or 10-digit mobile number. */
  login(username: string, password: string): Promise<void>;
  chooseClinic(clinicId: number): void;
  logout(): void;
  has(...roles: Role[]): boolean;
}

const KEY = 'clinic-emr.session';
const SessionContext = createContext<SessionApi | null>(null);

// Kept in sessionStorage so closing the tab signs out; shared clinic computers shouldn't stay signed in.
function load(): Session | undefined {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return undefined;
    const s = JSON.parse(raw) as Session;
    return new Date(s.expiresAt) > new Date() ? s : undefined;
  } catch {
    return undefined;
  }
}

function save(s?: Session) {
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
    else sessionStorage.removeItem(KEY);
  } catch {
    // storage unavailable: session lasts until reload
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | undefined>(() => {
    const s = load();
    if (s) setAuth({ token: s.token, clinicId: s.clinicId });
    return s;
  });

  const update = useCallback((s?: Session) => {
    setAuth(s ? { token: s.token, clinicId: s.clinicId } : {});
    save(s);
    setSession(s);
  }, []);

  const logout = useCallback(() => update(undefined), [update]);

  useEffect(() => setUnauthorizedHandler(logout), [logout]);

  const value = useMemo<SessionApi>(() => {
    const clinic = session?.clinics.find((c) => c.clinicId === session.clinicId);
    return {
      session,
      clinic,
      async login(username, password) {
        const res = await api.post<LoginResponse>('/auth/staff/login', { username, password });
        update({
          token: res.accessToken,
          expiresAt: res.expiresAt,
          staffId: res.staffId,
          name: res.name,
          clinics: res.clinics,
          clinicId: res.clinics.length === 1 ? res.clinics[0].clinicId : undefined,
        });
      },
      chooseClinic(clinicId) {
        if (session) update({ ...session, clinicId });
      },
      logout,
      has: (...roles) => !!clinic && roles.some((r) => clinic.roles.includes(r)),
    };
  }, [session, update, logout]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession outside SessionProvider');
  return ctx;
}
