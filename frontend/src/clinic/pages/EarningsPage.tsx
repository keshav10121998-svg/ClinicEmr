import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { Earnings, EarningsRange, OwingList } from '../types';
import { ageSex, date } from '../format';
import { useSession } from '../session';
import { dueLabel, PAYMENTS_CHANGED, rupees } from '../payments';

const LABELS: Record<EarningsRange, string> = { TODAY: 'Today', THIS_MONTH: 'This month', THIS_YEAR: 'This year', ALL: 'All time' };
const RANGES = Object.keys(LABELS) as EarningsRange[];

/**
 * Earnings today, this month, this year and in all: total charged, paid and balance for the visits in each period.
 * Tap a balance to see who still owes. Doctors see their own visits; admins and front desk can switch to the clinic.
 */
export function EarningsPage() {
  const navigate = useNavigate();
  const { has } = useSession();
  const [params, setParams] = useSearchParams();
  const owingRange = RANGES.find((r) => r.toLowerCase() === params.get('owing'));
  const mineAsked = params.get('scope') === 'mine';
  const [earnings, setEarnings] = useState<Earnings>();
  const [owing, setOwing] = useState<OwingList>();
  const [error, setError] = useState<string>();
  const list = useRef<HTMLDivElement>(null);

  const set = (next: { owing?: EarningsRange; mine?: boolean }) => {
    const p: Record<string, string> = {};
    const o = 'owing' in next ? next.owing : owingRange;
    const m = 'mine' in next ? next.mine : mineAsked;
    if (o) p.owing = o.toLowerCase();
    if (m) p.scope = 'mine';
    setParams(p, { replace: true });
  };

  const load = useCallback(() => {
    api.get<Earnings>(`/clinic/earnings?mine=${mineAsked}`)
      .then(setEarnings)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load earnings.'));
  }, [mineAsked]);

  const loadOwing = useCallback(() => {
    if (!owingRange) { setOwing(undefined); return; }
    api.get<OwingList>(`/clinic/earnings/owing?range=${owingRange.toLowerCase()}&mine=${mineAsked}`)
      .then(setOwing)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load the list.'));
  }, [owingRange, mineAsked]);

  useEffect(() => { setOwing(undefined); loadOwing(); }, [loadOwing]);
  useEffect(() => { if (owingRange) list.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, [owingRange]);
  useEffect(() => {
    load();
    const refresh = () => { load(); loadOwing(); };
    window.addEventListener(PAYMENTS_CHANGED, refresh);
    return () => window.removeEventListener(PAYMENTS_CHANGED, refresh);
  }, [load, loadOwing]);

  // The scope switch is for admins and front desk who also see patients themselves
  const showSwitch = earnings?.canSeeClinic && has('DOCTOR');
  const mine = earnings?.mine ?? mineAsked;

  return (
    <>
      <div className="between">
        <h1>Earnings</h1>
        {showSwitch && (
          <span className="scope-switch" role="group" aria-label="Whose visits">
            <button type="button" aria-pressed={!mine} onClick={() => set({ mine: false })}>Whole clinic</button>
            <button type="button" aria-pressed={mine} onClick={() => set({ mine: true })}>My visits</button>
          </span>
        )}
      </div>
      {earnings && <p className="muted small" style={{ margin: 0 }}>{mine ? 'Your visits.' : 'All visits at this clinic.'} Total = paid + balance, for visits in each period.</p>}
      {error && <div className="alert error">{error}</div>}

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
                        onClick={() => set({ owing: owingRange === r ? undefined : r })}
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
          <div className="between">
            <h2>Balance · {LABELS[owingRange]}</h2>
            <span className="row">
              {owing && <b>{rupees(owing.balance)}</b>}
              <button type="button" className="small" onClick={() => set({ owing: undefined })}>Close</button>
            </span>
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
