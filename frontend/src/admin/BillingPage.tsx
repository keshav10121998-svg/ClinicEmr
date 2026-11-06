import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { adminApi, ApiError, type BillingOverview, type ClinicPlan, type SubscriptionPayment } from './api';
import { date, dateTime } from '../clinic/format';
import { rupees } from '../clinic/payments';

const STAGE = { FREE_MONTH: 'Free month', LAUNCH: 'Launch price', STANDARD: 'Standard price' } as const;
const FILTERS = [['ALL', 'All'], ['DUE', 'Payment due'], ['PAID', 'Paid'], ['FREE_MONTH', 'Free month']] as const;

/** Every clinic's plan: who is free, paid or behind, what each owes next, and recording a payment received. */
export function BillingPage() {
  const [data, setData] = useState<BillingOverview>();
  const [filter, setFilter] = useState<(typeof FILTERS)[number][0]>('ALL');
  const [error, setError] = useState<string>();
  const [flash, setFlash] = useState<string>();

  const load = () => adminApi.get<BillingOverview>('/admin/billing').then(setData)
    .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load billing.'));
  useEffect(() => { load(); }, []);

  const shown = useMemo(() => data?.items.filter((c) => filter === 'ALL' || c.plan.status === filter), [data, filter]);

  const tiles: [string, string | number | undefined, string?][] = [
    ['Payment due', data?.due, 'plan ran out'],
    ['Paid', data?.paid, 'up to date'],
    ['Free month', data?.freeMonth, 'trying it out'],
    ['This month', data && rupees(data.monthlyIncome), 'from clinics past the free month'],
  ];

  return (
    <>
      <div className="between">
        <h1>Billing</h1>
        <Link className="admin-link-btn" to="/admin">Doctors</Link>
      </div>
      {error && <div className="alert error">{error}</div>}
      {flash && <div className="alert info">{flash}</div>}
      <div className="stat-tiles admin-billing-tiles">
        {tiles.map(([label, n, sub], i) => (
          <div key={label} className={`stat-tile${i === 0 ? ' main' : ''}`}>
            <span className="stat-label">{label}</span>
            <span className="stat-number">{n ?? '–'}</span>
            <span className="stat-sub">{sub}</span>
          </div>
        ))}
      </div>
      <div className="card">
        <div className="between">
          <h2>Clinics</h2>
          {shown && <span className="muted small">{shown.length} shown</span>}
        </div>
        <div className="scope-tabs" role="group" aria-label="Show">
          {FILTERS.map(([k, label]) => (
            <button key={k} type="button" aria-pressed={filter === k} onClick={() => setFilter(k)}>{label}</button>
          ))}
        </div>
        {!data && !error && <p className="muted">Loading…</p>}
        {shown?.length === 0 && <p className="muted">No clinics here.</p>}
        <div className="patient-list">
          {shown?.map((c) => (
            <ClinicBilling key={c.plan.clinicId} c={c} onPaid={(msg) => { setFlash(msg); load(); }} />
          ))}
        </div>
      </div>
    </>
  );
}

function ClinicBilling({ c, onPaid }: { c: ClinicPlan; onPaid: (message: string) => void }) {
  const p = c.plan;
  const [open, setOpen] = useState(false);
  const [history, setHistory] = useState<SubscriptionPayment[]>();
  const [amount, setAmount] = useState(String(p.nextAmount));
  const [months, setMonths] = useState(1);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  // The amount follows the months chosen: the price of the next month, once for each month
  useEffect(() => { setAmount(String(p.nextAmount * months)); }, [p.nextAmount, months]);
  useEffect(() => {
    if (!open) return;
    adminApi.get<SubscriptionPayment[]>(`/admin/billing/clinics/${p.clinicId}/payments`).then(setHistory).catch(() => setHistory([]));
  }, [open, p.clinicId, c.payments]);

  async function record(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const updated = await adminApi.post<ClinicPlan['plan']>(`/admin/billing/clinics/${p.clinicId}/payments`,
        { amount: Number(amount), months, note: note.trim() || undefined });
      setOpen(false);
      setNote('');
      onPaid(`Recorded ${rupees(Number(amount))} for ${p.clinicName}. Paid until ${date(updated.coveredUntil)}.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not record the payment.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="due-row admin-billing-row">
      <b className="due-name">{p.clinicName}{c.city ? <span className="muted small"> · {c.city}</span> : null}</b>
      <span className={`chip ${p.status === 'DUE' ? 'allergy' : 'ok'}`}>
        {p.status === 'DUE' ? 'Payment due' : p.status === 'PAID' ? 'Paid' : 'Free month'}
      </span>
      <span className="small muted">
        {STAGE[p.stage]} · month {p.monthNumber} · {p.billableStaff} extra staff
        <br />{p.status === 'DUE' ? <span className="late">Ran out {date(p.coveredUntil)}</span> : <>Covered until {date(p.coveredUntil)}</>}
        {' · '}Next: <b>{rupees(p.nextAmount)}</b>
        <br />{[c.ownerName, c.ownerPhone, c.ownerEmail].filter(Boolean).join(' · ')}
      </span>
      <span className="due-actions">
        {c.ownerPhone && <a className="small" href={`tel:+91${c.ownerPhone}`}>Call</a>}
        <button type="button" className="small" onClick={() => setOpen((v) => !v)}>{open ? 'Close' : 'Record payment'}</button>
      </span>
      {open && (
        <form className="admin-pay" onSubmit={record}>
          {error && <div className="alert error">{error}</div>}
          <div className="fields">
            <label className="field">Months paid for
              <select value={months} onChange={(e) => setMonths(Number(e.target.value))}>
                {[1, 2, 3, 6, 12].map((m) => <option key={m} value={m}>{m} {m === 1 ? 'month' : 'months'}</option>)}
              </select>
            </label>
            <label className="field">Amount received (₹)
              <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />
            </label>
          </div>
          <label className="field">Note (optional, e.g. UPI reference)
            <input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button className="primary" disabled={busy || amount === ''}>{busy ? 'Saving...' : 'Save payment'}</button>
          {history && history.length > 0 && (
            <ul className="admin-pay-history">
              {history.map((h) => (
                <li key={h.id}>
                  <b>{rupees(h.amount)}</b> for {h.months} {h.months === 1 ? 'month' : 'months'}, until {date(h.coversUntil)}
                  <span className="muted small"> · {dateTime(h.recordedAt)}{h.note ? ` · ${h.note}` : ''}</span>
                </li>
              ))}
            </ul>
          )}
        </form>
      )}
    </div>
  );
}
