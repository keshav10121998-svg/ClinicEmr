import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useSession, type SignedIn } from '../session';

/**
 * Choose a PIN (after the first code sign-in, or after a forgotten PIN), or change it. With a PIN the patient signs
 * in at home with their mobile number. Setting it signs out the account on every other phone.
 */
export function PinForm({ changing, onDone, onSkip }: { changing: boolean; onDone: () => void; onSkip?: () => void }) {
  const { signIn } = useSession();
  const [current, setCurrent] = useState('');
  const [pin, setPin] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const digits = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => set(e.target.value.replace(/\D/g, ''));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (pin !== again) {
      setError("The two PINs don't match.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      signIn(await api.post<SignedIn>('/patient/pin', { pin, currentPin: current || undefined }));
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the PIN.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={submit} style={{ gap: 12 }}>
      <div>
        <h2>{changing ? 'Change your PIN' : 'Choose a PIN'}</h2>
        <p className="muted small">
          {changing
            ? 'Other phones signed in to your records will be signed out.'
            : 'Next time, sign in from home with your mobile number and this PIN. No code needed.'}
        </p>
      </div>
      {error && <div className="alert error" role="alert">{error}</div>}
      {changing && (
        <label className="field">Current PIN
          <input className="otp" type="password" inputMode="numeric" autoComplete="current-password" maxLength={6}
                 value={current} onChange={digits(setCurrent)} />
        </label>
      )}
      <label className="field">New PIN (4 to 6 digits)
        <input id="newPin" className="otp" type="password" inputMode="numeric" autoComplete="new-password" maxLength={6}
               value={pin} onChange={digits(setPin)} autoFocus={!changing} />
      </label>
      <label className="field">Type it again
        <input id="newPinAgain" className="otp" type="password" inputMode="numeric" autoComplete="new-password" maxLength={6}
               value={again} onChange={digits(setAgain)} />
      </label>
      <p className="muted small">Avoid easy PINs like 1111 or 1234.</p>
      <button className="primary block" disabled={busy || pin.length < 4 || again.length < 4 || (changing && current.length < 4)}>
        {busy ? 'Saving…' : 'Save PIN'}
      </button>
      {onSkip && <button type="button" className="link" onClick={onSkip}>{changing ? 'Cancel' : 'Not now'}</button>}
    </form>
  );
}
