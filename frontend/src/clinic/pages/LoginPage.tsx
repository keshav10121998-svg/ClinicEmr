import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api';
import { useSession } from '../session';
import { SignInSwitch } from '../../shared/SignInSwitch';

export function LoginPage() {
  const { login } = useSession();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await login(email, password);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Sign-in failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <form className="card" onSubmit={submit}>
        <SignInSwitch current="clinic" />
        <div>
          <h1>Sign in to ClinicEMR</h1>
          <p className="muted small">For doctors, nurses and front desk staff.</p>
        </div>
        {error && <div className="alert error">{error}</div>}
        <label className="field">Email
          <input id="email" type="email" autoComplete="username" required value={email}
                 onChange={(e) => setEmail(e.target.value)} autoFocus />
        </label>
        <label className="field">Password
          <input id="password" type="password" autoComplete="current-password" required value={password}
                 onChange={(e) => setPassword(e.target.value)} />
        </label>
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <p className="small muted">New clinic? <Link to="/signup">Set up your clinic</Link></p>
      </form>
    </div>
  );
}
