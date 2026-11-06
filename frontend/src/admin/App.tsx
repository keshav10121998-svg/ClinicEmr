import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import { adminApi, ApiError, setAdminToken, setAdminUnauthorizedHandler,
  type AdminVisit, type DeleteResult, type Doctor, type DoctorDetail, type Overview, type PurgeResult } from './api';
import { date, dateTime } from '../clinic/format';
import { BillingPage } from './BillingPage';

interface AdminSession {
  token: string;
  expiresAt: string;
  name: string;
  email: string;
}

const KEY = 'clinic-emr.admin-session';

function load(): AdminSession | undefined {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) ?? 'null') as AdminSession | null;
    return s && new Date(s.expiresAt) > new Date() ? s : undefined;
  } catch {
    return undefined;
  }
}

/** The super admin (platform owner) at /admin: totals across every clinic, every doctor, and removing test data. */
export function App() {
  const [session, setSession] = useState<AdminSession | undefined>(() => {
    const s = load();
    setAdminToken(s?.token);
    return s;
  });

  const update = useCallback((s?: AdminSession) => {
    setAdminToken(s?.token);
    try {
      if (s) localStorage.setItem(KEY, JSON.stringify(s));
      else localStorage.removeItem(KEY);
    } catch {
      // storage unavailable: signed in until reload
    }
    setSession(s);
  }, []);

  useEffect(() => setAdminUnauthorizedHandler(() => update(undefined)), [update]);

  if (!session) return <AdminLogin onSignedIn={update} />;
  return (
    <>
      <header className="topbar">
        <Link className="brand" to="/admin">ClinicEMR · Super admin</Link>
        <nav>
          <NavLink to="/admin" end>Doctors</NavLink>
          <NavLink to="/admin/billing">Billing</NavLink>
        </nav>
        <span className="spacer" />
        <span className="who">{session.name}</span>
        <button className="link" onClick={() => update(undefined)}>Sign out</button>
      </header>
      <main className="page">
        <Routes>
          <Route index element={<AdminHome />} />
          <Route path="doctors/:id" element={<DoctorPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Routes>
      </main>
    </>
  );
}

function AdminLogin({ onSignedIn }: { onSignedIn: (s: AdminSession) => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const r = await adminApi.post<{ accessToken: string; expiresAt: string; name: string; email: string }>(
        '/auth/admin/login', { email, password });
      onSignedIn({ token: r.accessToken, expiresAt: r.expiresAt, name: r.name, email: r.email });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not sign in.');
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <form className="card" onSubmit={submit}>
        <div>
          <h1>Super admin</h1>
          <p className="muted small">For the ClinicEMR owner only. Clinic staff sign in at the main page.</p>
        </div>
        {error && <div className="alert error">{error}</div>}
        <label className="field">Email
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">Password
          <input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  );
}

function AdminHome() {
  const navigate = useNavigate();
  const flash = (useLocation().state as { message?: string } | null)?.message;
  const [overview, setOverview] = useState<Overview>();
  const [doctors, setDoctors] = useState<Doctor[]>();
  const [showDeleted, setShowDeleted] = useState(false);
  const [filter, setFilter] = useState('');
  const [onlyDoctors, setOnlyDoctors] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => {
    adminApi.get<Overview>('/admin/overview').then(setOverview)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load the totals.'));
  }, []);

  useEffect(() => {
    setDoctors(undefined);
    adminApi.get<Doctor[]>(`/admin/doctors?deleted=${showDeleted}`).then(setDoctors)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load doctors.'));
  }, [showDeleted]);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return doctors?.filter((d) => (!onlyDoctors || d.isDoctor)
      && (!q || [d.name, d.email, d.phone ?? '', d.clinics].some((s) => s.toLowerCase().includes(q))));
  }, [doctors, filter, onlyDoctors]);

  const tiles: [string, number | undefined, string?][] = [
    ['Clinics', overview?.clinics, overview && `+${overview.newClinicsLast7Days} in 7 days`],
    ['Doctors', overview?.doctors, overview && `${overview.staff} staff accounts in all`],
    ['Patients', overview?.patients, overview && `+${overview.newPatientsLast7Days} in 7 days`],
    ['Patient app users', overview?.appUsers],
    ['Visits', overview?.visits, overview && `${overview.visitsToday} today · ${overview.visitsLast7Days} in 7 days`],
  ];

  return (
    <>
      <div className="between">
        <h1>All clinics</h1>
        <Link className="admin-link-btn" to="/admin/billing">Billing</Link>
      </div>
      {error && <div className="alert error">{error}</div>}
      {flash && <div className="alert info">{flash}</div>}
      <div className="stat-tiles">
        {tiles.map(([label, n, sub], i) => (
          <div key={label} className={`stat-tile${i === 0 ? ' main' : ''}`}>
            <span className="stat-label">{label}</span>
            <span className="stat-number">{n ?? '–'}</span>
            <span className="stat-sub">{sub}</span>
          </div>
        ))}
      </div>
      {overview && (overview.deletedDoctors > 0 || overview.deletedVisits > 0) && (
        <p className="small muted">Removed so far: {overview.deletedDoctors} staff accounts, {overview.deletedVisits} visits.</p>
      )}

      <div className="card">
        <div className="between">
          <h2>Doctors and staff</h2>
          {shown && <span className="muted small">{shown.length} shown</span>}
        </div>
        <input aria-label="Search" placeholder="Search name, email, mobile or clinic" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <div className="row">
          <label className="check"><input type="checkbox" checked={onlyDoctors} onChange={(e) => setOnlyDoctors(e.target.checked)} /> Doctors only</label>
          <label className="check"><input type="checkbox" checked={showDeleted} onChange={(e) => setShowDeleted(e.target.checked)} /> Show removed</label>
        </div>
        {!doctors && !error && <p className="muted">Loading…</p>}
        {shown?.length === 0 && <p className="muted">Nobody matches.</p>}
        <div className="patient-list">
          {shown?.map((d) => (
            <button key={d.id} type="button" className="patient-row" onClick={() => navigate(`/admin/doctors/${d.id}`)}>
              <span className="avatar" aria-hidden="true">{initials(d.name)}</span>
              <span className="patient-name">
                {d.name}
                {d.deletedAt ? <span className="chip allergy admin-chip">Removed</span>
                  : !d.isDoctor && <span className="chip admin-chip">Staff</span>}
              </span>
              <span className="muted small">{d.email}{d.phone ? ` · ${d.phone.replace(/^\+91/, '')}` : ''}<br />{d.clinics || 'No clinic'}</span>
              <span className="small last-visit">
                <b className="visit-total">{d.visits} {d.visits === 1 ? 'visit' : 'visits'}</b> · {d.patients} patients
                <br />{d.lastVisitAt ? `Last visit ${date(d.lastVisitAt)}` : `Joined ${date(d.createdAt)}`}
              </span>
            </button>
          ))}
        </div>
      </div>
    </>
  );
}

function DoctorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<DoctorDetail>();
  const [error, setError] = useState<string>();
  const [result, setResult] = useState<DeleteResult>();

  const load = useCallback(() => {
    adminApi.get<DoctorDetail>(`/admin/doctors/${id}`).then(setDetail)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load this doctor.'));
  }, [id]);
  useEffect(load, [load]);

  if (!detail) {
    return <>{error ? <div className="alert error">{error}</div> : <p className="muted">Loading…</p>}</>;
  }
  const d = detail.doctor;
  return (
    <>
      <button className="link" style={{ justifySelf: 'start' }} onClick={() => navigate('/admin')}>← All clinics</button>
      {error && <div className="alert error">{error}</div>}
      <div className="card">
        <div className="between">
          <h1>{d.name}</h1>
          {d.deletedAt ? <span className="chip allergy">Removed {date(d.deletedAt)}</span> : <span className="chip ok">{d.isDoctor ? 'Doctor' : 'Staff'}</span>}
        </div>
        <p className="muted">
          {d.email}{d.phone ? ` · ${d.phone.replace(/^\+91/, '')}` : ''}<br />
          {d.isDoctor && <>{[d.qualifications, d.specialty, d.registrationNo && `Reg. ${d.registrationNo}`].filter(Boolean).join(' · ')}<br /></>}
          Joined {date(d.createdAt)} · {d.visits} finished {d.visits === 1 ? 'visit' : 'visits'} · {d.patients} patients
          {detail.draftVisits > 0 && ` · ${detail.draftVisits} in progress`}
        </p>
        <h3>Clinics</h3>
        {detail.clinics.length === 0 && <p className="muted">Not part of any clinic.</p>}
        {detail.clinics.map((c) => (
          <div key={c.id} className="small">
            <b>{c.name}</b>{c.city ? `, ${c.city}` : ''} · {c.roles.toLowerCase().replace(/_/g, ' ')} · {c.patients} patients · {c.staff} staff
            {c.deletedAt && <span className="chip allergy admin-chip">Removed</span>}
          </div>
        ))}
      </div>

      {result && (
        <div className="alert info">
          Removed {d.name}. {result.visitsDeleted} {result.visitsDeleted === 1 ? 'visit' : 'visits'} removed
          {result.clinicsDeleted.length > 0 && `; clinic ${result.clinicsDeleted.join(', ')} removed with ${result.patientsDeleted} ${result.patientsDeleted === 1 ? 'patient' : 'patients'} registered only there`}.
        </div>
      )}
      {d.deletedAt && <PurgeDoctor doctor={d} clinics={detail.clinics.filter((c) => c.deletedAt).map((c) => c.name)} />}
      {!d.deletedAt && <DeleteDoctor doctor={d} clinicsLeft={detail.clinics.filter((c) => !c.deletedAt && c.staff <= 1).map((c) => c.name)}
                                     onDone={(r) => { setResult(r); load(); }} />}

      <div className="card">
        <h2>Visits</h2>
        {detail.visits.length === 0 && <p className="muted">No visits.</p>}
        <div className="list">
          {detail.visits.map((v) => <VisitRow key={v.id} visit={v} onDeleted={load} />)}
        </div>
        {detail.visits.length >= 300 && <p className="small muted">Showing the latest 300 visits.</p>}
      </div>
    </>
  );
}

