import { useState } from 'react';
import { api, ApiError } from '../api';
import type { PendingRequest, Profile } from '../types';

const DURATIONS = [
  { value: 'VISIT', label: 'Just for today’s visit (24 hours)' },
  { value: 'DAYS_30', label: '30 days' },
  { value: 'YEAR_1', label: '1 year (my regular doctor)' },
];

/** A clinic is asking to see records. The patient chooses who, what and for how long, or declines. */
export function PendingRequests({ requests, profiles, onDone }:
  { requests: PendingRequest[]; profiles: Profile[]; onDone: () => void }) {
  return (
    <>
      {requests.map((r) => <RequestCard key={r.requestId} req={r} profiles={profiles} onDone={onDone} />)}
    </>
  );
}

function RequestCard({ req, profiles, onDone }: { req: PendingRequest; profiles: Profile[]; onDone: () => void }) {
  const [chosen, setChosen] = useState<string[]>(profiles.length === 1 ? [profiles[0].profileId] : []);
  const [visits, setVisits] = useState(true);
  const [documents, setDocuments] = useState(true);
  const [duration, setDuration] = useState('VISIT');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function approve() {
    setBusy(true);
    setError(undefined);
    try {
      const types = [visits && 'VISITS', documents && 'DOCUMENTS'].filter(Boolean);
      await api.post(`/patient/access-requests/${req.requestId}/approve`, { profileIds: chosen, scope: { types, duration } });
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not approve.');
      setBusy(false);
    }
  }

  async function deny() {
    setBusy(true);
    try {
      await api.post(`/patient/access-requests/${req.requestId}/deny`);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not decline.');
      setBusy(false);
    }
  }

  return (
    <section className="card request" aria-label={`Request from ${req.clinicName}`}>
      <div>
        <h2>{req.clinicName}{req.clinicCity ? `, ${req.clinicCity}` : ''} wants to see your records</h2>
        <p className="small muted">Requested by {req.requestedBy}. Only approve if you're at this clinic now.</p>
      </div>
      {error && <div className="alert error">{error}</div>}
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <h3>Whose records</h3>
        {profiles.map((p) => (
          <label key={p.profileId} className="check">
            <input type="checkbox" checked={chosen.includes(p.profileId)}
                   onChange={(e) => setChosen(e.target.checked ? [...chosen, p.profileId] : chosen.filter((x) => x !== p.profileId))} />
            {p.name}
          </label>
        ))}
      </fieldset>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <h3>What to share</h3>
        <label className="check"><input type="checkbox" checked={visits} onChange={(e) => setVisits(e.target.checked)} /> Visits, prescriptions and allergies</label>
        <label className="check"><input type="checkbox" checked={documents} onChange={(e) => setDocuments(e.target.checked)} /> Reports and documents</label>
      </fieldset>
      <label className="field">For how long
        <select value={duration} onChange={(e) => setDuration(e.target.value)}>
          {DURATIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
        </select>
      </label>
      <p className="small muted">Doctors' private notes are never shared. You can stop sharing at any time.</p>
      <div className="row">
        <button className="primary" disabled={busy || chosen.length === 0 || (!visits && !documents)} onClick={approve}>Approve</button>
        <button disabled={busy} onClick={deny}>Decline</button>
      </div>
    </section>
  );
}
