import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { App as ClinicApp } from './clinic/App';
import { SessionProvider as ClinicSession } from './clinic/session';
import { App as PatientApp } from './patient/App';
import { SessionProvider as PatientSession } from './patient/session';
import clinicCss from './clinic/styles.css?inline';
import patientCss from './patient/styles.css?inline';

/**
 * One app for everyone. Patients and families use /my; clinic staff use everything else. Each side keeps its own
 * sign-in and its own stylesheet, which is only on the page while that side is shown.
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/my/*" element={
          <PatientSession>
            <style>{patientCss}</style>
            <PatientApp />
          </PatientSession>
        } />
        <Route path="*" element={
          <ClinicSession>
            <style>{clinicCss}</style>
            <ClinicApp />
          </ClinicSession>
        } />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
