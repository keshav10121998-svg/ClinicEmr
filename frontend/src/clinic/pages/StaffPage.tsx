import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { roleLabel } from '../App';
import { PhoneInput, isMobile, toMobileDigits } from '../../shared/PhoneInput';
import type { Role, StaffMember } from '../types';

const ROLES: Role[] = ['DOCTOR', 'NURSE', 'FRONT_DESK', 'ADMIN'];

/** Admins add doctors, nurses and front desk staff. A doctor who already uses ClinicEMR elsewhere joins by email. */
export function StaffPage() {
  const [staff, setStaff] = useState<StaffMember[]>([]);
  const [error, setError] = useState<string>();

  useEffect(() => {
    api.get<StaffMember[]>('/clinic/staff').then(setStaff).catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load staff.'));
  }, []);

  // The mobile number someone can sign in with (their password doesn't change). Empty removes it.
  async function editPhone(s: StaffMember) {
    const typed = window.prompt(`10-digit mobile number ${s.name} can sign in with (leave empty to remove):`, s.phone ?? '');
    if (typed === null) return;
    const digits = toMobileDigits(typed);
    if (digits && !isMobile(digits)) {
      window.alert('Enter exactly 10 digits, starting with 6, 7, 8 or 9.');
      return;
    }
    setError(undefined);
    try {
      const updated = await api.put<StaffMember>(`/clinic/staff/${s.staffId}/phone`, { phone: digits });
      setStaff((list) => list.map((x) => (x.staffId === updated.staffId ? updated : x)));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save the mobile number.');
    }
  }

  return (
    <>
      <h1>Staff</h1>
      {error && <div className="alert error">{error}</div>}
      <div className="card">
        <h2>Team</h2>
        <div className="staff-list">
          {staff.map((s) => (
            <div key={s.staffId} className="staff-row">
              <b>{s.name}</b>
              <span className="chip ok">{s.roles.map(roleLabel).join(', ')}</span>
              <span className="muted small" style={{ overflowWrap: 'anywhere' }}>{s.email}</span>
              <span className="small">
                {s.phone ? <>Mobile <span className="mono">{s.phone}</span> </> : <span className="muted">No mobile for sign-in </span>}
                <button type="button" className="link small" onClick={() => editPhone(s)}>{s.phone ? 'Change' : 'Add mobile'}</button>
              </span>
              {s.doctor && <span className="small">{s.doctor.registrationNo} · {s.doctor.registrationCouncil}</span>}
            </div>
          ))}
        </div>
      </div>
      <AddStaff onAdded={(s) => setStaff((list) => [...list.filter((x) => x.staffId !== s.staffId), s])} />
    </>
  );
}

function AddStaff({ onAdded }: { onAdded: (s: StaffMember) => void }) {
  const blank = { name: '', email: '', phone: '', password: '', registrationNo: '', registrationCouncil: '', qualifications: '', specialty: '' };
  const [f, setF] = useState(blank);
  const [roles, setRoles] = useState<Role[]>([]);
  const [error, setError] = useState<ApiError>();
  const [done, setDone] = useState<string>();
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const isDoctor = roles.includes('DOCTOR');

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    setDone(undefined);
    try {
      const s = await api.post<StaffMember>('/clinic/staff', {
        name: f.name, email: f.email, phone: f.phone || undefined, password: f.password || undefined, roles,
        doctor: isDoctor ? { registrationNo: f.registrationNo, registrationCouncil: f.registrationCouncil,
                             qualifications: f.qualifications, specialty: f.specialty || undefined } : undefined,
      });
      onAdded(s);
      setDone(`${s.name} can now sign in with ${s.email}${s.phone ? ` or ${s.phone}` : ''}.`);
      setF(blank);
      setRoles([]);
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
    } finally {
      setBusy(false);
    }
  }

  const fieldErrors = error?.problem.fields as Record<string, string> | undefined;

  return (
    <form className="card" onSubmit={submit}>
      <h2>Add a staff member</h2>
      {error && (
        <div className="alert error">
          {error.message}
          {fieldErrors && <ul>{Object.entries(fieldErrors).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul>}
        </div>
      )}
      {done && <div className="alert info">{done}</div>}
      <div className="fields">
        <label className="field">Full name<input id="staffName" required value={f.name} onChange={set('name')} /></label>
        <label className="field">Email<input id="staffEmail" type="email" required value={f.email} onChange={set('email')} /></label>
        <label className="field">Mobile (can sign in with it)
          <PhoneInput id="staffPhone" value={f.phone} onChange={(v) => setF({ ...f, phone: v })} />
        </label>
        <label className="field">Password (new staff, 10+ characters)
          <input id="staffPassword" type="password" minLength={10} value={f.password} onChange={set('password')} />
        </label>
      </div>
      <div className="row" role="group" aria-label="Roles">
        <span className="small muted">Roles:</span>
        {ROLES.map((r) => (
          <label key={r} className="check">
            <input type="checkbox" checked={roles.includes(r)}
                   onChange={(e) => setRoles(e.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} />
            {roleLabel(r)}
          </label>
        ))}
      </div>
      {isDoctor && (
        <div className="fields">
          <label className="field">Registration no.<input id="staffReg" required value={f.registrationNo} onChange={set('registrationNo')} /></label>
          <label className="field">Medical council<input id="staffCouncil" required value={f.registrationCouncil} onChange={set('registrationCouncil')} /></label>
          <label className="field">Qualifications<input id="staffQuals" required value={f.qualifications} onChange={set('qualifications')} /></label>
          <label className="field">Specialty<input id="staffSpecialty" value={f.specialty} onChange={set('specialty')} /></label>
        </div>
      )}
      <p className="small muted">
        If the person already uses ClinicEMR at another clinic, enter their email and leave the password empty;
        they keep their existing sign-in.
      </p>
      <div className="row end"><button className="primary" disabled={busy || roles.length === 0}>{busy ? 'Adding…' : 'Add staff member'}</button></div>
    </form>
  );
}
