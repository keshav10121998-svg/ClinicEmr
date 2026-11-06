import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useCached } from '../cache';
import type { Earnings, EarningsRange, OwingList } from '../types';
import { ageSex, date } from '../format';
import { dueLabel, PAYMENTS_CHANGED, rupees } from '../payments';

const LABELS: Record<EarningsRange, string> = { TODAY: 'Today', THIS_MONTH: 'This month', THIS_YEAR: 'This year', ALL: 'All time' };
const RANGES = Object.keys(LABELS) as EarningsRange[];

/**
 * Earnings today, this month, this year and in all: total charged, paid and balance for the visits in each period.
 * Tap a balance to see who still owes. Doctors see their own visits; admins and front desk see the whole clinic.
 */
export function EarningsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const owingRange = RANGES.find((r) => r.toLowerCase() === params.get('owing'));
  const [version, setVersion] = useState(0);
  // Shown at once from the copy kept on this phone, then refreshed (and again after any payment)
  const { data: earnings, error: earningsError } = useCached<Earnings>('/clinic/earnings', 'Could not load earnings.', version);
  const [owing, setOwing] = useState<OwingList>();
  const [error, setError] = useState<string>();
  const list = useRef<HTMLDivElement>(null);

  const show = (o?: EarningsRange) => setParams(o ? { owing: o.toLowerCase() } : {}, { replace: true });

  const loadOwing = useCallback(() => {
    if (!owingRange) { setOwing(undefined); return; }
    api.get<OwingList>(`/clinic/earnings/owing?range=${owingRange.toLowerCase()}`)
      .then(setOwing)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load the list.'));
  }, [owingRange]);

  useEffect(() => { setOwing(undefined); loadOwing(); }, [loadOwing]);
  useEffect(() => { if (owingRange) list.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, [owingRange]);
  useEffect(() => {
    const refresh = () => { setVersion((v) => v + 1); loadOwing(); };
    window.addEventListener(PAYMENTS_CHANGED, refresh);
    return () => window.removeEventListener(PAYMENTS_CHANGED, refresh);
  }, [loadOwing]);


  return (
    <>
      <h1>Earnings</h1>
      {earnings && <p className="muted small" style={{ margin: 0 }}>{earnings.mine ? 'Your visits.' : 'All visits at this clinic.'} Total = paid + balance, for visits in each period.</p>}
      {(earningsError ?? error) && <div className="alert error">{earningsError ?? error}</div>}

      <div className="earn-grid">
        {RANGES.map((r) => {
          const p = earnings?.periods.find((x) => x.range === r);
          return (
            <div key={r} className={`earn-card${r === 'TODAY' ? ' main' : ''}`}>
              <span className="stat-label">{LABELS[r]}</span>
              <span className="earn-total">{p ? rupees(p.total) : '–'}</span>
              <span className="muted small">{p ? `${p.visits} ${p.visits === 1 ? 'visit' : 'visits'} with a payment` : ' '}</span>
              <div className="earn-split">
                <div className="earn-paid"><span>Paid</span><b>{p ? rupees(p.paid) : '–'}</b></div>
                <button type="button" className="earn-balance" disabled={!p || p.balance === 0} aria-pressed={owingRange === r}
                        onClick={() => show(owingRange === r ? undefined : r)}
                        aria-label={p ? `Balance ${rupees(p.balance)}. Show who owes` : 'Balance'}>
                  <span>Balance</span><b>{p ? rupees(p.balance) : '–'}</b>
                  {p && p.balance > 0 && <small>Who owes?</small>}
                </button>
              </div>
              {p && p.collected !== p.paid && (
                <span className="muted small">Money received {r === 'ALL' ? 'in all' : LABELS[r].toLowerCase()}: <b>{rupees(p.collected)}</b></span>
              )}
            </div>
          );
        })}
      </div>

      {owingRange && (
        // Opened from a balance: scrolled into view, since on a phone it is below the tiles
        <div ref={list} className="card money-due" style={{ scrollMarginTop: 64 }}>
          <div className="owing-head">
            <div>
              <h2>Balance · {LABELS[owingRange]}</h2>
              {owing && <span className="owing-total">{rupees(owing.balance)} to collect</span>}
            </div>
            <button type="button" className="icon-close" onClick={() => show()} aria-label="Close the list" title="Close">
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
              </svg>
            </button>
          </div>
          <p className="muted small" style={{ margin: 0 }}>
            Patients who still owe for visits {owingRange === 'ALL' ? 'at any time' : LABELS[owingRange].toLowerCase()}, the longest overdue first.
          </p>
          {!owing && <p className="muted">Loading…</p>}
          {owing && owing.items.length === 0 && <p className="muted">Nobody owes anything for this period.</p>}
          <div className="patient-list">
            {owing?.items.map((o) => (
              <div key={o.patientId} className="due-row">
                <button type="button" className="link due-name" onClick={() => navigate(`/patients/${o.patientId}`)}>{o.name}</button>
                <span className="due-amount">{rupees(o.balance)}</span>
                <span className={`small ${o.overdueDays > 0 ? 'late' : 'muted'}`}>
                  {[ageSex({ ...o, dobEstimated: false }), o.mrn].filter(Boolean).join(' · ')}
                  <br />{dueLabel(o.overdueDays)} · {o.visits} {o.visits === 1 ? 'visit' : 'visits'} · last visit {date(o.lastVisitAt)}
                </span>
                <span className="due-actions">
                  {o.phone && <a className="small" href={`tel:+91${o.phone}`}>Call {o.phone}</a>}
                  <button type="button" className="small" onClick={() => navigate(`/patients/${o.patientId}`)}>Open</button>
                </span>
              </div>
            ))}
          </div>
          {owing?.truncated && <p className="muted small">Showing the first 500 patients.</p>}
        </div>
      )}
    </>
  );
}
