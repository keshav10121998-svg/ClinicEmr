import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, openAuthed } from '../api';
import type { VisitDetail } from '../types';
import { date, dateTime } from '../format';

export function VisitPage() {
  const { profileId, encounterId } = useParams();
  const navigate = useNavigate();
  const [visit, setVisit] = useState<VisitDetail>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    api.get<VisitDetail>(`/patient/profiles/${profileId}/visits/${encounterId}`)
      .then(setVisit)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load this visit.'));
  }, [profileId, encounterId]);

  async function download() {
    setError(undefined);
    try {
      await openAuthed(`/patient/profiles/${profileId}/visits/${encounterId}/prescription.pdf`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not open the prescription.');
    }
  }

  const c = visit?.content;
  return (
    <div className="shell">
      <header className="top">
        <button className="link" onClick={() => navigate(`/?p=${profileId}`)}>← Back</button>
      </header>
      <main>
        {error && <div className="alert error">{error}</div>}
        {!visit && !error && <p className="muted">Loading…</p>}
        {visit && c && (
          <>
            <div>
              <h1>{dateTime(visit.date)}</h1>
              <p className="muted">{visit.clinicName} · {visit.doctorName} <span className="small">({visit.doctorQualifications})</span></p>
              {visit.amended && <p className="small muted">The doctor updated this record after the visit. This is the latest version.</p>}
            </div>
            <button className="primary block" onClick={download}>Download prescription (PDF)</button>
            <div className="card">
              <dl className="visit" style={{ margin: 0 }}>
                {c.diagnoses.length > 0 && <><dt>Diagnosis</dt><dd>{c.diagnoses.map((d) => d.display).join('; ')}</dd></>}
                {c.chiefComplaints && <><dt>Complaints</dt><dd>{c.chiefComplaints}</dd></>}
                {c.treatmentGiven && <><dt>Treatment given</dt><dd>{c.treatmentGiven}</dd></>}
                {c.prescription.length > 0 && (
                  <>
                    <dt>Medicines</dt>
                    <dd className="rx">
                      {c.prescription.map((r, i) => (
                        <div key={i}>
                          <b>{r.drugDisplay}</b>
                          <div className="small">{[r.dose, r.frequency, r.timing].filter(Boolean).join(' · ')}
                            {r.longTerm ? ' · continue until the doctor says' : r.durationDays ? ` · for ${r.durationDays} days` : ''}</div>
                          {r.instructions && <div className="small muted">{r.instructions}</div>}
                        </div>
                      ))}
                    </dd>
                  </>
                )}
                {c.investigations.length > 0 && <><dt>Tests advised</dt><dd>{c.investigations.map((t) => t.display).join(', ')}</dd></>}
                {c.advice && <><dt>Advice</dt><dd>{c.advice}</dd></>}
                {c.followUp && <><dt>Next visit</dt><dd>{date(c.followUp.dueDate)}{c.followUp.note ? ` · ${c.followUp.note}` : ''}</dd></>}
              </dl>
            </div>
          </>
        )}
      </main>
    </div>
  );
}
