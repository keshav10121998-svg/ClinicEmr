import { useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from './api';
import { useSession } from './session';
import type { PendingRequest, Profile } from './types';
import { SignInPage } from './pages/SignInPage';
import { VisitsTab } from './pages/VisitsTab';
import { DocumentsTab } from './pages/DocumentsTab';
import { SharingTab } from './pages/SharingTab';
import { VisitPage } from './pages/VisitPage';
import { PendingRequests } from './components/PendingRequests';
import { PinForm } from './pages/PinPage';

type Tab = 'visits' | 'documents' | 'sharing';

export function App() {
  const { session } = useSession();
  if (!session) return <SignInPage />;
  // Code sign-ins choose a PIN on the way in; a phone signed in before that rule still has to set one first
  if (!session.pinSet) {
    return (
      <div className="auth">
        <PinForm changing={false} onDone={() => {}} />
      </div>
    );
  }
  return (
    <Routes>
      <Route index element={<Home />} />
      <Route path="profiles/:profileId/visits/:encounterId" element={<VisitPage />} />
      <Route path="*" element={<Navigate to="/my" replace />} />
    </Routes>
  );
}

function Home() {
  const { session, signOut } = useSession();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [profiles, setProfiles] = useState<Profile[]>();
  const [pending, setPending] = useState<PendingRequest[]>([]);
  const [error, setError] = useState<string>();
  const [changingPin, setChangingPin] = useState(false);
  const tab = (params.get('tab') as Tab) || 'visits';
  const profileId = params.get('p') ?? profiles?.[0]?.profileId;
  const profile = profiles?.find((p) => p.profileId === profileId);

  const loadPending = useCallback(() => {
    api.get<PendingRequest[]>('/patient/access-requests').then(setPending).catch(() => {});
  }, []);

  useEffect(() => {
    api.get<Profile[]>('/patient/profiles')
      .then(setProfiles)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load your profiles.'));
    loadPending();
    // A clinic may be waiting at the counter: check for new requests regularly
    const t = window.setInterval(loadPending, 10000);
    return () => window.clearInterval(t);
  }, [loadPending]);

  function go(next: { tab?: Tab; p?: string }) {
    const q = new URLSearchParams(params);
    if (next.tab) q.set('tab', next.tab);
    if (next.p) q.set('p', next.p);
    setParams(q, { replace: true });
  }

  return (
    <div className="shell">
      <header className="top">
        <span className="brand">ClinicEMR</span>
        <span className="spacer" />
        <span className="small muted mono">{session!.phone}</span>
        <button className="link small" onClick={() => setChangingPin((v) => !v)}>{session!.pinSet ? 'Change PIN' : 'Set PIN'}</button>
        <button className="link small" onClick={() => { signOut(); navigate('/my'); }}>Sign out</button>
      </header>
      <main>
        {error && <div className="alert error">{error}</div>}
        {changingPin && <PinForm changing={session!.pinSet} onDone={() => setChangingPin(false)} onSkip={() => setChangingPin(false)} />}
        {profiles && pending.length > 0 && (
          <PendingRequests requests={pending} profiles={profiles} onDone={loadPending} />
        )}
        {profiles && profiles.length === 0 && (
          <div className="card">
            <h2>No records yet</h2>
            <p className="muted">When a clinic on ClinicEMR registers you or a family member with this number, their records appear here.</p>
          </div>
        )}
        {profiles && profiles.length > 0 && (
          <>
            {profiles.length > 1 && (
              <div className="family" role="group" aria-label="Family member">
                {profiles.map((p) => (
                  <button key={p.profileId} aria-pressed={p.profileId === profileId} onClick={() => go({ p: p.profileId })}>
                    {p.name.split(' ')[0]}
                  </button>
                ))}
              </div>
            )}
            {profile && (
              <div>
                <h1>{profile.name}</h1>
                <p className="muted small">{[profile.ageYears != null ? `${profile.ageYears} yrs` : '', ({ M: 'Male', F: 'Female', O: 'Other' } as Record<string, string>)[profile.gender]].filter(Boolean).join(' · ')}</p>
              </div>
            )}
            {profile && tab === 'visits' && <VisitsTab profileId={profile.profileId} />}
            {profile && tab === 'documents' && <DocumentsTab profileId={profile.profileId} />}
            {tab === 'sharing' && <SharingTab profiles={profiles} />}
          </>
        )}
      </main>
      <nav className="tabs" aria-label="Sections">
        <div className="inner">
          <button aria-current={tab === 'visits' ? 'page' : undefined} onClick={() => go({ tab: 'visits' })}>Visits</button>
          <button aria-current={tab === 'documents' ? 'page' : undefined} onClick={() => go({ tab: 'documents' })}>Documents</button>
          <button aria-current={tab === 'sharing' ? 'page' : undefined} onClick={() => go({ tab: 'sharing' })}>
            Sharing{pending.length > 0 ? ` (${pending.length})` : ''}
          </button>
        </div>
      </nav>
    </div>
  );
}
