import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useSession } from '../session';
import type { AllergyList, Patient, PatientSummary } from '../types';
import { ageSex, date } from '../format';

export function PatientHeader({ patient, allergies }: { patient: Patient; allergies?: AllergyList }) {
  return (
    <div className="card tight">
      <div className="patient-head">
        <span className="name">{patient.name}</span>
        <span>{ageSex(patient)}</span>
        <span className="mono muted">{patient.mrn}</span>
        {patient.phone && <span className="mono muted">{patient.phone}</span>}
        {patient.bloodGroup && <span className="chip">Blood {patient.bloodGroup}</span>}
      </div>
      {allergies && <AllergyBand allergies={allergies} />}
    </div>
  );
}

/** Allergies are always shown first and in red, or the fact that nobody has asked yet. */
export function AllergyBand({ allergies }: { allergies: AllergyList }) {
  return (
    <div className="allergy-band" aria-label="Allergies">
      <b className="small">Allergies:</b>
      {allergies.allergies.length > 0
        ? allergies.allergies.map((a) => (
            <span key={a.id} className="chip allergy" title={a.reaction}>
              {a.substance}{a.severity ? ` · ${a.severity.toLowerCase()}` : ''}
            </span>
          ))
        : allergies.noKnownAllergies
          ? <span className="chip ok">No known allergies</span>
          : <span className="chip">Not asked yet</span>}
    </div>
  );
}

export function AllergyEditor({ patientId, allergies, onChange }:
  { patientId: string; allergies: AllergyList; onChange: (a: AllergyList) => void }) {
  const { has } = useSession();
  const [substance, setSubstance] = useState('');
  const [reaction, setReaction] = useState('');
  const [severity, setSeverity] = useState('');
  const [error, setError] = useState<string>();

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(undefined);
    try {
      onChange(await api.post<AllergyList>(`/clinic/patients/${patientId}/allergies`, {
        substance, reaction: reaction || undefined, severity: severity || undefined,
      }));
      setSubstance('');
      setReaction('');
      setSeverity('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not add allergy.');
    }
  }

  async function noneKnown() {
    setError(undefined);
    try {
      onChange(await api.post<AllergyList>(`/clinic/patients/${patientId}/allergies/none-known`));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save.');
    }
  }

  async function inactivate(id: string) {
    const reason = window.prompt?.('Why is this allergy no longer valid?');
    if (!reason) return;
    try {
      onChange(await api.post<AllergyList>(`/clinic/patients/${patientId}/allergies/${id}/status`,
        { status: 'ENTERED_IN_ERROR', reason }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not update.');
    }
  }

  return (
    <div className="card">
      <div className="section-title">
        <h3>Allergies</h3>
        {allergies.allergies.length === 0 && !allergies.noKnownAllergies && (
          <button className="link" onClick={noneKnown}>Mark no known allergies</button>
        )}
      </div>
      {error && <div className="alert error">{error}</div>}
      {allergies.allergies.map((a) => (
        <div key={a.id} className="between">
          <span>
            <span className="chip allergy">{a.substance}</span>{' '}
            <span className="small muted">{[a.severity?.toLowerCase(), a.reaction].filter(Boolean).join(' · ')}</span>
          </span>
          {has('DOCTOR') && <button className="link small" onClick={() => inactivate(a.id)}>Remove</button>}
        </div>
      ))}
      {allergies.allergies.length === 0 && (
        <p className="muted small">{allergies.noKnownAllergies ? `No known allergies (confirmed ${date(allergies.reviewedAt)})` : 'Not asked yet.'}</p>
      )}
      <form className="fields" onSubmit={add}>
        <label className="field">Substance
          <input id="allergySubstance" required value={substance} onChange={(e) => setSubstance(e.target.value)} placeholder="Penicillin" />
        </label>
        <label className="field">Reaction
          <input id="allergyReaction" value={reaction} onChange={(e) => setReaction(e.target.value)} placeholder="Rash" />
        </label>
        <label className="field">Severity
          <select id="allergySeverity" value={severity} onChange={(e) => setSeverity(e.target.value)}>
            <option value="">Unknown</option>
            <option value="MILD">Mild</option>
            <option value="MODERATE">Moderate</option>
            <option value="SEVERE">Severe</option>
          </select>
        </label>
        <div className="row" style={{ alignSelf: 'end' }}><button>Add allergy</button></div>
      </form>
    </div>
  );
}

export function SummaryPanel({ summary }: { summary: PatientSummary }) {
  const vitals = summary.latestVitals;
  const v = (code: string) => vitals.find((x) => x.code === code);
  const sys = v('8480-6');
  const dia = v('8462-4');
  return (
    <div className="card">
      <h3>Active problems</h3>
      {summary.activeProblems.length === 0 ? <p className="muted small">None recorded.</p> : (
        <div className="row">{summary.activeProblems.map((p) => (
          <span key={p.problemKey} className="chip" title={`Since ${date(p.since)}`}>{p.display}{p.code ? ` (${p.code})` : ''}</span>
        ))}</div>
      )}
      <h3>Current medicines</h3>
      {summary.currentMedicines.length === 0 ? <p className="muted small">None.</p> : (
        <ul style={{ margin: 0, paddingLeft: 18 }}>
          {summary.currentMedicines.map((m) => (
            <li key={m.medicationKey}>
              <b>{m.drugDisplay}</b> <span className="muted small">{[m.dose, m.frequency, m.timing].filter(Boolean).join(' · ')}
              {m.longTerm ? ' · long-term' : m.endsOn ? ` · until ${date(m.endsOn)}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
      <h3>Latest vitals</h3>
      {vitals.length === 0 ? <p className="muted small">None recorded.</p> : (
        <div className="row small">
          {sys && dia && <span className="chip mono">BP {sys.value}/{dia.value}</span>}
          {vitals.filter((x) => x.code !== '8480-6' && x.code !== '8462-4').map((x) => (
            <span key={x.code} className="chip mono" title={x.display}>{shortVital(x.code)} {x.value} {unit(x.unit)}</span>
          ))}
          <span className="muted">{date(vitals[0].observedAt)}</span>
        </div>
      )}
    </div>
  );
}

function shortVital(code: string) {
  return ({ '8867-4': 'Pulse', '8310-5': 'Temp', '59408-5': 'SpO2', '29463-7': 'Wt', '8302-2': 'Ht', '9279-1': 'RR', '2339-0': 'RBS' } as Record<string, string>)[code] ?? code;
}

function unit(u: string) {
  return ({ '[degF]': '°F', '/min': '/min', 'mm[Hg]': 'mmHg' } as Record<string, string>)[u] ?? u;
}
