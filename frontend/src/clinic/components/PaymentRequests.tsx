import { useState } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';
import { dateTime } from '../format';
import { paymentsChanged, rupees, usePaymentRequests } from '../payments';
import type { PatientPaymentReceived, PaymentRequest } from '../types';
import { FilePreview } from './FilePreview';

/**
 * Patients' "I've paid" with a screenshot. Approve records the payment like Received (latest visit first), with an
 * optional comment the patient sees; Reject needs a reason the patient sees. Doctors and admins decide; others see
 * who is waiting. Everything is typed on the page (no pop-up boxes, which some phone and in-app browsers block).
 */
export function PaymentRequests({ patientId, onOpenPatient }: { patientId?: string; onOpenPatient?: (id: string) => void }) {
  const { has } = useSession();
  const canDecide = has('DOCTOR', 'ADMIN');
  const { requests, reload } = usePaymentRequests(patientId);
  const [done, setDone] = useState<string>();
  const [preview, setPreview] = useState<{ title: string; url: string; type?: string }>();

  // Keep the last result visible after the final request is handled
  if ((!requests || requests.length === 0) && !done) return null;

  return (
    <div className="card payment-requests">
      <div className="between">
        <h2>{patientId ? 'Payment proofs from the patient' : 'Payment requests'}</h2>
        {requests && requests.length > 0 && <span className="due-count" style={{ position: 'static' }}>{requests.length}</span>}
      </div>
      {done && <div className="alert info">{done}</div>}
      {requests?.map((r) => (
        <RequestRow key={r.id} request={r} showName={!patientId} canDecide={canDecide} onOpenPatient={onOpenPatient}
                    onPreview={() => setPreview({ title: `Payment proof: ${r.patientName}, ${rupees(r.amount)}`, url: r.proofUrl!, type: r.proofType })}
                    onDecided={(message) => { setDone(message); paymentsChanged(); reload(); }} />
      ))}
      {preview && <FilePreview title={preview.title} pages={[{ url: preview.url, mimeType: preview.type }]} onClose={() => setPreview(undefined)} />}
    </div>
  );
}

function RequestRow({ request: r, showName, canDecide, onOpenPatient, onPreview, onDecided }: {
  request: PaymentRequest; showName: boolean; canDecide: boolean; onOpenPatient?: (id: string) => void;
  onPreview: () => void; onDecided: (message: string) => void;
}) {
  const [comment, setComment] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('Payment not received');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function approve() {
    setBusy(true);
    setError(undefined);
    try {
      const res = await api.post<PatientPaymentReceived>(`/clinic/payment-requests/${r.id}/approve`, { comment: comment.trim() || undefined });
      onDecided(`Approved ${r.patientName}'s ${rupees(res.amount)}`
        + (res.remainingBalance > 0 ? `, ${rupees(res.remainingBalance)} still due.` : '. Nothing left to collect.'));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not approve. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  }

  async function reject() {
    if (reason.trim().length < 3) {
      setError('Write a short reason the patient will see.');
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await api.post(`/clinic/payment-requests/${r.id}/reject`, { reason: reason.trim() });
      onDecided(`Not approved: ${r.patientName}'s ${rupees(r.amount)}. The patient can see the reason.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not reject. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="request-row">
      <div className="between">
        {showName && onOpenPatient
          ? <button type="button" className="link due-name" onClick={() => onOpenPatient(r.patientId)}>{r.patientName}</button>
          : showName ? <b>{r.patientName}</b> : <span />}
        <b>{rupees(r.amount)}</b>
      </div>
      <span className="small muted">Sent {dateTime(r.createdAt)}{r.note ? ` · ${r.note}` : ''}</span>
      {error && <div className="alert error">{error}</div>}
      <div className="row">
        {r.proofUrl && <button type="button" className="small" onClick={onPreview}>View proof</button>}
        {!canDecide && <span className="small muted">Waiting for the doctor to check</span>}
      </div>
      {canDecide && !rejecting && (
        <div className="decide">
          <input aria-label={`Comment for ${r.patientName} (optional)`} placeholder='Comment for the patient (optional), e.g. "Received, thank you"'
                 maxLength={300} value={comment} onChange={(e) => setComment(e.target.value)} disabled={busy} />
          <div className="row">
            <button type="button" className="small primary" disabled={busy} onClick={approve}>{busy ? 'Saving…' : `Approve ${rupees(r.amount)}`}</button>
            <button type="button" className="small" disabled={busy} onClick={() => { setRejecting(true); setError(undefined); }}>Reject</button>
          </div>
        </div>
      )}
      {canDecide && rejecting && (
        <div className="decide">
          <label className="field">Why it's not approved (the patient will see this)
            <input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} disabled={busy} autoFocus />
          </label>
          <div className="row">
            <button type="button" className="small danger" disabled={busy} onClick={reject}>{busy ? 'Saving…' : 'Reject'}</button>
            <button type="button" className="small" disabled={busy} onClick={() => { setRejecting(false); setError(undefined); }}>Cancel</button>
          </div>
        </div>
      )}
    </div>
  );
}
