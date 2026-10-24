import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ExitIcon, PeopleIcon, StaffIcon } from './components/Icons';
import { useSession } from './session';
import { LoginPage } from './pages/LoginPage';
import { RegisterClinicPage } from './pages/RegisterClinicPage';
import { ChooseClinicPage } from './pages/ChooseClinicPage';
import { PatientsPage } from './pages/PatientsPage';
import { PatientPage } from './pages/PatientPage';
import { VisitPage } from './pages/VisitPage';
import { StaffPage } from './pages/StaffPage';

export function App() {
  const { session, clinic, has } = useSession();

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
  return (
    <>
      <TopBar />
      <BottomTabs />
      <main className="page">
        <Routes>
          <Route path="/" element={<Navigate to="/patients" replace />} />
          <Route path="/patients" element={<PatientsPage />} />
          <Route path="/patients/:patientId" element={<PatientPage />} />
          <Route path="/visits/:visitId" element={<VisitPage />} />
          <Route path="/staff" element={has('ADMIN') ? <StaffPage /> : <Navigate to="/patients" replace />} />
          <Route path="*" element={<Navigate to="/patients" replace />} />
        </Routes>
      </main>
    </>
  );
}

function TopBar() {
  const { session, clinic, logout, chooseClinic, has } = useSession();
  const navigate = useNavigate();
  return (
    <header className="topbar">
      <a className="brand" href="/patients">ClinicEMR</a>
      <nav>
        <NavLink to="/patients">Patients</NavLink>
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
function BottomTabs() {
  const { logout, has } = useSession();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  if (pathname.startsWith('/visits/')) return null;
  return (
    <nav className="bottom-tabs" aria-label="Main">
      <NavLink to="/patients"><PeopleIcon />Patients</NavLink>
      {has('ADMIN') && <NavLink to="/staff"><StaffIcon />Staff</NavLink>}
      <button type="button" onClick={() => { logout(); navigate('/', { replace: true }); }}><ExitIcon />Sign out</button>
    </nav>
  );
}

export function roleLabel(r: string) {
  return { ADMIN: 'Admin', FRONT_DESK: 'Front desk', NURSE: 'Nurse', DOCTOR: 'Doctor' }[r] ?? r;
}