function DeleteDoctor({ doctor, clinicsLeft, onDone }: { doctor: Doctor; clinicsLeft: string[]; onDone: (r: DeleteResult) => void }) {
  const [asking, setAsking] = useState(false);
  const [alsoVisits, setAlsoVisits] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function remove() {
    setBusy(true);
    setError(undefined);
    try {
      onDone(await adminApi.post<DeleteResult>(`/admin/doctors/${doctor.id}/delete`, { alsoVisits }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not remove this doctor.');
      setBusy(false);
    }
  }

  if (!asking) {
    return <button type="button" className="danger" style={{ justifySelf: 'start' }} onClick={() => setAsking(true)}>Remove this {doctor.isDoctor ? 'doctor' : 'account'}…</button>;
  }
  return (
    <div className="card admin-danger">
      <h2>Remove {doctor.name}?</h2>
      <ul className="small">
        <li>They can't sign in any more. Their email and mobile number can be used to register again.</li>
        {clinicsLeft.length > 0 && <li>Nobody else works at <b>{clinicsLeft.join(', ')}</b>, so the clinic is removed too, with the patients registered only there.</li>}
        <li>Records stay in the database (with when they were removed) but no longer show anywhere in the app.</li>
      </ul>
      <label className="check"><input type="checkbox" checked={alsoVisits} onChange={(e) => setAlsoVisits(e.target.checked)} /> Also remove their {doctor.visits} visits</label>
      {error && <div className="alert error">{error}</div>}
      <div className="row">
        <button type="button" className="danger" onClick={remove} disabled={busy}>{busy ? 'Removing…' : 'Yes, remove'}</button>
        <button type="button" onClick={() => setAsking(false)} disabled={busy}>Keep</button>
      </div>
    </div>
  );
}

function VisitRow({ visit: v, onDeleted }: { visit: AdminVisit; onDeleted: () => void }) {
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function remove() {
    setBusy(true);
    setError(undefined);
    try {
      await adminApi.post(`/admin/visits/${v.id}/delete`, { reason: reason.trim() || undefined });
      onDeleted();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not remove the visit.');
      setBusy(false);
    }
  }

  return (
    <div className={`admin-visit${v.deletedAt ? ' removed' : ''}`}>
      <div className="between">
        <b>{v.patientName}{v.mrn ? <span className="muted mono small"> {v.mrn}</span> : null}</b>
        {v.deletedAt ? <span className="chip allergy">Removed</span>
          : v.status === 'DRAFT' ? <span className="chip">In progress</span> : <span className="chip ok">Finished</span>}
      </div>
      <span className="small muted">{dateTime(v.startedAt)} · {v.clinicName}{v.diagnoses ? ` · ${v.diagnoses}` : ''}</span>
      {v.deletedAt && <span className="small muted">Removed {date(v.deletedAt)}: {v.deletedReason}</span>}
      {v.deletedAt && <PurgeVisit id={v.id} onDone={onDeleted} />}
      {!v.deletedAt && (asking ? (
        <div className="row">
          <input aria-label="Reason (optional)" placeholder="Reason (optional), e.g. Test visit" maxLength={300}
                 value={reason} onChange={(e) => setReason(e.target.value)} style={{ flex: '1 1 200px' }} />
          <button type="button" className="danger" onClick={remove} disabled={busy}>{busy ? 'Removing…' : 'Yes, remove'}</button>
          <button type="button" onClick={() => setAsking(false)} disabled={busy}>Keep</button>
        </div>
      ) : (
        <button type="button" className="link danger small" style={{ justifySelf: 'start' }} onClick={() => setAsking(true)}>Remove visit</button>
      ))}
      {error && <span className="small" style={{ color: 'var(--danger)' }}>{error}</span>}
    </div>
  );
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

/** Second step for a removed visit: erase it for good (with its medicines, payments and attached files). */
function PurgeVisit({ id, onDone }: { id: string; onDone: () => void }) {
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function purge() {
    setBusy(true);
    setError(undefined);
    try {
      await adminApi.post<PurgeResult>(`/admin/visits/${id}/purge`);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete the visit.');
      setBusy(false);
    }
  }

  return (
    <>
      {asking ? (
        <div className="row">
          <span className="small" style={{ color: 'var(--danger)' }}>Erase this visit for good? This can't be undone.</span>
          <button type="button" className="danger-solid" onClick={purge} disabled={busy}>{busy ? 'Deleting…' : 'Delete forever'}</button>
          <button type="button" onClick={() => setAsking(false)} disabled={busy}>Keep</button>
        </div>
      ) : (
        <button type="button" className="link danger small" style={{ justifySelf: 'start' }} onClick={() => setAsking(true)}>Permanently delete</button>
      )}
      {error && <span className="small" style={{ color: 'var(--danger)' }}>{error}</span>}
    </>
  );
}

/** Second step for a removed doctor: erase the account, their removed clinics and everything in them. */
function PurgeDoctor({ doctor, clinics }: { doctor: Doctor; clinics: string[] }) {
  const navigate = useNavigate();
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function purge() {
    setBusy(true);
    setError(undefined);
    try {
      const r = await adminApi.post<PurgeResult>(`/admin/doctors/${doctor.id}/purge`);
      navigate('/admin', {
        replace: true,
        state: { message: `${doctor.name} was permanently deleted: ${r.visits} visits, ${r.clinics} clinics, ${r.patients} patients and ${r.files} files erased.` },
      });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete this doctor.');
      setBusy(false);
    }
  }

  return (
    <div className="card admin-danger">
      <h2>Permanently delete</h2>
      <p className="small">
        Erases {doctor.name}'s account{clinics.length > 0 && <> and the removed clinic <b>{clinics.join(', ')}</b> with its patients, visits,
        payments and files</>} from the database for good. <b>This can't be undone.</b> Use it only for test or dummy accounts.
      </p>
      <label className="field">Type DELETE to confirm
        <input value={typed} onChange={(e) => setTyped(e.target.value)} autoCapitalize="characters" placeholder="DELETE" style={{ maxWidth: 220 }} />
      </label>
      {error && <div className="alert error">{error}</div>}
      <button type="button" className="danger-solid" style={{ justifySelf: 'start' }} onClick={purge}
              disabled={busy || typed.trim().toUpperCase() !== 'DELETE'}>{busy ? 'Deleting…' : 'Delete forever'}</button>
    </div>
  );
}
