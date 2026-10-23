import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useSession } from './session';
import { LoginPage } from './pages/LoginPage';
import { RegisterClinicPage } from './pages/RegisterClinicPage';
import { ChooseClinicPage } from './pages/ChooseClinicPage';
import { PatientsPage } from './pages/PatientsPage';
import { PatientPage } from './pages/PatientPage';
import { VisitPage } from './pages/VisitPage';

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
  return (
    <>
      <TopBar />
      <main className="page">
        <Routes>
          <Route path="/" element={<Navigate to="/patients" replace />} />
          <Route path="/patients" element={<PatientsPage />} />
          <Route path="/patients/:patientId" element={<PatientPage />} />
          <Route path="/visits/:visitId" element={<VisitPage />} />
          <Route path="*" element={<Navigate to="/patients" replace />} />
        </Routes>
      </main>
    </>
  );
}

function TopBar() {
  const { session, clinic, logout, chooseClinic } = useSession();
  return (
    <header className="topbar">
      <a className="brand" href="/patients">ClinicEMR</a>
      <nav>
        <NavLink to="/patients">Patients</NavLink>
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
        <span className="who">{clinic!.clinicName}</span>
      )}
      <span className="who">{session!.name} · {clinic!.roles.map(roleLabel).join(', ')}</span>
      <button className="link" onClick={logout}>Sign out</button>
    </header>
  );
}

export function roleLabel(r: string) {
  return { ADMIN: 'Admin', FRONT_DESK: 'Front desk', NURSE: 'Nurse', DOCTOR: 'Doctor' }[r] ?? r;
}
