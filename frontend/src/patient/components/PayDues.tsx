import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, postForm } from '../api';
import type { ClinicBalance, PaymentRequest } from '../types';
import { date, dateTime } from '../format';
import { shrinkFile } from '../../shared/shrinkFile';

const rupees = (n: number) => '₹' + n.toLocaleString('en-IN');
const STATUS: Record<PaymentRequest['status'], string> = {
  PENDING: 'Waiting for the clinic',
  APPROVED: 'Approved',
  REJECTED: 'Not approved',
};

/**
 * What this person owes each clinic. If they've paid (UPI, bank transfer), they send the amount and a screenshot;
 * the clinic's doctor checks it and the balance goes down. Hidden when nothing is owed and nothing was sent.
 */
export function PayDues({ profileId }: { profileId: string }) {
  const [balances, setBalances] = useState<ClinicBalance[]>([]);
  const [requests, setRequests] = useState<PaymentRequest[]>([]);
  const [open, setOpen] = useState<number>();

  const load = useCallback(() => {
    api.get<ClinicBalance[]>(`/patient/profiles/${profileId}/balances`).then(setBalances).catch(() => {});
    api.get<PaymentRequest[]>(`/patient/profiles/${profileId}/payment-requests`).then(setRequests).catch(() => {});
  }, [profileId]);

  useEffect(() => {
    setOpen(undefined);
    load();
  }, [load]);

  if (balances.length === 0 && requests.length === 0) return null;

  return (
    <div className="card">
      <h3>Payments</h3>
      {balances.map((b) => {
        const canSend = b.balance - b.pending;
        return (
          <div key={b.clinicId} className="owed">
            <div className="between">
              <span><b>{rupees(b.balance)}</b> due to {b.clinicName}</span>
              {b.nextDue && <span className="small muted">by {date(b.nextDue)}</span>}
            </div>
            {b.pending > 0 && <span className="small muted">{rupees(b.pending)} sent, waiting for the clinic to check.</span>}
            {open === b.clinicId ? (
              <SendProof profileId={profileId} clinic={b} max={canSend}
                         onDone={() => { setOpen(undefined); load(); }} onCancel={() => setOpen(undefined)} />
            ) : canSend > 0 && (
              <button type="button" className="primary" onClick={() => setOpen(b.clinicId)}>I've paid, send proof</button>
            )}
          </div>
        );
      })}
      {requests.length > 0 && (
        <div className="list">
          <span className="small muted">Proofs you sent</span>
          {requests.map((r) => (
            <div key={r.id} className="proof-row">
              <div className="between">
                <span><b>{rupees(r.amount)}</b> to {r.clinicName}</span>
                <span className={`chip ${r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'bad' : ''}`}>{STATUS[r.status]}</span>
              </div>
              <span className="small muted">Sent {dateTime(r.createdAt)}{r.note ? ` · ${r.note}` : ''}</span>
              {r.status === 'APPROVED' && r.approvedAmount != null && r.approvedAmount < r.amount && (
                <span className="small">Approved {rupees(r.approvedAmount)}, all that was still due.</span>
              )}
              {r.status === 'APPROVED' && r.decisionComment && <span className="small">Clinic: {r.decisionComment}</span>}
              {r.status === 'REJECTED' && r.rejectReason && <span className="small" style={{ color: 'var(--danger)' }}>{r.rejectReason}</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function SendProof({ profileId, clinic, max, onDone, onCancel }:
  { profileId: string; clinic: ClinicBalance; max: number; onDone: () => void; onCancel: () => void }) {
  const [amount, setAmount] = useState(String(max));
  const [capped, setCapped] = useState(false);
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<string>();
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [error, setError] = useState<string>();
  const n = Number(amount || 0);

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);

  // Digits only, never more than what can still be claimed
  function typeAmount(raw: string) {
    const digits = raw.replace(/\D/g, '');
    const v = Number(digits || 0);
    setCapped(v > max);
    setAmount(v > max ? String(max) : digits.replace(/^0+(?=\d)/, ''));
  }

  async function pick(picked?: File) {
    setError(undefined);
    if (!picked) return;
    if (picked.size > 30 * 1024 * 1024) {
      setError('That file is too big. Send a smaller screenshot.');
      return;
    }
    // A photo or screenshot is made much smaller first, so it also sends quickly on a slow connection
    setPreparing(true);
    const f = await shrinkFile(picked);
    setPreparing(false);
    if (f.size > 8 * 1024 * 1024) {
      setError('That file is bigger than 8 MB. Send a smaller screenshot.');
      return;
    }
    setFile(f);
    setPreview(f.type.startsWith('image/') ? URL.createObjectURL(f) : undefined);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) {
      setError('Add a screenshot of the payment.');
      return;
    }
    setBusy(true);
    setError(undefined);
    const form = new FormData();
    form.append('clinicId', String(clinic.clinicId));
    form.append('amount', String(n));
    if (note.trim()) form.append('note', note.trim());
    form.append('proof', file);
    try {
      await postForm(`/patient/profiles/${profileId}/payment-requests`, form);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not send. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="send-proof">
      {error && <div className="alert error" role="alert">{error}</div>}
      <label className="field">Amount you paid (up to {rupees(max)})
        <input inputMode="numeric" value={amount} onChange={(e) => typeAmount(e.target.value)} />
      </label>
      {capped && <span className="small field-hint">Can't be more than {rupees(max)}.</span>}
      <label className="attach-proof">
        {preparing ? 'Making the file smaller…' : file ? `Change screenshot (${file.name})` : 'Add payment screenshot'}
        <input type="file" accept="image/*,application/pdf" onChange={(e) => pick(e.target.files?.[0])} />
      </label>
      {preview && <img src={preview} alt="Payment screenshot preview" className="proof-preview" />}
      <span className="small muted">The screenshot is deleted from ClinicEMR as soon as the clinic approves or rejects it.</span>
      <label className="field">UPI reference or note (optional)
        <input value={note} maxLength={300} placeholder="e.g. UPI ref 4123 5567 8901" onChange={(e) => setNote(e.target.value)} />
      </label>
      <button className="primary block" disabled={busy || preparing || n < 1 || !file}>{busy ? 'Sending…' : `Send ${rupees(n || 0)} for the clinic to check`}</button>
      <button type="button" className="link" onClick={onCancel}>Cancel</button>
    </form>
  );
}
