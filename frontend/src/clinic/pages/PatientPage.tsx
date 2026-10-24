import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, openPdf } from '../api';
import { useSession } from '../session';
import type { EncounterListItem, Encounter, Patient, PatientSummary } from '../types';
import { AllergyEditor, LastVisitCard, PatientHeader, SummaryPanel } from '../components/PatientPanels';
import { date, dateTime } from '../format';
import { SharedHistoryPanel } from '../components/SharedHistoryPanel';
import { useIsPhone } from '../useIsPhone';
import { AttachedFiles } from '../components/AttachedFiles';

export function PatientPage() {
  const { patientId } = useParams();
  const { has } = useSession();
  const navigate = useNavigate();
  const clinical = has('DOCTOR', 'NURSE');
  const [patient, setPatient] = useState<Patient>();
  const [summary, setSummary] = useState<PatientSummary>();
  const [visits, setVisits] = useState<EncounterListItem[]>([]);
  const [error, setError] = useState<string>();
  const phone = useIsPhone();
  const [tab, setTab] = useState<'visits' | 'health' | 'files'>('visits');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        if (clinical) {
          const [s, v] = await Promise.all([
            api.get<PatientSummary>(`/clinic/patients/${patientId}/summary`),
            api.get<EncounterListItem[]>(`/clinic/patients/${patientId}/encounters`),
          ]);
          if (!cancelled) {
            setSummary(s);
            setPatient(s.patient);
            setVisits(v);
          }
        } else {
          const p = await api.get<Patient>(`/clinic/patients/${patientId}`);
          if (!cancelled) setPatient(p);
        }
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Could not load the patient.');
      }
    })();
    return () => { cancelled = true; };
  }, [patientId, clinical]);

  async function startVisit() {
    setError(undefined);
    try {
      const v = await api.post<Encounter>('/clinic/encounters', { id: crypto.randomUUID(), patientId });
      navigate(`/visits/${v.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the visit.');
    }
  }

  if (error && !patient) return <div className="alert error">{error}</div>;
  if (!patient) return <p className="muted">Loading…</p>;

  const history = (
    <div className="card" style={{ alignContent: 'start' }}>
      <h2>Visits at this clinic</h2>
      {visits.length > 0 && (
        <div className="visit-stats" aria-label="Visit totals">
          <div><span className="stat-number">{visits.length}</span><span className="stat-label">{visits.length === 1 ? 'Visit' : 'Total visits'}</span></div>
          <div><span className="stat-value">{date(visits[visits.length - 1].startedAt)}</span><span className="stat-label">First visit</span></div>
          <div><span className="stat-value">{date(visits[0].startedAt)}</span><span className="stat-label">Last visit</span></div>
        </div>
      )}
      {visits.length === 0 ? <p className="muted">No visits yet.</p> : phone ? (
        <div className="visit-cards">
          {visits.map((v) => (
            <button key={v.id} type="button" className="visit-card" onClick={() => navigate(`/visits/${v.id}`)}>
              <span className="between">
                <b>{dateTime(v.startedAt)}</b>
                {!v.signedAt ? <span className="chip">Draft</span> : v.currentVersion > 1 ? <span className="chip">Corrected</span> : null}
              </span>
              <span className="muted small">{v.doctorName}</span>
              {v.diagnoses && <span>{v.diagnoses}</span>}
              {v.treatmentGiven && <span className="small"><span className="muted">Treatment:</span> {v.treatmentGiven}</span>}
              {v.medicines && <span className="small"><span className="muted">Medicines:</span> {v.medicines}</span>}
            </button>
          ))}
        </div>
      ) : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Visit time</th><th>Doctor</th><th>Diagnosis</th><th>Treatment</th><th>Medicines</th><th /></tr></thead>
            <tbody>
              {visits.map((v) => (
                <tr key={v.id} className="clickable" onClick={() => navigate(`/visits/${v.id}`)}>
                  <td className="mono" style={{ whiteSpace: 'nowrap' }}>{dateTime(v.startedAt)}</td>
                  <td>{v.doctorName}</td>
                  <td>{v.diagnoses || <span className="muted">—</span>}{v.currentVersion > 1 && <span className="chip" style={{ marginLeft: 6 }}>amended</span>}</td>
                  <td className="small">{v.treatmentGiven || <span className="muted">—</span>}</td>
                  <td className="small">{v.medicines || <span className="muted">—</span>}</td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <button className="link" onClick={() => openPdf(`/clinic/encounters/${v.id}/prescription.pdf`)}>Print</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
  const files = (
    <div className="card">
      <AttachedFiles patientId={patientId!} canAttach title="Reports and files" />
    </div>
  );
  const health = summary && (
    <>
      <SummaryPanel summary={summary} />
      <AllergyEditor patientId={patientId!} allergies={summary.allergies}
                     onChange={(a) => setSummary({ ...summary, allergies: a })} />
    </>
  );

  return (
    <>
      <div className="between">
        <button className="link" onClick={() => navigate('/patients')}>← Patients</button>
        {has('DOCTOR') && !phone && <button className="primary" onClick={startVisit}>Start visit</button>}
      </div>
      {error && <div className="alert error">{error}</div>}
      <PatientHeader patient={patient} allergies={summary?.allergies} />

      {!clinical && (
        <div className="alert info">Visit records are visible to doctors and nurses.</div>
      )}

      {clinical && summary && phone && (
        <>
          <div className="tab-switch" role="tablist" aria-label="Patient record">
            {([['visits', 'Visits'], ['health', 'Health'], ['files', 'Files']] as const).map(([k, l]) => (
              <button key={k} type="button" role="tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>
            ))}
          </div>
          {tab === 'visits' && <>{history}<SharedHistoryPanel patientId={patientId!} /></>}
          {tab === 'health' && health}
          {tab === 'files' && files}
        </>
      )}

      {(!phone || !clinical) && <SharedHistoryPanel patientId={patientId!} />}

      {clinical && summary && !phone && (
        <div className="grid-2">
          <div style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
            {summary.lastVisit && (
              <LastVisitCard lastVisit={summary.lastVisit} onOpen={() => navigate(`/visits/${summary.lastVisit!.encounterId}`)} />
            )}
            {health}
          </div>
          <div style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
            {history}
            {files}
          </div>
        </div>
      )}

      {has('DOCTOR') && phone && (
        <div className="action-bar">
          <button className="primary block" onClick={startVisit}>Start visit</button>
        </div>
      )}
    </>
  );
}
