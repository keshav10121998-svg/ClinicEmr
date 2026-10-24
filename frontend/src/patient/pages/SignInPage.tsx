import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useSession, type SignedIn } from '../session';
import { SignInSwitch } from '../../shared/SignInSwitch';

type Mode = 'pin' | 'code';

/**
 * Two free ways in: mobile number and PIN (at home), or a one-time code from the clinic counter (first time, or a
 * forgotten PIN). Scanning the counter QR opens this page with the code in the link and signs in straight away.
 */
export function SignInPage() {
  const { signIn } = useSession();
  const [params, setParams] = useSearchParams();
  const [mode, setMode] = useState<Mode>('pin');
  const [phone, setPhone] = useState('');
  const [pin, setPin] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const scanned = useRef(false);

  // Opened from the QR at the clinic: /my?c=<code id>&k=<code>
  useEffect(() => {
    const c = params.get('c');
    const k = params.get('k');
    if (!c || !k || scanned.current) return;
    scanned.current = true;
    setParams({}, { replace: true });
    run(() => api.post<SignedIn>('/auth/patient/otp/verify', { challengeId: c, otp: k }),
      'This code has already been used or has expired. Ask the clinic for a new one.');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function run(call: () => Promise<SignedIn>, fallback?: string) {
    setBusy(true);
    setError(undefined);
    try {
      signIn(await call());
    } catch (err) {
      if (err instanceof ApiError && err.code === 'PIN_INVALID' && err.problem.attemptsLeft != null) {
        setError(`${err.message} ${err.problem.attemptsLeft} ${err.problem.attemptsLeft === 1 ? 'try' : 'tries'} left.`);
      } else if (err instanceof ApiError && err.code === 'OTP_INVALID') {
        setError(`That code is not right. ${err.problem.attemptsLeft} tries left.`);
      } else if (err instanceof ApiError && err.code === 'OTP_EXPIRED' && fallback) {
        setError(fallback);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not sign in.');
      }
      setPin('');
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  function submitPin(e: FormEvent) {
    e.preventDefault();
    run(() => api.post<SignedIn>('/auth/patient/pin', { phone, pin }));
  }

  function submitCode(e: FormEvent) {
    e.preventDefault();
    run(() => api.post<SignedIn>('/auth/patient/code/verify', { phone, otp: code }));
  }

  const phoneOk = phone.replace(/\D/g, '').length >= 10;

  return (
    <div className="auth">
      <div className="card">
        <SignInSwitch current="patient" />
        <div>
          <h1>Your family's health records</h1>
          <p className="muted small">See visits and prescriptions from every clinic on ClinicEMR, keep old reports, and decide who can see them.</p>
        </div>
        {error && <div className="alert error" role="alert">{error}</div>}
        {busy && !phone && <p className="muted">Signing you in…</p>}

        <label className="field">Mobile number
          <input id="phone" type="tel" inputMode="tel" autoComplete="tel" required placeholder="98765 43210"
                 value={phone} onChange={(e) => setPhone(e.target.value)} />
        </label>

        {mode === 'pin' ? (
          <form onSubmit={submitPin} style={{ display: 'grid', gap: 12 }}>
            <label className="field">PIN
              <input id="pin" className="otp" type="password" inputMode="numeric" autoComplete="current-password"
                     maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
            </label>
            <button className="primary block" disabled={busy || !phoneOk || pin.length < 4}>{busy ? 'Checking…' : 'Sign in'}</button>
            <div className="hint-box">
              <b>First time, or forgot your PIN?</b>
              <span className="small">Ask the clinic for a sign-in code. It's free and takes a few seconds.</span>
              <button type="button" className="link" onClick={() => { setMode('code'); setError(undefined); }}>I have a code from the clinic</button>
            </div>
          </form>
        ) : (
          <form onSubmit={submitCode} style={{ display: 'grid', gap: 12 }}>
            <label className="field">6-digit code from the clinic
              <input id="code" className="otp" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
                     value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
            </label>
            <button className="primary block" disabled={busy || !phoneOk || code.length !== 6}>{busy ? 'Checking…' : 'Sign in'}</button>
            <button type="button" className="link" onClick={() => { setMode('pin'); setError(undefined); }}>Sign in with my PIN instead</button>
          </form>
        )}
      </div>
    </div>
  );
}
