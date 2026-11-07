import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useCached } from '../cache';
import type { VisitCount, VisitorList, VisitRange } from '../types';
import { ageSex } from '../format';
import { useSession } from '../session';
import { dueLabel, onlyMine, paymentsChanged, rupees, useDuePayments } from '../payments';
import { PaymentRequests } from '../components/PaymentRequests';
import { RatingsStrip } from '../components/Ratings';

const LABELS: Record<VisitRange, string> = {
  TODAY: 'Today', YESTERDAY: 'Yesterday', LAST_7_DAYS: 'Last 7 days', THIS_YEAR: 'This year', ALL: 'All time',
};
const RANGES = Object.keys(LABELS) as VisitRange[];

/** How many patients came in, per period. Tap a period to see who they were. */
export function OverviewPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const range = (RANGES.find((r) => r === params.get('range')) ?? 'TODAY') as VisitRange;
  // Shown at once from the copy kept on this phone, then refreshed
  const { data: counts, error: countsError } = useCached<VisitCount[]>('/clinic/visit-stats', 'Could not load the numbers.');
  const { data: list, error: listError } = useCached<VisitorList>(`/clinic/visit-stats/visitors?range=${range.toLowerCase()}`, 'Could not load the list.');
  const error = countsError ?? listError;

  const showDate = range !== 'TODAY' && range !== 'YESTERDAY';

  return (
    <>
      <h1>Overview</h1>
      <PaymentRequests onOpenPatient={(id) => navigate(`/patients/${id}`)} />
      <MoneyToCollect />
      <RatingsStrip />
      {error && <div className="alert error">{error}</div>}

      <div className="stat-tiles" role="tablist" aria-label="Period">
        {RANGES.map((r) => {
          const c = counts?.find((x) => x.range === r);
          return (
            <button key={r} type="button" role="tab" aria-selected={r === range}
                    className={`stat-tile${r === 'TODAY' ? ' main' : ''}`}
                    onClick={() => setParams(r === 'TODAY' ? {} : { range: r }, { replace: true })}>
              <span className="stat-label">{LABELS[r]}</span>
              <span className="stat-number">{c ? c.patients : '–'}</span>
              <span className="stat-sub">
                {c ? `${c.patients === 1 ? 'patient' : 'patients'}${c.visits !== c.patients ? ` · ${c.visits} visits` : ''}` : ' '}
              </span>
            </button>
          );
        })}
      </div>

      <div className="card">
        <div className="between">
          <h2>{LABELS[range]}</h2>
          {list && <span className="muted small">{list.patients} {list.patients === 1 ? 'patient' : 'patients'} · {list.visits} {list.visits === 1 ? 'visit' : 'visits'}</span>}
        </div>
        {!list && !error && <p className="muted">Loading…</p>}
        {list && list.items.length === 0 && <p className="muted">No visits {range === 'TODAY' ? 'yet today' : 'in this period'}.</p>}
        <div className="patient-list">
          {list?.items.map((v) => (
            <button key={v.encounterId} type="button" className="patient-row visitor-row"
                    onClick={() => navigate(`/patients/${v.patientId}`)}>
              <span className="avatar" aria-hidden="true">{initials(v.name)}</span>
              <span className="patient-name">{v.name}</span>
              <span className="muted small">
                {[ageSex({ ...v, dobEstimated: false }), v.mrn, v.doctorName].filter(Boolean).join(' · ')}
                <br /><b className="visit-total">{visitsLabel(v.visitCount)}</b>
                {v.diagnoses ? <><br /><span style={{ color: 'var(--ink)' }}>{v.diagnoses}</span></> : null}
              </span>
              <span className="small last-visit">
                {visitTime(v.startedAt, showDate)}
                {v.status === 'DRAFT' && <><br /><span className="chip">In progress</span></>}
              </span>
            </button>
          ))}
        </div>
        {list?.truncated && <p className="muted small">Showing the latest 500 visits.</p>}
      </div>
    </>
  );
}

function visitTime(iso: string, withDate: boolean) {
  const opts: Intl.DateTimeFormatOptions = { hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Kolkata' };
  if (withDate) Object.assign(opts, { day: 'numeric', month: 'short' });
  return new Date(iso).toLocaleString('en-IN', opts);
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

function visitsLabel(n: number) {
  return n === 0 ? 'First visit, not finished yet' : `${n} ${n === 1 ? 'visit' : 'visits'} in total`;
}

/**
 * The due-date reminder: balances due today or earlier. Doctors see their own patients; admins and front desk see
 * the clinic's. Tap a name to open the patient, or mark the balance received here.
 */
function MoneyToCollect() {
  const navigate = useNavigate();
  const { has } = useSession();
  const { dues, reload } = useDuePayments(onlyMine(has));
  const [error, setError] = useState<string>();

  if (!dues || dues.length === 0) return null;
  const total = dues.reduce((s, d) => s + d.balance, 0);

  async function received(encounterId: string) {
    setError(undefined);
    try {
      await api.post(`/clinic/encounters/${encounterId}/payment/settle`);
      paymentsChanged();
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not update the payment.');
    }
  }

  return (
    <div className="card money-due">
      <div className="between">
        <h2>Money to collect</h2>
        <b>{rupees(total)}</b>
      </div>
      {error && <div className="alert error">{error}</div>}
      <div className="patient-list">
        {dues.map((d) => (
          <div key={d.encounterId} className="due-row">
            <button type="button" className="link due-name" onClick={() => navigate(`/patients/${d.patientId}`)}>{d.name}</button>
            <span className="due-amount">{rupees(d.balance)}</span>
            <span className={`small ${d.overdueDays > 0 ? 'late' : 'muted'}`}>
              {dueLabel(d.overdueDays)} · visit {visitTime(d.visitAt, true)} · {d.doctorName}
            </span>
            <span className="due-actions">
              {d.phone && <a className="small" href={`tel:+91${d.phone}`}>Call {d.phone}</a>}
              <button type="button" className="small" onClick={() => received(d.encounterId)}>Received</button>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
