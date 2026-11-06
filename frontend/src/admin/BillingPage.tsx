import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { adminApi, ApiError, type BillingOverview, type ClinicPlan, type SubscriptionPayment } from './api';
import { date, dateTime } from '../clinic/format';
import { rupees } from '../clinic/payments';

const STATUS = { ACTIVE: 'Active', EXPIRING: 'Ending soon', EXPIRED: 'Ended', NOT_ENABLED: 'Not switched on' } as const;
const FILTERS = [['ALL', 'All'], ['ATTENTION', 'Needs attention'], ['EXPIRING', 'Ending soon'], ['ACTIVE', 'Active']] as const;
const MONTHS = [1, 2, 3, 4, 5, 6, 9, 12, 18, 24, 36];

/**
 * Every clinic's access. The terms are agreed with each clinic in person; here you switch it on for some months, free
 * or at the agreed price, and record the money received. A clinic that is off or has run out can only view its records.
 */
export function BillingPage() {
  const [data, setData] = useState<BillingOverview>();
  const [filter, setFilter] = useState<(typeof FILTERS)[number][0]>('ALL');
  const [error, setError] = useState<string>();
  const [flash, setFlash] = useState<string>();

  const load = () => adminApi.get<BillingOverview>('/admin/billing').then(setData)
    .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load billing.'));
  useEffect(() => { load(); }, []);

  const shown = useMemo(() => data?.items.filter((c) => {
    const s = c.plan.status;
    return filter === 'ALL' || (filter === 'ATTENTION' ? c.plan.blocked : s === filter);
  }), [data, filter]);

  const tiles: [string, string | number | undefined, string?][] = [
    ['Needs attention', data && data.expired + data.notEnabled, 'not on, or ended'],
    ['Ending soon', data?.expiring, 'within 7 days'],
    ['Active', data?.active, 'working normally'],
    ['Per month', data && rupees(data.monthlyIncome), 'agreed prices of clinics switched on'],
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
            <ClinicBilling key={c.plan.clinicId} c={c} onChanged={(msg) => { setFlash(msg); load(); }} />
          ))}
        </div>
      </div>
    </>
  );
}

