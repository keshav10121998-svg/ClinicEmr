import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';
import type { AccessRequestView, GrantView } from '../types';
import { dateTime, sexLabel } from '../format';

interface Props {
  /** Start from a phone number (patient not at this clinic yet) or from a patient this clinic already has. */
  phone?: string;
  patientId?: string;
  onGranted: (grant: GrantView) => void;
  onCancel: () => void;
}

const DURATIONS = [
  { value: 'VISIT', label: 'This visit (24 hours)' },
  { value: 'DAYS_30', label: '30 days' },
  { value: 'YEAR_1', label: 'Ongoing care (1 year)' },
];

/**
 * The counter flow for records held at other clinics: the patient gets a code by SMS (and an Approve prompt in the
 * app). After they approve, staff pick the person on that number and what they agreed to share.
 */
export function AccessRequestFlow({ phone, patientId, onGranted, onCancel }: Props) {
  const { has } = useSession();
  const [req, setReq] = useState<AccessRequestView>();
  const [error, setError] = useState<string>();
  const [otp, setOtp] = useState('');
  const [profileId, setProfileId] = useState<string>();
  const [types, setTypes] = useState({ VISITS: true, DOCUMENTS: true });
  const [fromDate, setFromDate] = useState('');
  const [duration, setDuration] = useState('VISIT');
  const [onlyMe, setOnlyMe] = useState(false);
  const [busy, setBusy] = useState(false);

  // Start exactly once: each start texts the patient and counts toward the hourly limit, and React's
  // development mode runs effects twice
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    api.post<AccessRequestView>('/clinic/access-requests', patientId ? { patientId } : { phone })
      .then(setReq)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not send the code.'));
  }, [phone, patientId]);

  // While waiting, check every few seconds whether the patient approved in the app
  useEffect(() => {
    if (req?.status !== 'PENDING') return;
    const t = window.setInterval(async () => {
      try {
        const r = await api.get<AccessRequestView>(`/clinic/access-requests/${req.requestId}`);
        if (r.status !== 'PENDING') setReq(r);
      } catch {
        // keep waiting
      }
    }, 3000);
    return () => window.clearInterval(t);
  }, [req?.status, req?.requestId]);

  // Approved in the app: the patient already chose what to share
  useEffect(() => {
    if (req?.status === 'APPROVED' && req.grants.length > 0) onGranted(req.grants[0]);
  }, [req, onGranted]);

  // Starting from a known patient: preselect them once the list appears
  useEffect(() => {
    if (req?.profiles.length && !profileId) {
      setProfileId(req.profiles.length === 1 ? req.profiles[0].profileId : undefined);
    }
  }, [req?.profiles, profileId]);

  async function verify(e: FormEvent) {
    e.preventDefault();
    if (!req) return;
    setBusy(true);
    setError(undefined);
    try {
      setReq(await api.post<AccessRequestView>(`/clinic/access-requests/${req.requestId}/verify`, { otp }));
    } catch (err) {
      if (err instanceof ApiError && err.code === 'OTP_INVALID') {
        setError(`That code is not right. ${err.problem.attemptsLeft} tries left.`);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not check the code.');
      }
      setOtp('');
    } finally {
      setBusy(false);
    }
  }

  async function grant(e: FormEvent) {
    e.preventDefault();
    if (!req || !profileId) return;
    setBusy(true);
    setError(undefined);
    try {
      const scopeTypes = (Object.keys(types) as (keyof typeof types)[]).filter((k) => types[k]);
      const r = await api.post<AccessRequestView>(`/clinic/access-requests/${req.requestId}/grant`, {
        profileId,
        onlyMe,
        scope: { types: scopeTypes, duration, fromDate: fromDate || undefined },
      });
      setReq(r);
      const g = r.grants.find((x) => x.profileId === profileId);
      if (g) onGranted(g);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" role="region" aria-label="Patient approval">
      <div className="between">
        <h2>Records from other clinics</h2>
        <button className="link" onClick={onCancel}>Close</button>
      </div>
      {error && <div className="alert error">{error}</div>}
      {!req && !error && <p className="muted">Sending the code…</p>}

      {req?.status === 'PENDING' && (
        <form className="grid-tight" onSubmit={verify} style={{ display: 'grid', gap: 10 }}>
          <p>
            We sent a code to <b className="mono">{req.phone}</b>. Ask the patient to read it out, or to tap
            <b> Approve</b> in the ClinicEMR app. The code works for 10 minutes.
          </p>
          <div className="row" style={{ flexWrap: 'nowrap', maxWidth: 360 }}>
            <input id="otp" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required
                   placeholder="6-digit code" value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))} autoFocus />
            <button className="primary" disabled={busy || otp.length !== 6}>Check code</button>
          </div>
          <p className="small muted">Waiting for approval in the app…</p>
        </form>
      )}

      {req?.status === 'VERIFIED' && (
        <form onSubmit={grant} style={{ display: 'grid', gap: 12 }}>
          {req.profiles.length === 0 ? (
            <div className="alert info">Nobody with this number has records on ClinicEMR yet. Register them as a new patient.</div>
          ) : (
            <>
              <div>
                <h3>Who is the patient?</h3>
                {req.profiles.map((p) => (
                  <label key={p.profileId} className="check" style={{ display: 'flex', padding: '4px 0' }}>
                    <input type="radio" name="profile" checked={profileId === p.profileId} onChange={() => setProfileId(p.profileId)} />
                    <b>{p.name}</b>&nbsp;<span className="muted">{[p.ageYears != null ? `${p.ageYears} yrs` : '', sexLabel(p.gender)].filter(Boolean).join(' · ')}
                      {p.linkedToThisClinic ? ` · already here (${p.mrn})` : ''}</span>
                  </label>
                ))}
              </div>
              <div>
                <h3>What the patient agreed to share</h3>
                <div className="row">
                  <label className="check"><input type="checkbox" checked={types.VISITS} onChange={(e) => setTypes({ ...types, VISITS: e.target.checked })} /> Visits, prescriptions and allergies</label>
                  <label className="check"><input type="checkbox" checked={types.DOCUMENTS} onChange={(e) => setTypes({ ...types, DOCUMENTS: e.target.checked })} /> Reports and documents</label>
                </div>
              </div>
              <div className="fields">
                <label className="field">For how long
                  <select id="duration" value={duration} onChange={(e) => setDuration(e.target.value)}>
                    {DURATIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
                  </select>
                </label>
                <label className="field">Only records from (optional)
                  <input id="fromDate" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} max={new Date().toISOString().slice(0, 10)} />
                </label>
              </div>
              {has('DOCTOR') && (
                <label className="check"><input type="checkbox" checked={onlyMe} onChange={(e) => setOnlyMe(e.target.checked)} /> Only I can see them (not other staff here)</label>
              )}
              <p className="small muted">The patient gets an SMS confirming this and can stop it any time in the app. Every view is logged for them.</p>
              <div className="row end">
                <button className="primary" disabled={busy || !profileId || (!types.VISITS && !types.DOCUMENTS)}>Confirm sharing</button>
              </div>
            </>
          )}
        </form>
      )}

      {req?.status === 'APPROVED' && <div className="alert info">Approved in the app.</div>}
      {req?.status === 'DENIED' && <div className="alert warn">The patient declined in the app.</div>}
      {req?.status === 'LOCKED' && <div className="alert error">Too many wrong codes. Close this and start again.</div>}
      {req && req.status !== 'LOCKED' && new Date(req.expiresAt) < new Date() && req.grants.length === 0 && (
        <div className="alert warn">This request expired at {dateTime(req.expiresAt)}. Close it and start again.</div>
      )}
    </div>
  );
}
