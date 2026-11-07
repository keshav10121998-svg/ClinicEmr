import { useEffect, useRef, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ChartIcon, PeopleIcon, RupeeIcon, StaffIcon, StarIcon } from './components/Icons';
import { onlyMine, useDuePayments, usePaymentRequests } from './payments';
import { useSession } from './session';
import { LoginPage } from './pages/LoginPage';
import { RegisterClinicPage } from './pages/RegisterClinicPage';
import { ChooseClinicPage } from './pages/ChooseClinicPage';
import { PatientsPage } from './pages/PatientsPage';
import { PatientPage } from './pages/PatientPage';
import { VisitPage } from './pages/VisitPage';
import { StaffPage } from './pages/StaffPage';
import { OverviewPage } from './pages/OverviewPage';
import { EarningsPage } from './pages/EarningsPage';
import { RatingsPage } from './pages/RatingsPage';
import { PdfViewerHost } from '../shared/pdf/PdfViewer';

export function App() {
  const { session, clinic } = useSession();

  if (!session) {
    return (
      <Routes>
        <Route path="/signup" element={<RegisterClinicPage />} />
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }
  if (!clinic) {
    return <ChooseClinicPage />;
  }
  return <ClinicShell />;
}

/** The signed-in clinic screens. The Overview tab shows how many balances are due (the payment reminder). */
function ClinicShell() {
  const { has } = useSession();
  const { dues } = useDuePayments(onlyMine(has));
  const { requests } = usePaymentRequests();
  // Balances due now plus patients' payment proofs waiting to be checked
  const due = (dues?.length ?? 0) + (requests?.length ?? 0);
  // Nurses don't see money
  const seesMoney = has('ADMIN', 'DOCTOR', 'FRONT_DESK');
  // Ratings are for the people the patients rated
  const seesRatings = has('ADMIN', 'DOCTOR');
  return (
    <>
      <PdfViewerHost />
      <TopBar due={due} />
      <BottomTabs due={due} />
      <main className="page">
        <Routes>
          <Route path="/" element={<Navigate to="/patients" replace />} />
          <Route path="/patients" element={<PatientsPage />} />
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/earnings" element={seesMoney ? <EarningsPage /> : <Navigate to="/patients" replace />} />
          <Route path="/ratings" element={seesRatings ? <RatingsPage /> : <Navigate to="/patients" replace />} />
          <Route path="/patients/:patientId" element={<PatientPage />} />
          <Route path="/visits/:visitId" element={<VisitPage />} />
          <Route path="/staff" element={has('ADMIN') ? <StaffPage /> : <Navigate to="/patients" replace />} />
          <Route path="*" element={<Navigate to="/patients" replace />} />
        </Routes>
      </main>
    </>
  );
}

function TopBar({ due }: { due: number }) {
  const { session, clinic, logout, chooseClinic, has } = useSession();
  const navigate = useNavigate();
  const signOut = () => { logout(); navigate('/', { replace: true }); };
  return (
    <header className="topbar">
      <a className="brand" href="/patients">ClinicEMR</a>
      <nav>
        <NavLink to="/patients">Patients</NavLink>
        <NavLink to="/overview">Overview{due > 0 && <DueCount n={due} />}</NavLink>
        {has('ADMIN', 'DOCTOR', 'FRONT_DESK') && <NavLink to="/earnings">Earnings</NavLink>}
        {has('ADMIN', 'DOCTOR') && <NavLink to="/ratings">Ratings</NavLink>}
        {has('ADMIN') && <NavLink to="/staff">Staff</NavLink>}
      </nav>
      <span className="spacer" />
      {session!.clinics.length > 1 ? (
        <select
          aria-label="Clinic"
          value={clinic!.clinicId}
          onChange={(e) => chooseClinic(Number(e.target.value))}
          style={{ width: 'auto' }}
        >
          {session!.clinics.map((c) => (
            <option key={c.clinicId} value={c.clinicId}>{c.clinicName}</option>
          ))}
        </select>
      ) : (
        <span className="who clinic-name">{clinic!.clinicName}</span>
      )}
      <span className="who">{session!.name} · {clinic!.roles.map(roleLabel).join(', ')}</span>
      <button className="link desktop-only" onClick={signOut}>Sign out</button>
      <ProfileMenu name={session!.name} roles={clinic!.roles.map(roleLabel).join(', ')} onSignOut={signOut} />
    </header>
  );
}

/**
 * Phones have no room for the name in the top bar, so the profile is a round button with the person's initials; it opens
 * a small card with the name, role and Sign out. Closes with a tap outside, Esc, or when an item is chosen.
 */
function ProfileMenu({ name, roles, onSignOut }: { name: string; roles: string; onSignOut: () => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', esc); };
  }, [open]);
  const initials = name.trim().split(/\s+/).filter((p) => !/^dr\.?$/i.test(p)).slice(0, 2).map((p) => p[0]?.toUpperCase() ?? '').join('') || '?';
  return (
    <div className="profile-menu phone-only" ref={box}>
      <button type="button" className="profile-btn" aria-haspopup="menu" aria-expanded={open} aria-label={`Profile: ${name}`}
              onClick={() => setOpen((v) => !v)}>{initials}</button>
      {open && (
        <div className="profile-pop" role="menu">
          <b>{name}</b>
          <span className="muted small">{roles}</span>
          <button type="button" role="menuitem" className="profile-signout" onClick={onSignOut}>Sign out</button>
        </div>
      )}
    </div>
  );
}

/** Phone navigation along the bottom edge, within thumb reach. Hidden on wider screens and during a visit. */
function BottomTabs({ due }: { due: number }) {
  const { has } = useSession();
  const { pathname } = useLocation();
  if (pathname.startsWith('/visits/')) return null;
  return (
    <nav className="bottom-tabs" aria-label="Main">
      <NavLink to="/patients"><PeopleIcon />Patients</NavLink>
      <NavLink to="/overview" className="tab-with-count"><ChartIcon />Overview{due > 0 && <DueCount n={due} />}</NavLink>
      {has('ADMIN', 'DOCTOR', 'FRONT_DESK') && <NavLink to="/earnings"><RupeeIcon />Earnings</NavLink>}
      {has('ADMIN', 'DOCTOR') && <NavLink to="/ratings"><StarIcon />Ratings</NavLink>}
      {has('ADMIN') && <NavLink to="/staff"><StaffIcon />Staff</NavLink>}
    </nav>
  );
}

function DueCount({ n }: { n: number }) {
  return <span className="due-count" aria-label={`${n} payments to collect`}>{n}</span>;
}

export function roleLabel(r: string) {
  return { ADMIN: 'Admin', FRONT_DESK: 'Front desk', NURSE: 'Nurse', DOCTOR: 'Doctor' }[r] ?? r;
}
