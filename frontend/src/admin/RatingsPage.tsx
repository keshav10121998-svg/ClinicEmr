import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi, ApiError, type AdminRatings, type PublicRating } from './api';
import { date } from '../clinic/format';
import { Stars } from '../shared/Stars';

/**
 * How clinics rate ClinicEMR (to promote it) and how patients rate each clinic. What clinics allowed to be shown
 * publicly is also served, without a sign-in, at /api/v1/public/app-rating for a website.
 */
export function RatingsPage() {
  const [data, setData] = useState<AdminRatings>();
  const [pub, setPub] = useState<PublicRating>();
  const [error, setError] = useState<string>();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    adminApi.get<AdminRatings>('/admin/ratings').then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load ratings.'));
    // The public endpoint needs no sign-in: this is exactly what a website would show
    fetch('/api/v1/public/app-rating').then((r) => r.json()).then(setPub).catch(() => {});
  }, []);

  const url = `${window.location.origin}/api/v1/public/app-rating`;

  return (
    <>
      <div className="between">
        <h1>Ratings</h1>
        <Link className="admin-link-btn" to="/admin">Doctors</Link>
      </div>
      {error && <div className="alert error">{error}</div>}

      <div className="stat-tiles admin-rating-tiles">
        <div className="stat-tile main">
          <span className="stat-label">ClinicEMR, by clinics</span>
          <span className="stat-number">{data && data.app.count > 0 ? data.app.average.toFixed(1) : '–'}</span>
          <span className="stat-sub">{data ? `${data.app.count} ${data.app.count === 1 ? 'clinic' : 'clinics'} rated it` : ' '}</span>
        </div>
        <div className="stat-tile">
          <span className="stat-label">Clinics, by patients</span>
          <span className="stat-number">{data && data.patients.count > 0 ? data.patients.average.toFixed(1) : '–'}</span>
          <span className="stat-sub">{data ? `${data.patients.count} visit ratings` : ' '}</span>
        </div>
      </div>

      <div className="card">
        <div className="between">
          <h2>What clinics say about ClinicEMR</h2>
          {data && <span className="muted small">{data.appFeedback.length} shown</span>}
        </div>
        {!data && !error && <p className="muted">Loading…</p>}
        {data?.appFeedback.length === 0 && <p className="muted">No clinic has rated ClinicEMR yet. Clinics see the request on their Overview.</p>}
        <div className="patient-list">
          {data?.appFeedback.map((f) => (
            <div key={f.clinicId} className="due-row">
              <b className="due-name">{f.clinicName}{f.city ? <span className="muted small"> · {f.city}</span> : null}</b>
              <Stars value={f.stars} size={18} />
              <span className="small">{f.comment ?? <span className="muted">No comment</span>}</span>
              <span className="due-actions">
                <span className="muted small">{date(f.ratedAt)}</span>
                {f.allowPublic ? <span className="chip ok">OK to show publicly</span> : <span className="chip">Private</span>}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <h2>For your website</h2>
        <p className="muted small" style={{ margin: 0 }}>
          This address needs no sign-in. It gives the average rating over every clinic that rated, and the words only of
          clinics that agreed to be shown, with their clinic name and city.
        </p>
        <div className="row">
          <code className="admin-url">{url}</code>
          <button type="button" className="small" onClick={() => { navigator.clipboard?.writeText(url).then(() => setCopied(true)).catch(() => {}); }}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
        {pub && (
          <>
            <div className="rating-head">
              <span className="rating-number">{pub.count > 0 ? pub.average.toFixed(1) : '–'}</span>
              <span><Stars value={pub.average} size={22} /><br /><span className="muted small">from {pub.count} {pub.count === 1 ? 'clinic' : 'clinics'}</span></span>
            </div>
            {pub.testimonials.length === 0 && <p className="muted small">No clinic has allowed its words to be shown yet.</p>}
            <div className="rating-words">
              {pub.testimonials.map((t, i) => (
                <blockquote key={i}>
                  <Stars value={t.stars} size={14} />
                  <p>{t.comment}</p>
                  <span className="muted small">{t.clinicName}{t.city ? `, ${t.city}` : ''}</span>
                </blockquote>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="card">
        <h2>Clinics, by what patients say</h2>
        {data?.clinics.length === 0 && <p className="muted">No patient has rated a visit yet.</p>}
        <div className="patient-list">
          {data?.clinics.map((c) => (
            <div key={c.clinicId} className="between due-row">
              <b className="due-name">{c.clinicName}</b>
              <span><Stars value={c.average} size={16} /> <b>{c.average.toFixed(1)}</b> <span className="muted small">({c.count})</span></span>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
