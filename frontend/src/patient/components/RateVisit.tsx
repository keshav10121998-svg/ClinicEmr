import { useState } from 'react';
import { api, ApiError } from '../api';
import { Stars, StarPicker } from '../../shared/Stars';
import type { MyRating } from '../types';

/** "How was your visit?": 1 to 5 stars and an optional few words, for the clinic and doctor. Can be changed later. */
export function RateVisit({ profileId, encounterId, clinicName, initial, onSaved }:
  { profileId: string; encounterId: string; clinicName: string; initial?: MyRating; onSaved?: (r: MyRating) => void }) {
  const [saved, setSaved] = useState<MyRating | undefined>(initial);
  const [editing, setEditing] = useState(false);
  const [stars, setStars] = useState(initial?.stars ?? 0);
  const [comment, setComment] = useState(initial?.comment ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function send() {
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.put<MyRating>(`/patient/profiles/${profileId}/visits/${encounterId}/rating`, { stars, comment: comment.trim() || undefined });
      setSaved(r);
      setEditing(false);
      onSaved?.(r);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send your rating. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  }

  if (saved && !editing) {
    return (
      <div className="card" style={{ gap: 8 }}>
        <b>Thank you for rating your visit</b>
        <Stars value={saved.stars} size={24} />
        {saved.comment && <p className="muted" style={{ margin: 0 }}>“{saved.comment}”</p>}
        <button className="link" style={{ justifySelf: 'start' }} onClick={() => { setStars(saved.stars); setComment(saved.comment ?? ''); setEditing(true); }}>Change my rating</button>
      </div>
    );
  }

  return (
    <div className="card" style={{ gap: 10 }}>
      <b>How was your visit to {clinicName}?</b>
      <StarPicker value={stars} onChange={setStars} label="Your rating of this visit" />
      {stars > 0 && (
        <>
          <label className="field">A few words (optional)
            <textarea rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)}
                      placeholder="What went well, or what could be better?" />
          </label>
          <p className="small muted" style={{ margin: 0 }}>The clinic sees your stars and words, but not your name.</p>
          {error && <div className="alert error" role="alert">{error}</div>}
          <div className="row">
            <button className="primary" disabled={busy} onClick={send}>{busy ? 'Sending…' : 'Send rating'}</button>
            {editing && <button type="button" disabled={busy} onClick={() => setEditing(false)}>Cancel</button>}
          </div>
        </>
      )}
    </div>
  );
}