function ClinicBilling({ c, onChanged }: { c: ClinicPlan; onChanged: (message: string) => void }) {
  const p = c.plan;
  const [open, setOpen] = useState(false);
  const [history, setHistory] = useState<SubscriptionPayment[]>();
  const [free, setFree] = useState(false);
  const [months, setMonths] = useState(1);
  const [price, setPrice] = useState(p.monthlyPrice ? String(p.monthlyPrice) : '');
  const [amount, setAmount] = useState('');
  const [amountEdited, setAmountEdited] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmOff, setConfirmOff] = useState(false);
  const [error, setError] = useState<string>();

  // The amount received follows the price and months, until it is typed over (free means no money)
  useEffect(() => {
    if (!amountEdited) setAmount(free ? '0' : price ? String(Number(price) * months) : '');
  }, [free, price, months, amountEdited]);
  useEffect(() => {
    if (!open) return;
    adminApi.get<SubscriptionPayment[]>(`/admin/billing/clinics/${p.clinicId}/payments`).then(setHistory).catch(() => setHistory([]));
  }, [open, p.clinicId, c.payments]);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const updated = await adminApi.post<ClinicPlan['plan']>(`/admin/billing/clinics/${p.clinicId}/grant`, {
        months, free, monthlyPrice: free ? undefined : Number(price), amount: free ? undefined : Number(amount || 0), note: note.trim() || undefined,
      });
      setOpen(false);
      setNote('');
      setAmountEdited(false);
      onChanged(`${p.clinicName} is switched on until ${date(updated.accessUntil)}.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save.');
    } finally {
      setBusy(false);
    }
  }

  async function switchOff() {
    setBusy(true);
    setError(undefined);
    try {
      await adminApi.post(`/admin/billing/clinics/${p.clinicId}/switch-off`);
      setConfirmOff(false);
      onChanged(`${p.clinicName} is switched off. It can only view records now.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not switch off.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="due-row admin-billing-row">
      <b className="due-name">{p.clinicName}{c.city ? <span className="muted small"> · {c.city}</span> : null}</b>
      <span className={`chip ${p.blocked ? 'allergy' : p.status === 'EXPIRING' ? 'due' : 'ok'}`}>{STATUS[p.status]}</span>
      <span className="small muted">
        {p.status === 'NOT_ENABLED' ? <span className="late">Never switched on</span>
          : p.blocked ? <span className="late">Ended {date(p.accessUntil)}</span>
          : <>Until {date(p.accessUntil)} ({p.daysLeft} {p.daysLeft === 1 ? 'day' : 'days'} left)</>}
        {p.status !== 'NOT_ENABLED' && <> · {p.monthlyPrice ? `${rupees(p.monthlyPrice)} a month` : 'Free'}</>}
        <br />{[c.ownerName, c.ownerPhone, c.ownerEmail].filter(Boolean).join(' · ')}
      </span>
      <span className="due-actions">
        {c.ownerPhone && <a className="small" href={`tel:+91${c.ownerPhone}`}>Call</a>}
        <button type="button" className="small" onClick={() => setOpen((v) => !v)}>
          {open ? 'Close' : p.blocked ? 'Switch on' : 'Add months'}
        </button>
        {!p.blocked && !confirmOff && <button type="button" className="small link danger" onClick={() => setConfirmOff(true)}>Switch off</button>}
      </span>
      {confirmOff && (
        <div className="admin-pay">
          <span>Switch off {p.clinicName} now? It can still view its records, but cannot add or change anything.</span>
          <div className="row">
            <button type="button" className="danger" disabled={busy} onClick={switchOff}>{busy ? 'Saving...' : 'Yes, switch off'}</button>
            <button type="button" disabled={busy} onClick={() => setConfirmOff(false)}>Keep it on</button>
          </div>
        </div>
      )}
      {open && (
        <form className="admin-pay" onSubmit={save}>
          {error && <div className="alert error">{error}</div>}
          <span className="small muted">
            {p.blocked ? 'The months start today.' : `The months are added after ${date(p.accessUntil)}.`}
          </span>
          <div className="scope-tabs" role="group" aria-label="Free or paid">
            <button type="button" aria-pressed={!free} onClick={() => setFree(false)}>Paid</button>
            <button type="button" aria-pressed={free} onClick={() => setFree(true)}>Free</button>
          </div>
          <div className="fields">
            <label className="field">Months
              <select value={months} onChange={(e) => setMonths(Number(e.target.value))}>
                {MONTHS.map((m) => <option key={m} value={m}>{m} {m === 1 ? 'month' : 'months'}</option>)}
              </select>
            </label>
            {!free && (
              <label className="field">Agreed price a month (₹)
                <input inputMode="numeric" value={price} onChange={(e) => { setPrice(e.target.value.replace(/\D/g, '')); setAmountEdited(false); }} />
              </label>
            )}
          </div>
          {!free && (
            <label className="field">Amount received now (₹)
              <input inputMode="numeric" value={amount} onChange={(e) => { setAmount(e.target.value.replace(/\D/g, '')); setAmountEdited(true); }} />
            </label>
          )}
          <label className="field">Note (optional, e.g. what was agreed, UPI reference)
            <input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button className="primary" disabled={busy || (!free && !price)}>{busy ? 'Saving...' : free ? `Switch on free for ${months} ${months === 1 ? 'month' : 'months'}` : 'Save'}</button>
          {history && history.length > 0 && (
            <ul className="admin-pay-history">
              {history.map((h) => (
                <li key={h.id}>
                  <b>{h.amount === 0 && h.monthlyPrice === 0 ? 'Free' : rupees(h.amount)}</b> for {h.months} {h.months === 1 ? 'month' : 'months'}, until {date(h.coversUntil)}
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
