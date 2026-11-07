import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useCached } from '../cache';
import { date } from '../format';
import { useSession } from '../session';
import { Stars, StarPicker } from '../../shared/Stars';
import type { AppRating, ClinicRatings } from '../types';

/** What patients said about this clinic (admins and doctors only): the average, the spread, doctors and recent words. */
export function PatientRatings() {
  const { has } = useSession();
  return has('ADMIN', 'DOCTOR') ? <PatientRatingsCard /> : null;
}

function PatientRatingsCard() {
  const { data, error } = useCached<ClinicRatings>('/clinic/ratings', 'Could not load the ratings.');
  const [all, setAll] = useState(false);
  if (error && !data) return null;
  if (!data) return null;
  const { summary, doctors, recent } = data;
  const words = recent.filter((r) => r.comment);
  const shown = all ? words : words.slice(0, 5);
  return (
    <div className="card ratings-card">
      <div className="between">
        <h2>Patient ratings</h2>
        {data.mine && <span className="muted small">Your visits only</span>}
      </div>
      {summary.count === 0 ? (
        <p className="muted">No ratings yet. Patients can rate each visit in the patient app, after the prescription is signed.</p>
      ) : (
        <>
          <div className="rating-head">
            <span className="rating-number">{summary.average.toFixed(1)}</span>
            <span>
              <Stars value={summary.average} size={22} />
              <br /><span className="muted small">{summary.count} {summary.count === 1 ? 'rating' : 'ratings'}</span>
            </span>
          </div>
          <div className="rating-bars">
            {[5, 4, 3, 2, 1].map((n) => (
              <div key={n} className="rating-bar">
                <span className="small">{n}★</span>
                <span className="rating-track"><span style={{ width: `${(summary.distribution[n - 1] / summary.count) * 100}%` }} /></span>
                <span className="small muted">{summary.distribution[n - 1]}</span>
              </div>
            ))}
          </div>
          {doctors.length > 1 && (
            <div className="rating-doctors">
              {doctors.map((d) => (
                <div key={d.doctorId} className="between small">
                  <span>{d.doctorName}</span>
                  <span><b>{d.average.toFixed(1)}</b> ★ <span className="muted">({d.count})</span></span>
                </div>
              ))}
            </div>
          )}
          {shown.length > 0 && (
            <div className="rating-words">
              {shown.map((r, i) => (
                <blockquote key={i}>
                  <Stars value={r.stars} size={14} /> <span className="muted small">{r.doctorName} · {date(r.ratedAt)}</span>
                  <p>{r.comment}</p>
                </blockquote>
              ))}
              {words.length > 5 && <button type="button" className="link" onClick={() => setAll((v) => !v)}>{all ? 'Show fewer' : `Show all ${words.length} comments`}</button>}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/**
 * A short line on Overview that opens the Ratings page: the average and count, and a nudge to rate ClinicEMR until the
 * clinic has. Shows nothing when there is nothing to say, and nothing for staff who can't see ratings.
 */
export function RatingsStrip() {
  const { has } = useSession();
  return has('ADMIN', 'DOCTOR') ? <RatingsStripInner /> : null;
}

function RatingsStripInner() {
  const navigate = useNavigate();
  const { data } = useCached<ClinicRatings>('/clinic/ratings', 'Could not load the ratings.');
  const { data: mine, refreshing } = useCached<AppRating | undefined>('/clinic/app-rating', 'Could not load your rating.');
  const rated = data && data.summary.count > 0;
  const askForApp = mine === undefined && !refreshing;
  if (!rated && !askForApp) return null;
  return (
    <button type="button" className="ratings-strip" onClick={() => navigate('/ratings')}>
      {rated && data && (
        <span><Stars value={data.summary.average} size={18} /> <b>{data.summary.average.toFixed(1)}</b>
          <span className="muted small"> from {data.summary.count} {data.summary.count === 1 ? 'patient rating' : 'patient ratings'}</span></span>
      )}
      {askForApp && <span className="small">How do you like ClinicEMR? <u>Rate it</u></span>}
      <span className="small">See ratings ›</span>
    </button>
  );
}

/** "How do you like ClinicEMR?": the clinic's own rating of the app, once, changeable. Admins and doctors only. */
export function RateApp() {
  const { has } = useSession();
  return has('ADMIN', 'DOCTOR') ? <RateAppCard /> : null;
}

function RateAppCard() {
  const [version, setVersion] = useState(0);
  const { data: mine, refreshing } = useCached<AppRating | undefined>('/clinic/app-rating', 'Could not load your rating.', version);
  const [editing, setEditing] = useState(false);
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const [allowPublic, setAllowPublic] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function save() {
    setBusy(true);
    setError(undefined);
    try {
      await api.put<AppRating>('/clinic/app-rating', { stars, comment: comment.trim() || undefined, allowPublic });
      setEditing(false);
      setVersion((v) => v + 1);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save your rating. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  }

  if (mine === undefined && refreshing) return null;
  if (mine && !editing) {
    return (
      <div className="card rate-app">
        <div className="between">
          <span>Your rating of ClinicEMR <Stars value={mine.stars} size={18} /></span>
          <button type="button" className="link" onClick={() => { setStars(mine.stars); setComment(mine.comment ?? ''); setAllowPublic(mine.allowPublic); setEditing(true); }}>Change</button>
        </div>
        <span className="muted small">Thank you for your feedback.</span>
      </div>
    );
  }
  return (
    <div className="card rate-app">
      <h2>How do you like ClinicEMR?</h2>
      <StarPicker value={stars} onChange={setStars} label="Your rating of ClinicEMR" />
      {stars > 0 && (
        <>
          <label className="field">What works well, or what should be better? (optional)
            <textarea rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
          </label>
          <label className="check">
            <input type="checkbox" checked={allowPublic} onChange={(e) => setAllowPublic(e.target.checked)} />
            ClinicEMR may show my words and my clinic's name on its website
          </label>
          {error && <div className="alert error">{error}</div>}
          <div className="row">
            <button type="button" className="primary" disabled={busy} onClick={save}>{busy ? 'Saving…' : 'Send'}</button>
            {editing && <button type="button" disabled={busy} onClick={() => setEditing(false)}>Cancel</button>}
          </div>
        </>
      )}
    </div>
  );
}
