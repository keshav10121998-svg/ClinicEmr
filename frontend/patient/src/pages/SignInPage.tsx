import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';

interface CodeSent { challengeId: string; phone: string; expiresAt: string }
interface SignedIn { accessToken: string; expiresAt: string; accountId: number; profiles: number }

export function SignInPage() {
  const { signIn } = useSession();
  const [phone, setPhone] = useState('');
  const [sent, setSent] = useState<CodeSent>();
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function sendCode(e?: FormEvent) {
    e?.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      setSent(await api.post<CodeSent>('/auth/patient/otp', { phone }));
      setOtp('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not send the code.');
    } finally {
      setBusy(false);
    }
  }

  async function verify(e: FormEvent) {
    e.preventDefault();
    if (!sent) return;
    setBusy(true);
    setError(undefined);
    try {
      const res = await api.post<SignedIn>('/auth/patient/otp/verify', { challengeId: sent.challengeId, otp });
      signIn({ token: res.accessToken, expiresAt: res.expiresAt, accountId: res.accountId, phone: sent.phone });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'OTP_INVALID') {
        setError(`That code is not right. ${err.problem.attemptsLeft} tries left.`);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not sign in.');
      }
      setOtp('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <div className="card">
        <div>
          <h1>Your family's health records</h1>
          <p className="muted small">See visits and prescriptions from every clinic on ClinicEMR, keep old reports, and decide who can see them.</p>
        </div>
        {error && <div className="alert error" role="alert">{error}</div>}
        {!sent ? (
          <form onSubmit={sendCode} style={{ display: 'grid', gap: 12 }}>
            <label className="field">Mobile number
              <input id="phone" type="tel" inputMode="tel" autoComplete="tel" required placeholder="98765 43210"
                     value={phone} onChange={(e) => setPhone(e.target.value)} autoFocus />
            </label>
            <button className="primary block" disabled={busy || phone.replace(/\D/g, '').length < 10}>
              {busy ? 'Sending…' : 'Send code'}
            </button>
          </form>
        ) : (
          <form onSubmit={verify} style={{ display: 'grid', gap: 12 }}>
            <p>Enter the 6-digit code sent to <b className="mono">{sent.phone}</b>.</p>
            <input id="otp" className="otp" inputMode="numeric" autoComplete="one-time-code" maxLength={6} required
                   aria-label="6-digit code" value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))} autoFocus />
            <button className="primary block" disabled={busy || otp.length !== 6}>{busy ? 'Checking…' : 'Sign in'}</button>
            <div className="between small">
              <button type="button" className="link" onClick={() => setSent(undefined)}>Change number</button>
              <button type="button" className="link" onClick={() => sendCode()} disabled={busy}>Send a new code</button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
