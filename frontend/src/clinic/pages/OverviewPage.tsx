import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { VisitCount, VisitorList, VisitRange } from '../types';
import { ageSex } from '../format';

const LABELS: Record<VisitRange, string> = {
  TODAY: 'Today', YESTERDAY: 'Yesterday', LAST_7_DAYS: 'Last 7 days', THIS_YEAR: 'This year', ALL: 'All time',
};
const RANGES = Object.keys(LABELS) as VisitRange[];

/** How many patients came in, per period. Tap a period to see who they were. */
export function OverviewPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const range = (RANGES.find((r) => r === params.get('range')) ?? 'TODAY') as VisitRange;
  const [counts, setCounts] = useState<VisitCount[]>();
  const [list, setList] = useState<VisitorList>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    api.get<VisitCount[]>('/clinic/visit-stats')
      .then(setCounts)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load the numbers.'));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setList(undefined);
    api.get<VisitorList>(`/clinic/visit-stats/visitors?range=${range.toLowerCase()}`)
      .then((l) => !cancelled && setList(l))
      .catch((e) => !cancelled && setError(e instanceof ApiError ? e.message : 'Could not load the list.'));
    return () => { cancelled = true; };
  }, [range]);

  const showDate = range !== 'TODAY' && range !== 'YESTERDAY';

  return (
    <>
      <h1>Overview</h1>
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
