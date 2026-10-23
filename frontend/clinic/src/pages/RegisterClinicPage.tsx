import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useSession } from '../session';

export function RegisterClinicPage() {
  const { login } = useSession();
  const [f, setF] = useState({
    clinicName: '', city: '', state: '', pincode: '', clinicPhone: '',
    name: '', email: '', password: '',
    isDoctor: true, registrationNo: '', registrationCouncil: '', qualifications: '',
  });
  const [error, setError] = useState<ApiError>();
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await api.post('/clinics', {
        clinic: { name: f.clinicName, city: f.city || undefined, state: f.state || undefined,
                  pincode: f.pincode || undefined, phone: f.clinicPhone || undefined },
        admin: {
          name: f.name, email: f.email, password: f.password,
          doctor: f.isDoctor ? { registrationNo: f.registrationNo, registrationCouncil: f.registrationCouncil,
                                 qualifications: f.qualifications } : undefined,
        },
      });
      await login(f.email, f.password);
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
      setBusy(false);
    }
  }

  const fieldErrors = error?.problem.fields as Record<string, string> | undefined;

  return (
    <div className="auth">
      <form className="card" onSubmit={submit} style={{ width: 'min(640px, 100%)' }}>
        <div>
          <h1>Set up your clinic</h1>
          <p className="muted small">You'll be the clinic's admin. Add other staff after signing in.</p>
        </div>
        {error && (
          <div className="alert error">
            {error.message}
            {fieldErrors && <ul>{Object.entries(fieldErrors).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul>}
          </div>
        )}
        <h3>Clinic</h3>
        <div className="fields">
          <label className="field" style={{ gridColumn: '1 / -1' }}>Clinic name
            <input id="clinicName" required value={f.clinicName} onChange={set('clinicName')} />
          </label>
          <label className="field">City<input id="city" value={f.city} onChange={set('city')} /></label>
          <label className="field">State<input id="state" value={f.state} onChange={set('state')} /></label>
          <label className="field">PIN code<input id="pincode" inputMode="numeric" value={f.pincode} onChange={set('pincode')} /></label>
          <label className="field">Clinic phone<input id="clinicPhone" type="tel" value={f.clinicPhone} onChange={set('clinicPhone')} /></label>
        </div>
        <h3>You</h3>
        <div className="fields">
          <label className="field">Full name<input id="name" required value={f.name} onChange={set('name')} /></label>
          <label className="field">Email<input id="signupEmail" type="email" required value={f.email} onChange={set('email')} /></label>
          <label className="field">Password (10+ characters)
            <input id="signupPassword" type="password" minLength={10} required value={f.password} onChange={set('password')} />
          </label>
        </div>
        <label className="check"><input id="isDoctor" type="checkbox" checked={f.isDoctor} onChange={set('isDoctor')} /> I'm also a doctor at this clinic</label>
        {f.isDoctor && (
          <div className="fields">
            <label className="field">Registration no.<input id="regNo" required value={f.registrationNo} onChange={set('registrationNo')} /></label>
            <label className="field">Medical council<input id="council" required value={f.registrationCouncil} onChange={set('registrationCouncil')} /></label>
            <label className="field">Qualifications<input id="quals" required placeholder="MBBS, MD" value={f.qualifications} onChange={set('qualifications')} /></label>
          </div>
        )}
        <div className="row end">
          <Link to="/">Back to sign in</Link>
          <button className="primary" disabled={busy}>{busy ? 'Setting up…' : 'Create clinic'}</button>
        </div>
      </form>
    </div>
  );
}
