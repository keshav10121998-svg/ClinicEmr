import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { DuplicateCandidate, Patient } from '../types';
import { ageSex, date } from '../format';
import { AccessRequestFlow } from '../components/AccessRequestFlow';
import { PhoneInput, toMobileDigits } from '../../shared/PhoneInput';

interface RecentPatient {
  publicId: string;
  mrn: string;
  name: string;
  gender: Patient['gender'];
  dobEstimated: boolean;
  ageYears?: number;
  phone?: string;
  lastVisitAt?: string;
  visitCount: number;
  registeredAt: string;
}

/** Home screen: the clinic's patients, most recently seen first. Tap one to open; typing only narrows the list. */
export function PatientsPage() {
  const navigate = useNavigate();
  const [patients, setPatients] = useState<RecentPatient[]>();
  const [error, setError] = useState<string>();
  const [filter, setFilter] = useState('');
  const [showRegister, setShowRegister] = useState(false);
  const [asking, setAsking] = useState(false);

  useEffect(() => {
    api.get<RecentPatient[]>('/clinic/patients/recent?limit=300')
      .then(setPatients)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load patients.'));
  }, []);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!patients || !q) return patients;
    const digits = q.replace(/\D/g, '');
    return patients.filter((p) =>
      p.name.toLowerCase().includes(q)
      || p.mrn.toLowerCase().includes(q)
      || (digits.length >= 3 && (p.phone ?? '').replace(/\D/g, '').includes(digits)));
  }, [patients, filter]);

  const filterIsPhone = filter.replace(/\D/g, '').length >= 10;

  return (
    <>
      <div className="between">
        <h1>Patients</h1>
        <button className="primary desktop-only" onClick={() => setShowRegister((s) => !s)}>
          {showRegister ? 'Close' : '+ New patient'}
        </button>
      </div>

      {showRegister && (
        <button type="button" className="phone-only" onClick={() => setShowRegister(false)}>Cancel new patient</button>
      )}
      {showRegister && (
        <RegisterPatient
          initialPhone={filterIsPhone ? filter.trim() : ''}
          initialName={!filterIsPhone && /[a-z]/i.test(filter) ? filter.trim() : ''}
          onDone={(p) => navigate(`/patients/${p.publicId}`)}
        />
      )}

      <div className="card">
        <input id="patientFilter" aria-label="Type to narrow the list" placeholder="Type a name, mobile number or patient ID to narrow the list"
               value={filter} onChange={(e) => { setFilter(e.target.value); setAsking(false); }} />
        {error && <div className="alert error">{error}</div>}
        {!patients && !error && <p className="muted">Loading…</p>}
        {patients?.length === 0 && (
          <div className="alert info">No patients yet. Tap <b>+ New patient</b> to register the first one.</div>
        )}
        {shown && shown.length === 0 && patients && patients.length > 0 && (
          <div className="alert info">
            No patient here matches “{filter.trim()}”.
            <div className="row" style={{ marginTop: 8 }}>
              <button type="button" onClick={() => setShowRegister(true)}>+ Register as new patient</button>
              {filterIsPhone && !asking && (
                <button type="button" onClick={() => setAsking(true)}>Visited another clinic? Ask to share records</button>
              )}
            </div>
          </div>
        )}
        {!showRegister && (
          <button type="button" className="fab" aria-label="New patient"
                  onClick={() => { setShowRegister(true); window.scrollTo({ top: 0 }); }}>+</button>
        )}
        <div className="patient-list">
          {shown?.map((p) => (
            <button key={p.publicId} className="patient-row" onClick={() => navigate(`/patients/${p.publicId}`)}>
              <span className="avatar" aria-hidden="true">{initials(p.name)}</span>
              <span className="patient-name">{p.name}</span>
              <span className="muted small">{ageSex(p)} · <span className="mono">{p.mrn}</span>{p.phone ? ` · ${p.phone.replace(/^\+91/, '')}` : ''}</span>
              <span className="small last-visit">{p.lastVisitAt ? `Last visit ${date(p.lastVisitAt)}` : 'No visits yet'}
                {p.visitCount > 0 && <><br /><b className="visit-total">{p.visitCount} {p.visitCount === 1 ? 'visit' : 'visits'}</b></>}</span>
            </button>
          ))}
        </div>
      </div>

      {asking && filterIsPhone && (
        <AccessRequestFlow phone={filter.trim()} onCancel={() => setAsking(false)}
                           onGranted={(g) => navigate(`/patients/${g.profileId}`)} />
      )}
    </>
  );
}

