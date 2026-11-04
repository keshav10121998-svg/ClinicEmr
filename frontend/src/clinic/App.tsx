import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ChartIcon, ExitIcon, PeopleIcon, StaffIcon } from './components/Icons';
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
  return (
    <>
      <TopBar due={due} />
      <BottomTabs due={due} />
      <main className="page">
        <Routes>
          <Route path="/" element={<Navigate to="/patients" replace />} />
          <Route path="/patients" element={<PatientsPage />} />
          <Route path="/overview" element={<OverviewPage />} />
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
  return (
    <header className="topbar">
      <a className="brand" href="/patients">ClinicEMR</a>
      <nav>
        <NavLink to="/patients">Patients</NavLink>
        <NavLink to="/overview">Overview{due > 0 && <DueCount n={due} />}</NavLink>
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
      <button className="link desktop-only" onClick={() => { logout(); navigate('/', { replace: true }); }}>Sign out</button>
    </header>
  );
}

/** Phone navigation along the bottom edge, within thumb reach. Hidden on wider screens and during a visit. */
function BottomTabs({ due }: { due: number }) {
  const { logout, has } = useSession();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  if (pathname.startsWith('/visits/')) return null;
  return (
    <nav className="bottom-tabs" aria-label="Main">
      <NavLink to="/patients"><PeopleIcon />Patients</NavLink>
      <NavLink to="/overview" className="tab-with-count"><ChartIcon />Overview{due > 0 && <DueCount n={due} />}</NavLink>
      {has('ADMIN') && <NavLink to="/staff"><StaffIcon />Staff</NavLink>}
      <button type="button" onClick={() => { logout(); navigate('/', { replace: true }); }}><ExitIcon />Sign out</button>
    </nav>
  );
}

function DueCount({ n }: { n: number }) {
  return <span className="due-count" aria-label={`${n} payments to collect`}>{n}</span>;
}

export function roleLabel(r: string) {
  return { ADMIN: 'Admin', FRONT_DESK: 'Front desk', NURSE: 'Nurse', DOCTOR: 'Doctor' }[r] ?? r;
}
