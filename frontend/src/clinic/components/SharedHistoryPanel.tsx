import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';
import type { GrantView, SharedHistory } from '../types';
import { date, dateTime } from '../format';
import { AccessRequestFlow } from './AccessRequestFlow';

/**
 * On the patient page: which access this clinic holds, a button to ask the patient for it, and (for doctors and
 * nurses) the records from other clinics within that access. Each view is logged for the patient.
 */
export function SharedHistoryPanel({ patientId }: { patientId: string }) {
  const { has } = useSession();
  const clinical = has('DOCTOR', 'NURSE');
  const [grants, setGrants] = useState<GrantView[]>();
  const [history, setHistory] = useState<SharedHistory>();
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string>();
  const [open, setOpen] = useState<string>();

  const load = useCallback(async () => {
    setError(undefined);
    try {
      const g = await api.get<GrantView[]>(`/clinic/patients/${patientId}/access`);
      setGrants(g);
      if (clinical && g.length > 0) {
        setHistory(await api.get<SharedHistory>(`/clinic/patients/${patientId}/shared-history`));
      } else {
        setHistory(undefined);
      }
    } catch (err) {
      // A doctor-only grant held by a colleague shows as access but refuses this user
      if (err instanceof ApiError && err.code === 'NO_RECORD_ACCESS') setHistory(undefined);
      else setError(err instanceof ApiError ? err.message : 'Could not load shared records.');
    }
  }, [patientId, clinical]);

  useEffect(() => { load(); }, [load]);

  async function openDocument(id: string) {
    const tab = window.open('', '_blank');
    try {
      const res = await api.get<{ pages: { url: string }[] }>(`/clinic/patients/${patientId}/shared-history/documents/${id}`);
      if (tab) tab.location.href = res.pages[0]?.url ?? 'about:blank';
    } catch (err) {
      tab?.close();
      setError(err instanceof ApiError ? err.message : 'Could not open the document.');
    }
  }

  if (asking) {
    return <AccessRequestFlow patientId={patientId} onCancel={() => setAsking(false)}
                              onGranted={() => { setAsking(false); load(); }} />;
  }

  return (
    <div className="card">
      <div className="between">
        <h2>Records from other clinics</h2>
        <button onClick={() => setAsking(true)}>{grants?.length ? 'Ask again' : 'Ask patient to share'}</button>
      </div>
      {error && <div className="alert error">{error}</div>}
      {grants && grants.length === 0 && (
        <p className="muted small">
          This clinic can't see records from other clinics for this patient. The patient approves with a code sent
          to their phone, or in the ClinicEMR app.
        </p>
      )}
      {grants && grants.length > 0 && (
        <p className="small">
          Shared until <b>{dateTime(grants[0].expiresAt)}</b>
          {' · '}{grants[0].scopeTypes.map((t) => t === 'VISITS' ? 'visits' : 'documents').join(' and ')}
          {grants[0].fromDate ? ` from ${date(grants[0].fromDate)}` : ''}
          {grants[0].doctorName ? ` · only Dr. ${grants[0].doctorName.replace(/^Dr\.?\s*/i, '')}` : ''}
          {' · '}approved {grants[0].method === 'APP' ? 'in the app' : 'with a code'}
        </p>
      )}
      {grants && grants.length > 0 && !clinical && <p className="muted small">Doctors and nurses can view these records.</p>}

      {history && (
        <>
          {history.allergies.length > 0 && (
            <div className="allergy-band">
              <b className="small">Allergies elsewhere:</b>
              {history.allergies.map((a, i) => (
                <span key={i} className="chip allergy" title={`Recorded at ${a.clinicName}`}>
                  {a.substance}{a.severity ? ` · ${a.severity.toLowerCase()}` : ''} · {a.clinicName}
                </span>
              ))}
            </div>
          )}
          {history.encounters.length === 0 && history.documents.length === 0 && (
            <p className="muted small">No records from other clinics within what was shared.</p>
          )}
          {history.encounters.map((e) => (
            <div key={e.encounterId} className="card tight" style={{ background: 'var(--surface-2)' }}>
              <button className="link" style={{ textAlign: 'left', padding: 0 }} aria-expanded={open === e.encounterId}
                      onClick={() => setOpen(open === e.encounterId ? undefined : e.encounterId)}>
                <b>{dateTime(e.date)}</b> · {e.clinicName} · {e.doctorName}
                {e.version > 1 && <span className="chip" style={{ marginLeft: 6 }}>amended</span>}
              </button>
              <div className="small">{e.content.diagnoses.map((d) => d.display).join('; ')}</div>
              {open === e.encounterId && (
                <dl className="signed-view" style={{ margin: 0 }}>
                  {e.content.chiefComplaints && <><dt>Complaints</dt><dd>{e.content.chiefComplaints}</dd></>}
                  {e.content.examination && <><dt>Examination</dt><dd>{e.content.examination}</dd></>}
                  {e.content.treatmentGiven && <><dt>Treatment given</dt><dd>{e.content.treatmentGiven}</dd></>}
                  {e.content.prescription.length > 0 && (
                    <><dt>Rx</dt><dd>{e.content.prescription.map((r, i) => (
                      <div key={i}>{i + 1}. <b>{r.drugDisplay}</b> {[r.dose, r.frequency, r.timing].filter(Boolean).join(' · ')}
                        {r.longTerm ? ' · continue' : r.durationDays ? ` · ${r.durationDays} days` : ''}</div>
                    ))}</dd></>
                  )}
                  {e.content.investigations.length > 0 && <><dt>Investigations</dt><dd>{e.content.investigations.map((i) => i.display).join(', ')}</dd></>}
                  {e.content.advice && <><dt>Advice</dt><dd>{e.content.advice}</dd></>}
                </dl>
              )}
            </div>
          ))}
          {history.documents.length > 0 && (
            <>
              <h3>Documents</h3>
              {history.documents.map((d) => (
                <div key={d.id} className="between small">
                  <span>
                    <b>{d.title || d.docType.replace(/_/g, ' ').toLowerCase()}</b>
                    {' · '}{d.source === 'PATIENT' ? 'uploaded by patient' : d.clinicName}
                    {d.recordDate ? ` · ${date(d.recordDate)}` : ''} · {d.pages} page{d.pages === 1 ? '' : 's'}
                  </span>
                  <button className="link" onClick={() => openDocument(d.id)}>Open</button>
                </div>
              ))}
            </>
          )}
        </>
      )}
    </div>
  );
}