/** Short form: name, sex, age and mobile. Date of birth, ABHA and blood group are under "More". */
function RegisterPatient({ initialPhone, initialName, onDone }:
  { initialPhone: string; initialName: string; onDone: (p: Patient) => void }) {
  const [f, setF] = useState({ name: initialName, gender: '', ageYears: '', dob: '', phone: toMobileDigits(initialPhone), abhaNumber: '', bloodGroup: '' });
  const [more, setMore] = useState(false);
  const [error, setError] = useState<ApiError>();
  const [duplicates, setDuplicates] = useState<DuplicateCandidate[]>();
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.value });

  async function submit(confirmNew: boolean) {
    setBusy(true);
    setError(undefined);
    try {
      const p = await api.post<Patient>('/clinic/patients', {
        name: f.name,
        gender: f.gender,
        dob: f.dob || undefined,
        ageYears: f.dob ? undefined : f.ageYears ? Number(f.ageYears) : undefined,
        phone: f.phone,
        abhaNumber: f.abhaNumber || undefined,
        bloodGroup: f.bloodGroup || undefined,
        confirmNew,
      });
      onDone(p);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'POSSIBLE_DUPLICATE') {
        setDuplicates(err.problem.candidates);
      } else {
        setError(err instanceof ApiError ? err : undefined);
      }
    } finally {
      setBusy(false);
    }
  }

  const fieldErrors = error?.problem.fields as Record<string, string> | undefined;

  return (
    <form className="card" onSubmit={(e) => { e.preventDefault(); submit(false); }}>
      <h2>New patient</h2>
      {error && (
        <div className="alert error">
          {error.message}
          {fieldErrors && <ul>{Object.entries(fieldErrors).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul>}
        </div>
      )}
      <div className="fields">
        <label className="field" style={{ gridColumn: '1 / -1' }}>Full name
          <input id="regName" required autoFocus value={f.name} onChange={set('name')} />
        </label>
        <div className="field" role="radiogroup" aria-label="Sex" style={{ gridColumn: '1 / -1' }}>
          Sex
          <div className="segmented">
            {[['F', 'Female'], ['M', 'Male'], ['O', 'Other']].map(([v, l]) => (
              <button type="button" key={v} aria-pressed={f.gender === v} onClick={() => setF({ ...f, gender: v })}>{l}</button>
            ))}
          </div>
        </div>
        <label className="field">Age (years)
          <input id="regAge" type="number" inputMode="numeric" min={0} max={130} value={f.ageYears} onChange={set('ageYears')} disabled={!!f.dob} />
        </label>
        <label className="field">Mobile number
          <PhoneInput id="regPhone" required value={f.phone} onChange={(v) => setF({ ...f, phone: v })} />
        </label>
      </div>
      {more ? (
        <div className="fields">
          <label className="field">Date of birth (instead of age)
            <input id="regDob" type="date" value={f.dob} onChange={set('dob')} max={new Date().toISOString().slice(0, 10)} />
          </label>
          <label className="field">ABHA number
            <input id="regAbha" value={f.abhaNumber} onChange={set('abhaNumber')} placeholder="14 digits" />
          </label>
          <label className="field">Blood group
            <select id="regBlood" value={f.bloodGroup} onChange={set('bloodGroup')}>
              <option value="">Unknown</option>
              {['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'].map((b) => <option key={b}>{b}</option>)}
            </select>
          </label>
        </div>
      ) : (
        <button type="button" className="link" style={{ justifySelf: 'start' }} onClick={() => setMore(true)}>+ Date of birth, ABHA, blood group</button>
      )}

      {duplicates && (
        <div className="alert warn">
          <b>This clinic may already have this patient.</b> Open the existing record if it's the same person.
          <ul>
            {duplicates.map((d) => (
              <li key={d.publicId}>
                <a href={`/patients/${d.publicId}`}>{d.name}</a> · {d.ageYears ?? '?'} yrs · {d.gender} · <span className="mono">{d.mrn}</span>
              </li>
            ))}
          </ul>
          <button type="button" onClick={() => submit(true)} disabled={busy}>It's a different person, register anyway</button>
        </div>
      )}

      <button className="primary block" disabled={busy || !f.gender}>{busy ? 'Saving…' : 'Register'}</button>
    </form>
  );
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}
