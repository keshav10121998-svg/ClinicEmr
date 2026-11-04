import { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { api, ApiError } from '../api';
import type { EncounterListItem, Payment } from '../types';
import { addDays, date, todayIst } from '../format';
import { paymentsChanged, rupees } from '../payments';
import { MicButton, spokenAmount, useDictation } from './Dictation';
import { DateField } from '../../shared/DateField';

const DUE_DAYS = [3, 7, 15, 30];

/** Lets the visit screen save the payment together with Finish visit. */
export interface VisitPaymentHandle {
  /** Saves what was typed, if anything changed. False when it couldn't (the card shows why). */
  saveIfChanged(): Promise<boolean>;
}

/**
 * What the patient paid for this visit: Paid + Balance = Total. A balance gets a due date ("due in N days"); from
 * that day the doctor sees it in Money to collect on the Overview. Any clinic staff member can fill it in.
 * With {@code savedWithFinish} (a visit still open) there is no Save button: Finish visit saves it.
 */
export const VisitPayment = forwardRef<VisitPaymentHandle, { encounterId: string; savedWithFinish?: boolean }>(
function VisitPayment({ encounterId, savedWithFinish = false }, ref) {
  const [saved, setSaved] = useState<Payment | null>();
  const [paid, setPaid] = useState('');
  const [balance, setBalance] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string>();
  const [comment, setComment] = useState('');

  useEffect(() => {
    api.get<Payment | undefined>(`/clinic/encounters/${encounterId}/payment`)
      .then((p) => show(p ?? null))
      .catch(() => setSaved(null));
  }, [encounterId]);

  function show(p: Payment | null) {
    setSaved(p);
    setPaid(p ? String(p.paid) : '');
    setBalance(p && p.balance > 0 ? String(p.balance) : '');
    setDueDate(p?.dueDate ?? '');
  }

  const paidN = Number(paid || 0);
  const balanceN = Number(balance || 0);
  const changed = !saved ? paid !== '' || balance !== ''
    : paidN !== saved.paid || balanceN !== saved.balance || (balanceN > 0 && dueDate !== (saved.dueDate ?? ''));

  useImperativeHandle(ref, () => ({ saveIfChanged: () => (changed ? save() : Promise.resolve(true)) }));

  async function save(): Promise<boolean> {
    if (balanceN > 0 && !dueDate) {
      setError('Choose when the balance is due.');
      return false;
    }
    setBusy(true);
    setError(undefined);
    try {
      show(await api.put<Payment>(`/clinic/encounters/${encounterId}/payment`,
        { paid: paidN, balance: balanceN, dueDate: balanceN > 0 ? dueDate : undefined, comment: comment.trim() || undefined }));
      setComment('');
      setNote('Saved');
      paymentsChanged();
      return true;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save the payment.');
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function received() {
    setBusy(true);
    setError(undefined);
    try {
      show(await api.post<Payment>(`/clinic/encounters/${encounterId}/payment/settle`));
      setNote('Balance received');
      paymentsChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not update the payment.');
    } finally {
      setBusy(false);
    }
  }

  if (saved === undefined) return null;
  const amount = (set: (v: string) => void) => (value: string) => {
    set(value.replace(/\D/g, '').slice(0, 7));
    setNote(undefined);
  };

  return (
    <div className="payment" aria-label="Payment">
      <div className="between">
        <h3>Payment</h3>
        {note && !changed && <span className="small" style={{ color: 'var(--ok)' }}>{note}</span>}
      </div>
      {error && <div className="alert error">{error}</div>}

      {saved && saved.balance > 0 && !changed && (
        <div className="alert warn payment-due">
          <span><b>{rupees(saved.balance)}</b> to collect, due {date(saved.dueDate)}</span>
          <button type="button" className="primary" onClick={received} disabled={busy}>Balance received</button>
        </div>
      )}

      <div className="payment-row">
        <AmountField id="payPaid" label="Paid ₹" value={paid} onChange={amount(setPaid)} />
        <span className="payment-op" aria-hidden="true">+</span>
        <AmountField id="payBalance" label="Balance ₹" value={balance} onChange={amount(setBalance)} />
        <span className="payment-op" aria-hidden="true">=</span>
        <div className="field">Total
          <span className="payment-total">{rupees(paidN + balanceN)}</span>
        </div>
      </div>

      {balanceN > 0 && (
        <div className="field">Balance due in
          <div className="row">
            {DUE_DAYS.map((d) => (
              <button type="button" key={d} aria-pressed={dueDate === addDays(todayIst(), d)}
                      onClick={() => { setDueDate(addDays(todayIst(), d)); setNote(undefined); }}>
                {d} days
              </button>
            ))}
            <DateField label="Balance due date" placeholder="Or pick a date" min={todayIst()} value={dueDate}
                       onChange={(v) => { setDueDate(v); setNote(undefined); }} />
          </div>
          {dueDate && <span className="small muted">The doctor is reminded on {date(dueDate)}.</span>}
        </div>
      )}

      {changed && (
        <label className="field">Comment (optional, kept in the payment history)
          <input value={comment} maxLength={300} placeholder="e.g. Paid by UPI" onChange={(e) => setComment(e.target.value)} />
        </label>
      )}
      {changed && !savedWithFinish && (
        <button type="button" className="primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save payment'}</button>
      )}
      {changed && savedWithFinish && <span className="small muted">Saved when you tap Finish visit.</span>}
    </div>
  );
});


/** A rupee amount box with a mic: say "three hundred" or "₹150" and the number is filled in. */
function AmountField({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (v: string) => void }) {
  const [heard, setHeard] = useState<string>();
  const d = useDictation({
    onStart: () => setHeard(undefined),
    onText: (spoken) => {
      const n = spokenAmount(spoken);
      if (n === undefined) {
        setHeard(`Heard "${spoken}". Say an amount like "three hundred".`);
      } else {
        setHeard(undefined);
        onChange(String(n));
      }
    },
  });
  return (
    <div className="field">
      <div className="field-head">
        <label htmlFor={id}>{label}</label>
        <MicButton listening={d.listening} onClick={d.toggle} label={label.replace(' ₹', '')} />
      </div>
      <input id={id} inputMode="numeric" placeholder={d.listening ? 'Say the amount…' : '0'} value={value}
             className={d.listening ? 'listening' : undefined} onChange={(e) => onChange(e.target.value)} />
      {d.listening && d.interim && <span className="dictating" aria-live="polite">{d.interim}</span>}
      {heard && <span className="dictating bad">{heard}</span>}
      {d.error && <span className="dictating bad">{d.error}</span>}
    </div>
  );
}

/**
 * A visit's payment at a glance, for the patient's visit list: "Paid ₹450", or "₹150 due · 10 Nov" with a box
 * for the amount received now (never more than what's due) and Full received, or Add payment (opens the form).
 */
export function VisitPaymentQuick({ visit }: { visit: EncounterListItem }) {
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [amount, setAmount] = useState('');
  const [capped, setCapped] = useState(false);
  const [comment, setComment] = useState('');

  async function receive(value: number) {
    setBusy(true);
    setError(undefined);
    try {
      await api.post(`/clinic/encounters/${visit.id}/payment/receive`, { amount: value, comment: comment.trim() || undefined });
      setAmount('');
      setComment('');
      setCapped(false);
      paymentsChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not update the payment.');
    } finally {
      setBusy(false);
    }
  }

  // Digits only, and never more than what is due: a bigger number is cut down to the amount due
  function typeAmount(raw: string, due: number) {
    const digits = raw.replace(/\D/g, '');
    const n = Number(digits || 0);
    setCapped(n > due);
    setAmount(n > due ? String(due) : digits.replace(/^0+(?=\d)/, ''));
  }

  if (adding) {
    return (
      <div className="pay-quick">
        <VisitPayment encounterId={visit.id} />
        <button type="button" className="link small" onClick={() => setAdding(false)}>Close</button>
      </div>
    );
  }
  if (visit.paid == null) {
    return (
      <div className="pay-quick">
        <button type="button" className="small" onClick={() => setAdding(true)}>+ Add payment</button>
      </div>
    );
  }
  const balance = visit.balance ?? 0;
  if (balance === 0) {
    return (
      <div className="pay-quick">
        <span className="chip ok">Paid {rupees(visit.paid)}</span>
      </div>
    );
  }
  const late = !!visit.dueDate && visit.dueDate < todayIst();
  const n = Number(amount || 0);
  return (
    <div className="pay-quick">
      <span className={`chip ${late ? 'allergy' : 'due'}`}>{rupees(balance)} due · {date(visit.dueDate)}</span>
      <span className="small muted">paid {rupees(visit.paid)}</span>
      <form className="pay-actions" onSubmit={(e) => { e.preventDefault(); if (n >= 1 && n <= balance) receive(n); }}>
        <span className="amount-box">
          <span aria-hidden="true">₹</span>
          <input aria-label={`Amount received now, up to ₹${balance}`} inputMode="numeric" placeholder={`up to ${balance}`}
                 value={amount} onChange={(e) => typeAmount(e.target.value, balance)} disabled={busy} />
        </span>
        <button type="submit" className="small" disabled={busy || n < 1}>
          {n >= 1 && n < balance ? `Received ${rupees(n)}` : 'Received'}
        </button>
        <button type="button" className="small primary" disabled={busy} onClick={() => receive(balance)}>Full {rupees(balance)}</button>
      </form>
      <input className="pay-comment" aria-label="Comment (optional)" placeholder="Comment (optional)" maxLength={300}
             value={comment} onChange={(e) => setComment(e.target.value)} disabled={busy} />
      {capped && <span className="small field-hint">Can't be more than {rupees(balance)} due.</span>}
      {n >= 1 && n < balance && !capped && <span className="small muted">{rupees(balance - n)} will still be due on {date(visit.dueDate)}.</span>}
      {error && <span className="small" style={{ color: 'var(--danger)' }}>{error}</span>}
    </div>
  );
}
