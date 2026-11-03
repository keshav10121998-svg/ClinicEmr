import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import type { PatientPaymentReceived, PaymentDue, PaymentHistory, PaymentHistoryEntry } from '../types';
import { date, dateTime } from '../format';
import { dueLabel, PAYMENTS_CHANGED, paymentsChanged, rupees } from '../payments';

const KIND: Record<PaymentHistoryEntry['kind'], string> = {
  AT_VISIT: 'Paid at the visit',
  BALANCE: 'Balance received',
  CORRECTION: 'Correction',
};

/**
 * Everything the patient owes this clinic, in one place: the total balance across visits, an amount box that can't
 * go above it (taken off the latest visit first), Fully paid, and the payment history. Hidden until a payment has
 * been recorded for the patient.
 */
export function PatientPayments({ patientId }: { patientId: string }) {
  const [history, setHistory] = useState<PaymentHistory>();
  const [dues, setDues] = useState<PaymentDue[]>([]);
  const [amount, setAmount] = useState('');
  const [capped, setCapped] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [done, setDone] = useState<string>();
  const [showHistory, setShowHistory] = useState(false);

  const load = useCallback(() => {
    api.get<PaymentHistory>(`/clinic/patients/${patientId}/payments/history`).then(setHistory).catch(() => {});
    api.get<PaymentDue[]>(`/clinic/payments/due?when=all&patientId=${patientId}`).then(setDues).catch(() => {});
  }, [patientId]);

  useEffect(() => {
    load();
    window.addEventListener(PAYMENTS_CHANGED, load);
    return () => window.removeEventListener(PAYMENTS_CHANGED, load);
  }, [load]);

  if (!history || (history.total === 0 && history.entries.length === 0)) return null;
  const balance = history.balance;
  const n = Number(amount || 0);

  // Digits only, never more than the total balance: a bigger number is cut down to it
  function typeAmount(raw: string) {
    const digits = raw.replace(/\D/g, '');
    const v = Number(digits || 0);
    setCapped(v > balance);
    setAmount(v > balance ? String(balance) : digits.replace(/^0+(?=\d)/, ''));
    setDone(undefined);
  }

  async function receive(body: { amount?: number; full?: boolean }) {
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.post<PatientPaymentReceived>(`/clinic/patients/${patientId}/payments/receive`, body);
      setDone(`${rupees(r.amount)} received: ` + r.appliedTo.map((a) => `${rupees(a.amount)} for the ${dateTime(a.visitAt)} visit`).join(', ')
        + (r.remainingBalance > 0 ? `. ${rupees(r.remainingBalance)} still due.` : '. Nothing left to collect.'));
      setAmount('');
      setCapped(false);
      paymentsChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not record the payment.');
    } finally {
      setBusy(false);
    }
  }

  const next = dues[0];
  return (
    <div className="card patient-payments">
      <div className="between">
        <h2>Payments</h2>
        <button type="button" className="link small" onClick={() => setShowHistory((s) => !s)}>
          {showHistory ? 'Hide payment history' : 'Payment history'}
        </button>
      </div>

      <div className="pay-totals">
        <div><span className="stat-label">Total</span><b>{rupees(history.total)}</b></div>
        <div><span className="stat-label">Paid</span><b>{rupees(history.paid)}</b></div>
        <div className={balance > 0 ? 'owed' : undefined}><span className="stat-label">Balance</span><b>{rupees(balance)}</b></div>
      </div>

      {error && <div className="alert error">{error}</div>}
      {done && <div className="alert info">{done}</div>}

      {balance > 0 ? (
        <>
          <p className="small muted" style={{ margin: 0 }}>
            Due on {dues.length} {dues.length === 1 ? 'visit' : 'visits'}
            {next && <> · {next.overdueDays >= 0 ? dueLabel(next.overdueDays) : `next due ${date(next.dueDate)}`}</>}.
            A payment here is taken off the latest visit first.
          </p>
          <form className="pay-actions" onSubmit={(e) => { e.preventDefault(); if (n >= 1 && n <= balance) receive({ amount: n }); }}>
            <span className="amount-box">
              <span aria-hidden="true">₹</span>
              <input aria-label={`Amount received now, up to ₹${balance}`} inputMode="numeric" placeholder={`up to ${balance}`}
                     value={amount} onChange={(e) => typeAmount(e.target.value)} disabled={busy} />
            </span>
            <button type="submit" disabled={busy || n < 1}>{n >= 1 && n < balance ? `Received ${rupees(n)}` : 'Received'}</button>
            <button type="button" className="primary" disabled={busy} onClick={() => receive({ full: true })}>
              Fully paid {rupees(balance)}
            </button>
          </form>
          {capped && <span className="small field-hint">Can't be more than the total balance of {rupees(balance)}.</span>}
        </>
      ) : (
        <span className="chip ok" style={{ justifySelf: 'start' }}>Fully paid</span>
      )}

      {showHistory && <PaymentHistoryList entries={history.entries} />}
    </div>
  );
}

/** Newest first. The parts of one payment split across visits are shown together under one amount. */
function PaymentHistoryList({ entries }: { entries: PaymentHistoryEntry[] }) {
  if (entries.length === 0) return <p className="muted small">No payments recorded yet.</p>;
  const groups: PaymentHistoryEntry[][] = [];
  for (const e of entries) {
    const last = groups[groups.length - 1];
    if (e.batchId && last && last[0].batchId === e.batchId) last.push(e);
    else groups.push([e]);
  }
  return (
    <div className="pay-history" aria-label="Payment history">
      {groups.map((g) => {
        const total = g.reduce((s, e) => s + e.amount, 0);
        return (
          <div key={g[0].id} className="pay-history-item">
            <div className="between">
              <b className={total < 0 ? 'negative' : undefined}>{total < 0 ? '−' : ''}{rupees(Math.abs(total))}</b>
              <span className="small muted">{dateTime(g[0].receivedAt)}</span>
            </div>
            <span className="small">{g.length > 1 ? 'Balance received (split across visits)' : KIND[g[0].kind]} · by {g[0].recordedBy}</span>
            {g.map((e) => (
              <span key={e.id} className="small muted">
                {g.length > 1 ? `${rupees(e.amount)} for` : 'For'} the visit on {dateTime(e.visitAt)}
              </span>
            ))}
          </div>
        );
      })}
    </div>
  );
}
