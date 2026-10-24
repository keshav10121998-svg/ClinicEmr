import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { VisitItem } from '../types';
import { dateTime } from '../format';

export function VisitsTab({ profileId }: { profileId: string }) {
  const navigate = useNavigate();
  const [visits, setVisits] = useState<VisitItem[]>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    setVisits(undefined);
    api.get<VisitItem[]>(`/patient/profiles/${profileId}/visits`)
      .then(setVisits)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load visits.'));
  }, [profileId]);

  if (error) return <div className="alert error">{error}</div>;
  if (!visits) return <p className="muted">Loading…</p>;
  if (visits.length === 0) {
    return <div className="card"><p className="muted">No visits yet. Visits appear here after the doctor finishes the record.</p></div>;
  }
  return (
    <div className="card">
      <h3>Visits from all clinics</h3>
      <div className="list">
        {visits.map((v) => (
          <button key={v.encounterId} className="link" style={{ textAlign: 'left', color: 'inherit', padding: '12px 0' }}
                  onClick={() => navigate(`/my/profiles/${profileId}/visits/${v.encounterId}`)}>
            <div className="between">
              <b>{dateTime(v.date)}</b>
              {v.version > 1 && <span className="chip">updated by doctor</span>}
            </div>
            <div>{v.clinicName}{v.clinicCity ? `, ${v.clinicCity}` : ''} · {v.doctorName}</div>
            <div className="small muted">{v.diagnoses}{v.medicines > 0 ? ` · ${v.medicines} medicine${v.medicines === 1 ? '' : 's'}` : ''}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
