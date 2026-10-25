import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api';
import { useSession } from '../session';
import { SignInSwitch } from '../../shared/SignInSwitch';
import { isMobile, toMobileDigits } from '../../shared/PhoneInput';

/** Staff sign in with their email address or their 10-digit mobile number, and their password. */
export function LoginPage() {
  const { login } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  // Only digits (and +, spaces, dashes) typed: treat it as a mobile number, and stop at 10 digits
  const looksLikePhone = username !== '' && /^[\d\s+-]+$/.test(username);
  const phoneShort = looksLikePhone && username.length < 10;

  function change(value: string) {
    setUsername(/^[\d\s+-]+$/.test(value) ? toMobileDigits(value) : value);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (looksLikePhone && !isMobile(username)) {
      setError('Enter a 10-digit mobile number starting with 6, 7, 8 or 9, or your email address.');
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await login(username.trim(), password);
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
        <label className="field">Email or mobile number
          <input id="email" type="text" autoComplete="username" autoCapitalize="none" spellCheck={false} required
                 placeholder="you@clinic.com or 98765 43210" value={username}
                 onChange={(e) => change(e.target.value)} autoFocus />
          {phoneShort && <span className="small field-hint">{10 - username.length} more digits needed (10 in total).</span>}
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
