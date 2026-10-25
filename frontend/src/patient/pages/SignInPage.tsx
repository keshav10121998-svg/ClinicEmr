import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useSession, type SignedIn } from '../session';
import { SignInSwitch } from '../../shared/SignInSwitch';
import { PhoneInput, isMobile } from '../../shared/PhoneInput';

type Mode = 'pin' | 'code';

/**
 * Two free ways in: mobile number and PIN (at home), or a one-time code from the clinic counter (first time, or a
 * forgotten PIN). With a code, the patient chooses their PIN in the same step, so they are never signed in without
 * one. Scanning the counter QR opens this page with the code in the link, ready for them to choose the PIN.
 */
export function SignInPage() {
  const { signIn } = useSession();
  const [params, setParams] = useSearchParams();
  const [mode, setMode] = useState<Mode>('pin');
  const [scanned, setScanned] = useState<{ challengeId: string; code: string }>();
  const [phone, setPhone] = useState('');
  const [pin, setPin] = useState('');
  const [code, setCode] = useState('');
  const [newPin, setNewPin] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const read = useRef(false);

  // Opened from the QR at the clinic: /my?c=<code id>&k=<code>. Keep the code, take it out of the address bar.
  useEffect(() => {
    const c = params.get('c');
    const k = params.get('k');
    if (!c || !k || read.current) return;
    read.current = true;
    setParams({}, { replace: true });
    setScanned({ challengeId: c, code: k });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function run(call: () => Promise<SignedIn>) {
    setBusy(true);
    setError(undefined);
    try {
      signIn(await call());
    } catch (err) {
      if (err instanceof ApiError && err.code === 'PIN_INVALID' && err.problem.attemptsLeft != null) {
        setError(`${err.message} ${err.problem.attemptsLeft} ${err.problem.attemptsLeft === 1 ? 'try' : 'tries'} left.`);
      } else if (err instanceof ApiError && err.code === 'OTP_INVALID') {
        setError(`That code is not right. ${err.problem.attemptsLeft} tries left.`);
      } else if (err instanceof ApiError && err.code === 'OTP_EXPIRED' && scanned) {
        setError('This QR has already been used or has expired. Ask the clinic for a new one.');
        setScanned(undefined);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not sign in.');
      }
      setPin('');
    } finally {
      setBusy(false);
    }
  }

  function pinsOk() {
    if (newPin !== again) {
      setError("The two PINs don't match.");
      return false;
    }
    return true;
  }

  function submitPin(e: FormEvent) {
    e.preventDefault();
    run(() => api.post<SignedIn>('/auth/patient/pin', { phone, pin }));
  }

  function submitCode(e: FormEvent) {
    e.preventDefault();
    if (pinsOk()) run(() => api.post<SignedIn>('/auth/patient/code/verify', { phone, otp: code, newPin }));
  }

  function submitScanned(e: FormEvent) {
    e.preventDefault();
    if (pinsOk()) run(() => api.post<SignedIn>('/auth/patient/otp/verify', { ...scanned!, otp: scanned!.code, newPin }));
  }

  const phoneOk = isMobile(phone);
  const newPinOk = newPin.length >= 4 && again.length >= 4;
  const choosePin = (
    <>
      <label className="field">Choose a PIN (4 to 6 digits)
        <input id="newPin" className="otp" type="password" inputMode="numeric" autoComplete="new-password" maxLength={6}
               value={newPin} onChange={(e) => setNewPin(e.target.value.replace(/\D/g, ''))} autoFocus={!!scanned} />
      </label>
      <label className="field">Type the PIN again
        <input id="newPinAgain" className="otp" type="password" inputMode="numeric" autoComplete="new-password" maxLength={6}
               value={again} onChange={(e) => setAgain(e.target.value.replace(/\D/g, ''))} />
      </label>
      <p className="muted small">You'll use this PIN with your mobile number to sign in from home. Avoid easy PINs like 1111 or 1234.</p>
    </>
  );

  if (scanned) {
    return (
      <div className="auth">
        <form className="card" onSubmit={submitScanned}>
          <div>
            <h1>Welcome to ClinicEMR</h1>
            <p className="muted small">One last step: choose a PIN. Then your records open.</p>
          </div>
          {error && <div className="alert error" role="alert">{error}</div>}
          {choosePin}
          <button className="primary block" disabled={busy || !newPinOk}>{busy ? 'Saving…' : 'Save PIN and open my records'}</button>
        </form>
      </div>
    );
  }

  return (
    <div className="auth">
      <div className="card">
        <SignInSwitch current="patient" />
        <div>
          <h1>Your family's health records</h1>
          <p className="muted small">See visits and prescriptions from every clinic on ClinicEMR, keep old reports, and decide who can see them.</p>
        </div>
        {error && <div className="alert error" role="alert">{error}</div>}

        <label className="field">Mobile number
          <PhoneInput id="phone" required autoComplete="tel-national" value={phone} onChange={setPhone} />
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
            {choosePin}
            <button className="primary block" disabled={busy || !phoneOk || code.length !== 6 || !newPinOk}>
              {busy ? 'Checking…' : 'Save PIN and sign in'}
            </button>
            <button type="button" className="link" onClick={() => { setMode('pin'); setError(undefined); }}>Sign in with my PIN instead</button>
          </form>
        )}
      </div>
    </div>
  );
}
