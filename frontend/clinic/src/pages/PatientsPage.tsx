import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { DuplicateCandidate, Patient } from '../types';
import { ageSex } from '../format';

/** Front desk home: find a patient by phone, name or patient ID, or register a new one. */
export function PatientsPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Patient[]>();
  const [searchError, setSearchError] = useState<string>();
  const [searching, setSearching] = useState(false);
  const [showRegister, setShowRegister] = useState(false);

  async function search(e: FormEvent) {
    e.preventDefault();
    const q = query.trim();
    if (q.length < 2) return;
    setSearching(true);
    setSearchError(undefined);
    try {
      const digits = q.replace(/\D/g, '');
      const looksLikePhone = digits.length >= 10 && digits.length >= q.replace(/\s/g, '').length - 1;
      setResults(looksLikePhone
        ? await api.post<Patient[]>('/clinic/patients/search-by-phone', { phone: q })
        : await api.get<Patient[]>(`/clinic/patients?q=${encodeURIComponent(q)}`));
    } catch (err) {
      setSearchError(err instanceof ApiError ? err.message : 'Search failed.');
    } finally {
      setSearching(false);
    }
  }

  return (
    <>
      <div className="between">
        <h1>Patients</h1>
        <button className="primary" onClick={() => setShowRegister((s) => !s)}>
          {showRegister ? 'Close' : 'Register new patient'}
        </button>
      </div>

      {showRegister && (
        <RegisterPatient
          initialPhone={/^\+?[\d\s-]{10,}$/.test(query.trim()) ? query.trim() : ''}
          onDone={(p) => navigate(`/patients/${p.publicId}`)}
        />
      )}

      <form className="card" onSubmit={search}>
        <label className="field">Find a patient by mobile number, name or patient ID
          <div className="row" style={{ flexWrap: 'nowrap' }}>
            <input id="patientSearch" autoFocus placeholder="98765 43210, Kamla, P000123" value={query}
                   onChange={(e) => setQuery(e.target.value)} />
            <button className="primary" disabled={searching || query.trim().length < 2}>Search</button>
          </div>
        </label>
        {searchError && <div className="alert error">{searchError}</div>}
        {results && (results.length === 0 ? (
          <div className="alert info">
            No patient at this clinic matches. Register them, or if they've visited another clinic on ClinicEMR,
            ask them to approve sharing their records.
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Name</th><th>Age / sex</th><th>Patient ID</th><th>Mobile</th></tr>
              </thead>
              <tbody>
                {results.map((p) => (
                  <tr key={p.publicId} className="clickable" tabIndex={0}
                      onClick={() => navigate(`/patients/${p.publicId}`)}
                      onKeyDown={(e) => e.key === 'Enter' && navigate(`/patients/${p.publicId}`)}>
                    <td><b>{p.name}</b></td>
                    <td>{ageSex(p)}</td>
                    <td className="mono">{p.mrn}</td>
                    <td className="mono">{p.phone}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </form>
    </>
  );
}

function RegisterPatient({ initialPhone, onDone }: { initialPhone: string; onDone: (p: Patient) => void }) {
  const [f, setF] = useState({ name: '', gender: '', ageYears: '', dob: '', phone: initialPhone, abhaNumber: '', bloodGroup: '' });
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
      <h2>Register new patient</h2>
      {error && (
        <div className="alert error">
          {error.message}
          {fieldErrors && <ul>{Object.entries(fieldErrors).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul>}
        </div>
      )}
      <div className="fields">
        <label className="field" style={{ gridColumn: 'span 2' }}>Full name
          <input id="regName" required autoFocus value={f.name} onChange={set('name')} />
        </label>
        <label className="field">Sex
          <select id="regGender" required value={f.gender} onChange={set('gender')}>
            <option value="">Choose</option>
            <option value="F">Female</option>
            <option value="M">Male</option>
            <option value="O">Other</option>
          </select>
        </label>
        <label className="field">Age (years)
          <input id="regAge" type="number" min={0} max={130} value={f.ageYears} onChange={set('ageYears')} disabled={!!f.dob} />
        </label>
        <label className="field">or date of birth
          <input id="regDob" type="date" value={f.dob} onChange={set('dob')} max={new Date().toISOString().slice(0, 10)} />
        </label>
        <label className="field">Mobile number
          <input id="regPhone" type="tel" required value={f.phone} onChange={set('phone')} placeholder="98765 43210" />
        </label>
        <label className="field">ABHA number (optional)
          <input id="regAbha" value={f.abhaNumber} onChange={set('abhaNumber')} placeholder="14 digits" />
        </label>
        <label className="field">Blood group
          <select id="regBlood" value={f.bloodGroup} onChange={set('bloodGroup')}>
            <option value="">Unknown</option>
            {['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'].map((b) => <option key={b}>{b}</option>)}
          </select>
        </label>
      </div>

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

      <div className="row end">
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Register'}</button>
      </div>
    </form>
  );
}
