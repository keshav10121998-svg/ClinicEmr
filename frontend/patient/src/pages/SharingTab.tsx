import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import type { AccessLogEntry, Grant, Profile } from '../types';
import { date, dateTime } from '../format';

const ACTIONS: Record<string, string> = {
  VIEW_SHARED_HISTORY: 'viewed visits',
  VIEW_SHARED_DOCUMENT: 'opened a document',
  CHECK_SHARED_ALLERGIES: 'checked allergies while prescribing',
};

/** Which clinics can see records now (with a way to stop it), and every time someone looked. */
export function SharingTab({ profiles }: { profiles: Profile[] }) {
  const [grants, setGrants] = useState<Grant[]>();
  const [log, setLog] = useState<AccessLogEntry[]>();
  const [error, setError] = useState<string>();
  const [confirm, setConfirm] = useState<string>();

  const load = useCallback(() => {
    Promise.all([api.get<Grant[]>('/patient/access'), api.get<AccessLogEntry[]>('/patient/access-log')])
      .then(([g, l]) => { setGrants(g); setLog(l); })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load sharing.'));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function revoke(grantId: string) {
    setError(undefined);
    try {
      await api.del(`/patient/access/${grantId}`);
      setConfirm(undefined);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not stop sharing.');
    }
  }

  return (
    <>
      {error && <div className="alert error">{error}</div>}
      <div className="card">
        <h3>Who can see records now</h3>
        {grants?.length === 0 && (
          <p className="muted">No clinic can see your family's records from other clinics right now.
            {profiles.length > 0 && ' Each clinic still sees the visits it recorded itself.'}</p>
        )}
        <div className="list">
          {grants?.map((g) => (
            <div key={g.grantId} style={{ display: 'grid', gap: 4 }}>
              <div className="between"><b>{g.clinicName}</b><span className="small muted">until {dateTime(g.expiresAt)}</span></div>
              <div className="small">
                {g.patientName} · {g.scopeTypes.map((t) => (t === 'VISITS' ? 'visits' : 'documents')).join(' and ')}
                {g.fromDate ? ` from ${date(g.fromDate)}` : ''}
                {g.doctorName ? ` · only ${g.doctorName}` : ''}
              </div>
              <div className="row">
                {confirm === g.grantId ? (
                  <>
                    <button className="danger" onClick={() => revoke(g.grantId)}>Yes, stop sharing</button>
                    <button onClick={() => setConfirm(undefined)}>Keep</button>
                  </>
                ) : (
                  <button onClick={() => setConfirm(g.grantId)}>Stop sharing</button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="card">
        <h3>Who looked</h3>
        {log?.length === 0 && <p className="muted">Nobody from another clinic has looked at your records.</p>}
        <div className="list">
          {log?.map((l, i) => (
            <div key={i} className="small">
              <b>{l.actorName}</b> at {l.clinicName ?? 'ClinicEMR'} {ACTIONS[l.action] ?? l.action.toLowerCase().replace(/_/g, ' ')}
              {' '}for {l.patientName}
              <div className="muted">{dateTime(l.at)}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
