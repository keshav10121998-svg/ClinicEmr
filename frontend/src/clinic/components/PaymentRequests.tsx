import { useState } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';
import { dateTime } from '../format';
import { paymentsChanged, rupees, usePaymentRequests } from '../payments';
import type { PatientPaymentReceived } from '../types';
import { FilePreview } from './FilePreview';

/**
 * Patients' "I've paid" with a screenshot. Approve records the payment like Received (latest visit first); Reject
 * asks for a reason the patient will see. Doctors and admins decide; others see who is waiting.
 */
export function PaymentRequests({ patientId, onOpenPatient }: { patientId?: string; onOpenPatient?: (id: string) => void }) {
  const { has } = useSession();
  const canDecide = has('DOCTOR', 'ADMIN');
  const { requests, reload } = usePaymentRequests(patientId);
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState<string>();
  const [done, setDone] = useState<string>();
  const [preview, setPreview] = useState<{ title: string; url: string; type?: string }>();

  // Keep the last result visible after the final request is handled
  if ((!requests || requests.length === 0) && !done) return null;

  async function decide(id: string, approve: boolean) {
    let reason: string | null = null;
    if (!approve) {
      reason = window.prompt('Why is this not approved? The patient will see this.', 'Payment not received');
      if (reason === null) return;
    }
    setBusy(id);
    setError(undefined);
    setDone(undefined);
    try {
      if (approve) {
        const comment = window.prompt('Comment for the patient (optional), e.g. "Received, thank you":', '');
        if (comment === null) return;
        const r = await api.post<PatientPaymentReceived>(`/clinic/payment-requests/${id}/approve`, { comment: comment.trim() || undefined });
        setDone(`Approved: ${rupees(r.amount)} recorded${r.remainingBalance > 0 ? `, ${rupees(r.remainingBalance)} still due` : ', nothing left to collect'}.`);
      } else {
        await api.post(`/clinic/payment-requests/${id}/reject`, { reason });
        setDone('Not approved. The patient can see the reason.');
      }
      paymentsChanged();
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not update the request.');
    } finally {
      setBusy(undefined);
    }
  }

  return (
    <div className="card payment-requests">
      <div className="between">
        <h2>{patientId ? 'Payment proofs from the patient' : 'Payment requests'}</h2>
        {requests && requests.length > 0 && <span className="due-count" style={{ position: 'static' }}>{requests.length}</span>}
      </div>
      {error && <div className="alert error">{error}</div>}
      {done && <div className="alert info">{done}</div>}
      {requests?.map((r) => (
        <div key={r.id} className="request-row">
          <div className="between">
            {patientId || !onOpenPatient
              ? <b>{rupees(r.amount)}</b>
              : <button type="button" className="link due-name" onClick={() => onOpenPatient(r.patientId)}>{r.patientName}</button>}
            {!patientId && <b>{rupees(r.amount)}</b>}
          </div>
          <span className="small muted">Sent {dateTime(r.createdAt)}{r.note ? ` · ${r.note}` : ''}</span>
          <div className="row">
            {r.proofUrl && (
              <button type="button" className="small" onClick={() => setPreview({ title: `Payment proof: ${r.patientName}, ${rupees(r.amount)}`, url: r.proofUrl!, type: r.proofType })}>
                View proof
              </button>
            )}
            {canDecide ? (
              <>
                <button type="button" className="small primary" disabled={busy === r.id} onClick={() => decide(r.id, true)}>Approve</button>
                <button type="button" className="small" disabled={busy === r.id} onClick={() => decide(r.id, false)}>Reject</button>
              </>
            ) : <span className="small muted">Waiting for the doctor to check</span>}
          </div>
        </div>
      ))}
      {preview && <FilePreview title={preview.title} pages={[{ url: preview.url, mimeType: preview.type }]} onClose={() => setPreview(undefined)} />}
    </div>
  );
}
