import { useEffect, useMemo, useState } from 'react';
import qrcode from 'qrcode-generator';
import { api, ApiError } from '../api';

interface CounterCode { challengeId: string; code: string; phone: string; expiresAt: string }

/**
 * Free sign-in to the patient app: a one-time code and QR shown on this screen. The patient scans it with their
 * phone camera (or types the code) and then sets a PIN for signing in at home.
 */
export function AppSignInCode({ patientId }: { patientId: string }) {
  const [code, setCode] = useState<CounterCode>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    if (!code) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [code]);

  const link = code && `${window.location.origin}/my?c=${code.challengeId}&k=${code.code}`;
  const svg = useMemo(() => {
    if (!link) return '';
    const qr = qrcode(0, 'M');
    qr.addData(link);
    qr.make();
    return qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
  }, [link]);

  async function create() {
    setBusy(true);
    setError(undefined);
    try {
      setCode(await api.post<CounterCode>(`/clinic/patients/${patientId}/app-sign-in-code`));
      setNow(Date.now());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not create a code.');
    } finally {
      setBusy(false);
    }
  }

  const secondsLeft = code ? Math.max(0, Math.round((new Date(code.expiresAt).getTime() - now) / 1000)) : 0;

  if (!code) {
    return (
      <div className="app-code-start">
        <button type="button" onClick={create} disabled={busy}>{busy ? 'Creating…' : 'Patient app sign-in code'}</button>
        <span className="muted small">For the patient to see their prescriptions on their own phone. Free, no SMS.</span>
        {error && <div className="alert error">{error}</div>}
      </div>
    );
  }

  return (
    <div className="card app-code">
      <div className="between">
        <h2>Patient app sign-in</h2>
        <button type="button" className="link" onClick={() => setCode(undefined)}>Close</button>
      </div>
      {secondsLeft > 0 ? (
        <>
          <p className="small">Ask the patient to <b>scan this with their phone camera</b>. They choose a PIN on their phone and their records open. Next time they sign in with mobile number + PIN.</p>
          <div className="qr" role="img" aria-label="QR code for patient app sign-in" dangerouslySetInnerHTML={{ __html: svg }} />
          <p className="small muted">Or on their phone: open <b>{window.location.host}/my</b>, tap <b>I have a code from the clinic</b>, enter their mobile number {code.phone}, this code, and a PIN:</p>
          <div className="big-code" aria-label={`Code ${code.code.split('').join(' ')}`}>{code.code.slice(0, 3)} {code.code.slice(3)}</div>
          <p className="small muted">Works once, for {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')} more minutes.</p>
        </>
      ) : (
        <div className="alert warn">This code has expired. <button type="button" className="link" onClick={create}>Make a new one</button></div>
      )}
    </div>
  );
}
