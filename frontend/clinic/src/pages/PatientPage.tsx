import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, openPdf } from '../api';
import { useSession } from '../session';
import type { EncounterListItem, Encounter, Patient, PatientSummary } from '../types';
import { AllergyEditor, PatientHeader, SummaryPanel } from '../components/PatientPanels';
import { dateTime } from '../format';
import { SharedHistoryPanel } from '../components/SharedHistoryPanel';

export function PatientPage() {
  const { patientId } = useParams();
  const { has } = useSession();
  const navigate = useNavigate();
  const clinical = has('DOCTOR', 'NURSE');
  const [patient, setPatient] = useState<Patient>();
  const [summary, setSummary] = useState<PatientSummary>();
  const [visits, setVisits] = useState<EncounterListItem[]>([]);
  const [error, setError] = useState<string>();

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

  return (
    <>
      <div className="between">
        <button className="link" onClick={() => navigate('/patients')}>← Patients</button>
        {has('DOCTOR') && <button className="primary" onClick={startVisit}>Start visit</button>}
      </div>
      {error && <div className="alert error">{error}</div>}
      <PatientHeader patient={patient} allergies={summary?.allergies} />

      {!clinical && (
        <div className="alert info">Visit records are visible to doctors and nurses.</div>
      )}

      <SharedHistoryPanel patientId={patientId!} />

      {clinical && summary && (
        <div className="grid-2">
          <div style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
            <SummaryPanel summary={summary} />
            <AllergyEditor patientId={patientId!} allergies={summary.allergies}
                           onChange={(a) => setSummary({ ...summary, allergies: a })} />
          </div>
          <div className="card" style={{ alignContent: 'start' }}>
            <h2>Visits at this clinic</h2>
            {visits.length === 0 ? <p className="muted">No visits yet.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Date</th><th>Doctor</th><th>Diagnosis</th><th>Rx</th><th /></tr></thead>
                  <tbody>
                    {visits.map((v) => (
                      <tr key={v.id} className="clickable" onClick={() => navigate(`/visits/${v.id}`)}>
                        <td className="mono">{dateTime(v.startedAt)}</td>
                        <td>{v.doctorName}</td>
                        <td>{v.diagnoses}{v.currentVersion > 1 && <span className="chip" style={{ marginLeft: 6 }}>amended</span>}</td>
                        <td>{v.prescriptionLines}</td>
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
        </div>
      )}
    </>
  );
}
