import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, openPdf } from '../api';
import { useSession } from '../session';
import type {
  DiagnosisEntry, DiagnosisOption, DrugOption, Encounter, EncounterContent, Favourite, PatientSummary, RxItem,
  Template, Vitals,
} from '../types';
import { Typeahead, type Option } from '../components/Typeahead';
import { PatientHeader, SummaryPanel } from '../components/PatientPanels';
import { addDays, date, dateTime, todayIst } from '../format';

type SaveState = 'saved' | 'saving' | 'unsaved' | 'conflict' | 'error';

const FREQUENCIES = ['1-0-1', '1-1-1', '1-0-0', '0-0-1', '0-1-0', '1-1-1-1', 'SOS', 'HS', 'Once a week'];
const TIMINGS = ['', 'after food', 'before food', 'empty stomach', 'at bedtime', 'with food'];

const emptyContent = (): EncounterContent => ({ diagnoses: [], prescription: [], investigations: [] });

export function VisitPage() {
  const { visitId } = useParams();
  const navigate = useNavigate();
  const { session, has } = useSession();
  const [encounter, setEncounter] = useState<Encounter>();
  const [summary, setSummary] = useState<PatientSummary>();
  const [content, setContent] = useState<EncounterContent>(emptyContent());
  const [saveState, setSaveState] = useState<SaveState>('saved');
  const [error, setError] = useState<ApiError>();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [amending, setAmending] = useState(false);
  const [amendReason, setAmendReason] = useState('');

  // Refs drive autosave so typing never waits for the network
  const contentRef = useRef(content);
  const versionRef = useRef(0);
  const dirtyRef = useRef(false);
  const savingRef = useRef<Promise<void> | null>(null);
  const timerRef = useRef<number>();

  const isAuthor = !!encounter && encounter.doctor.staffId === session?.staffId;
  const editingDraft = encounter?.status === 'DRAFT' && (isAuthor || has('NURSE'));
  const editable = editingDraft || amending;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const e = await api.get<Encounter>(`/clinic/encounters/${visitId}`);
        const s = await api.get<PatientSummary>(`/clinic/patients/${e.patientId}/summary`);
        if (cancelled) return;
        setEncounter(e);
        setSummary(s);
        setContent(withDefaults(e.content));
        contentRef.current = withDefaults(e.content);
        versionRef.current = e.draftVersion;
        if (has('DOCTOR')) {
          api.get<Template[]>('/me/templates').then((t) => !cancelled && setTemplates(t)).catch(() => {});
        }
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err : undefined);
      }
    })();
    return () => { cancelled = true; };
  }, [visitId, has]);

  const save = useCallback(async () => {
    if (savingRef.current) {
      await savingRef.current;
    }
    if (!dirtyRef.current) return;
    dirtyRef.current = false;
    setSaveState('saving');
    const run = (async () => {
      try {
        const res = await api.put<Encounter>(`/clinic/encounters/${visitId}`,
          { baseVersion: versionRef.current, content: contentRef.current });
        versionRef.current = res.draftVersion;
        setEncounter(res);
        setSaveState(dirtyRef.current ? 'unsaved' : 'saved');
      } catch (err) {
        dirtyRef.current = true;
        setSaveState(err instanceof ApiError && err.code === 'DRAFT_VERSION_CONFLICT' ? 'conflict' : 'error');
      }
    })();
    savingRef.current = run;
    await run;
    savingRef.current = null;
  }, [visitId]);

  const flush = useCallback(async () => {
    window.clearTimeout(timerRef.current);
    await save();
  }, [save]);

  // Save on Ctrl+S, and before leaving the page
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        flush();
      }
    };
    const onLeave = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('beforeunload', onLeave);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('beforeunload', onLeave);
    };
  }, [flush]);

  function update(fn: (c: EncounterContent) => EncounterContent) {
    const next = fn(contentRef.current);
    contentRef.current = next;
    setContent(next);
    if (amending) return; // amendments are submitted in one go, not autosaved
    dirtyRef.current = true;
    setSaveState('unsaved');
    window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(save, 1000);
  }

  async function sign() {
    setError(undefined);
    await flush();
    if (dirtyRef.current) return;
    try {
      const res = await api.post<Encounter>(`/clinic/encounters/${visitId}/sign`);
      setEncounter(res);
      setContent(withDefaults(res.content));
      window.scrollTo({ top: 0 });
      // The summary is built from signed visits, so it changes now
      api.get<PatientSummary>(`/clinic/patients/${res.patientId}/summary`).then(setSummary).catch(() => {});
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
      // The Sign button is at the bottom; bring the list of problems into view
      requestAnimationFrame(() => document.querySelector('.alert.error')?.scrollIntoView({ block: 'center' }));
    }
  }

  async function submitAmendment() {
    setError(undefined);
    try {
      const res = await api.post<Encounter>(`/clinic/encounters/${visitId}/amend`, { content: contentRef.current, reason: amendReason });
      setEncounter(res);
      setContent(withDefaults(res.content));
      api.get<PatientSummary>(`/clinic/patients/${res.patientId}/summary`).then(setSummary).catch(() => {});
      setAmending(false);
      setAmendReason('');
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
    }
  }

  async function applyTemplate(templateId: string) {
    if (!templateId) return;
    setError(undefined);
    await flush();
    try {
      const res = await api.post<Encounter>(`/clinic/encounters/${visitId}/apply-template`,
        { baseVersion: versionRef.current, templateId });
      versionRef.current = res.draftVersion;
      setEncounter(res);
      const c = withDefaults(res.content);
      contentRef.current = c;
      setContent(c);
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
    }
  }

  async function repeatLastPrescription() {
    setError(undefined);
    try {
      const last = await api.get<{ items: RxItem[] }>(`/clinic/patients/${encounter!.patientId}/last-prescription`);
      update((c) => {
        const have = new Set(c.prescription.map(rxKey));
        return { ...c, prescription: [...c.prescription, ...last.items.filter((i) => !have.has(rxKey(i)))] };
      });
    } catch (err) {
      setError(err instanceof ApiError ? err : undefined);
    }
  }

  const loadDiagnoses = useCallback(async (q: string): Promise<Option<DiagnosisEntry>[]> => {
    const [favs, terms] = await Promise.all([
      api.get<Favourite<DiagnosisEntry>[]>(`/me/favourites?kind=DIAGNOSIS&q=${encodeURIComponent(q)}`).catch(() => []),
      api.get<DiagnosisOption[]>(`/terminology/diagnoses?q=${encodeURIComponent(q)}`),
    ]);
    const seen = new Set<string>();
    const out: Option<DiagnosisEntry>[] = [];
    for (const f of favs.slice(0, 5)) {
      seen.add(f.entry.code ?? f.entry.display);
      out.push({ key: 'f' + f.ref, label: f.entry.display, tag: `${f.entry.code ?? ''} ★`, value: { ...f.entry, kind: undefined } });
    }
    for (const t of terms) {
      if (seen.has(t.code)) continue;
      out.push({ key: 't' + t.code, label: t.display, tag: t.code, value: { codeSystem: t.codeSystem, code: t.code, display: t.display } });
    }
    return out;
  }, []);

  const loadDrugs = useCallback(async (q: string): Promise<Option<RxItem>[]> => {
    const [favs, drugs] = await Promise.all([
      api.get<Favourite<RxItem>[]>(`/me/favourites?kind=DRUG&q=${encodeURIComponent(q)}`).catch(() => []),
      api.get<DrugOption[]>(`/terminology/drugs?q=${encodeURIComponent(q)}`),
    ]);
    const seen = new Set<string>();
    const out: Option<RxItem>[] = [];
    for (const f of favs.slice(0, 5)) {
      seen.add(f.entry.drugDisplay.toLowerCase());
      out.push({ key: 'f' + f.ref, label: f.entry.drugDisplay,
                 tag: [f.entry.frequency, f.entry.longTerm ? 'long-term' : f.entry.durationDays ? `${f.entry.durationDays}d` : '', '★'].filter(Boolean).join(' · '),
                 value: f.entry });
    }
    for (const d of drugs) {
      if (seen.has(d.display.toLowerCase())) continue;
      out.push({ key: 'd' + d.id, label: d.display, tag: d.form,
                 value: { drugId: d.id, drugDisplay: d.display, genericName: d.genericName, dose: '', frequency: '', timing: 'after food' } });
    }
    return out;
  }, []);

  if (!encounter || !summary) {
    return error ? <div className="alert error">{error.message}</div> : <p className="muted">Loading…</p>;
  }

  const signed = encounter.status === 'SIGNED';
  const fieldErrors = error?.problem.fields as Record<string, string> | undefined;
  const set = (k: keyof EncounterContent) => (e: React.ChangeEvent<HTMLTextAreaElement>) =>
    update((c) => ({ ...c, [k]: e.target.value || undefined }));
  const setVital = (k: keyof Vitals) => (e: React.ChangeEvent<HTMLInputElement>) =>
    update((c) => ({ ...c, vitals: { ...c.vitals, [k]: e.target.value === '' ? undefined : Number(e.target.value) } }));

  return (
    <>
      <div className="between">
        <button className="link" onClick={async () => { await flush(); navigate(`/patients/${encounter.patientId}`); }}>
          ← {summary.patient.name}
        </button>
        <div className="row">
          <span className="chip status">{signed ? `Signed${encounter.currentVersion > 1 ? ` · v${encounter.currentVersion}` : ''}` : encounter.status === 'DRAFT' ? 'Draft' : 'Cancelled'}</span>
          <span className="muted small">Dr. {encounter.doctor.name.replace(/^Dr\.?\s*/i, '')} · {dateTime(encounter.startedAt)}</span>
        </div>
      </div>

      <PatientHeader patient={summary.patient} allergies={summary.allergies} />

      {error && (
        <div className="alert error">
          {error.message}
          {fieldErrors && (
            <ul>{Object.entries(fieldErrors).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => (
              <li key={k}><b>{fieldLabel(k)}</b>: {v === 'must not be blank' ? 'is required' : v}</li>
            ))}</ul>
          )}
        </div>
      )}
      {saveState === 'conflict' && (
        <div className="alert warn">This visit was changed in another window. <button className="link" onClick={() => window.location.reload()}>Reload</button> to continue.</div>
      )}

      <div className="visit-layout">
        <aside className="sticky" style={{ display: 'grid', gap: 16 }}>
          <SummaryPanel summary={summary} />
          {summary.lastVisit && summary.lastVisit.encounterId !== encounter.id && (
            <div className="card tight">
              <h3>Last visit</h3>
              <div>{date(summary.lastVisit.date)} · {summary.lastVisit.doctorName}</div>
              <div className="muted small">{summary.lastVisit.diagnoses}</div>
            </div>
          )}
        </aside>

        {signed && !amending ? (
          <SignedView encounter={encounter} canAmend={isAuthor} onAmend={() => { setAmending(true); contentRef.current = content; }} />
        ) : (
          <section className="card" aria-label="Visit">
            <div className="between">
              <h2>{amending ? `Amend visit (will become version ${encounter.currentVersion + 1})` : 'Visit notes'}</h2>
              {!amending && editingDraft && (
                <div className="row">
                  {templates.length > 0 && (
                    <select id="template" aria-label="Apply template" value="" onChange={(e) => applyTemplate(e.target.value)} style={{ width: 'auto' }}>
                      <option value="">Apply template…</option>
                      {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                    </select>
                  )}
                  <span className={`save-state ${saveState === 'error' || saveState === 'conflict' ? 'bad' : ''}`}>
                    {{ saved: 'All changes saved', saving: 'Saving…', unsaved: 'Unsaved changes', conflict: 'Not saved', error: 'Not saved, retrying on next change' }[saveState]}
                  </span>
                </div>
              )}
            </div>

            <fieldset disabled={!editable} style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 14 }}>
              <label className="field">Complaints
                <textarea id="complaints" rows={2} value={content.chiefComplaints ?? ''} onChange={set('chiefComplaints')} autoFocus />
              </label>

              <div>
                <h3>Vitals</h3>
                <div className="fields" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(110px, 1fr))' }}>
                  <VitalInput id="bpSys" label="BP systolic" value={content.vitals?.bpSystolic} onChange={setVital('bpSystolic')} />
                  <VitalInput id="bpDia" label="BP diastolic" value={content.vitals?.bpDiastolic} onChange={setVital('bpDiastolic')} />
                  <VitalInput id="pulse" label="Pulse /min" value={content.vitals?.pulse} onChange={setVital('pulse')} />
                  <VitalInput id="temp" label="Temp °F" step="0.1" value={content.vitals?.temperatureF} onChange={setVital('temperatureF')} />
                  <VitalInput id="spo2" label="SpO2 %" value={content.vitals?.spo2} onChange={setVital('spo2')} />
                  <VitalInput id="weight" label="Weight kg" step="0.1" value={content.vitals?.weightKg} onChange={setVital('weightKg')} />
                  <VitalInput id="rbs" label="RBS mg/dL" value={content.vitals?.bloodGlucoseMgDl} onChange={setVital('bloodGlucoseMgDl')} />
                </div>
              </div>

              <label className="field">History
                <textarea id="history" rows={2} value={content.historyOfPresentIllness ?? ''} onChange={set('historyOfPresentIllness')} />
              </label>
              <label className="field">Examination
                <textarea id="exam" rows={2} value={content.examination ?? ''} onChange={set('examination')} />
              </label>

              <div style={{ display: 'grid', gap: 8 }}>
                <h3>Diagnosis</h3>
                {content.diagnoses.map((d, i) => (
                  <div key={i} className="dx-line">
                    <span className="dx-wide">{d.display} {d.code && <span className="muted mono small">({d.code})</span>}</span>
                    <select aria-label="Diagnosis type" value={d.kind ?? 'PRIMARY'} className="dx-wide"
                            onChange={(e) => update((c) => ({ ...c, diagnoses: c.diagnoses.map((x, j) => j === i ? { ...x, kind: e.target.value as DiagnosisEntry['kind'] } : x) }))}>
                      <option value="PRIMARY">Primary</option>
                      <option value="SECONDARY">Secondary</option>
                      <option value="PROVISIONAL">Provisional</option>
                    </select>
                    <label className="check dx-wide"><input type="checkbox" checked={!!d.chronic}
                      onChange={(e) => update((c) => ({ ...c, diagnoses: c.diagnoses.map((x, j) => j === i ? { ...x, chronic: e.target.checked } : x) }))} /> Chronic</label>
                    <button type="button" className="link danger" aria-label={`Remove ${d.display}`}
                            onClick={() => update((c) => ({ ...c, diagnoses: c.diagnoses.filter((_, j) => j !== i) }))}>✕</button>
                  </div>
                ))}
                {editable && (
                  <Typeahead id="dxSearch" placeholder="Search diagnosis (e.g. fever, J02, sugar) and press Enter"
                    load={loadDiagnoses}
                    onSelect={(d) => update((c) => ({ ...c, diagnoses: [...c.diagnoses, { ...d, kind: c.diagnoses.length ? 'SECONDARY' : 'PRIMARY' }] }))}
                    onFreeText={(t) => update((c) => ({ ...c, diagnoses: [...c.diagnoses, { display: t, kind: c.diagnoses.length ? 'SECONDARY' : 'PRIMARY' }] }))} />
                )}
              </div>

              <div style={{ display: 'grid', gap: 8 }}>
                <div className="section-title">
                  <h3>Rx</h3>
                  {editingDraft && !amending && <button type="button" className="link" onClick={repeatLastPrescription}>Repeat last prescription</button>}
                </div>
                {(encounter.warnings ?? []).map((w, i) => (
                  <div key={i} className={`alert ${w.type === 'ALLERGY' ? 'error' : 'warn'}`} role="alert">{w.message}</div>
                ))}
                {content.prescription.map((r, i) => (
                  <RxRow key={i} n={i + 1} item={r}
                    onChange={(next) => update((c) => ({ ...c, prescription: c.prescription.map((x, j) => j === i ? next : x) }))}
                    onRemove={() => update((c) => ({ ...c, prescription: c.prescription.filter((_, j) => j !== i) }))} />
                ))}
                {editable && (
                  <Typeahead id="drugSearch" placeholder="Add medicine (e.g. parac, metf) and press Enter"
                    load={loadDrugs}
                    onSelect={(r) => update((c) => ({ ...c, prescription: [...c.prescription, r] }))}
                    onFreeText={(t) => update((c) => ({ ...c, prescription: [...c.prescription, { drugDisplay: t, frequency: '', timing: 'after food' }] }))} />
                )}
                <datalist id="frequencies">{FREQUENCIES.map((f) => <option key={f} value={f} />)}</datalist>
              </div>

              <InvestigationsEditor items={content.investigations} editable={editable}
                onChange={(items) => update((c) => ({ ...c, investigations: items }))} />

              <label className="field">Advice
                <textarea id="advice" rows={2} value={content.advice ?? ''} onChange={set('advice')} />
              </label>

              <div style={{ display: 'grid', gap: 6 }}>
                <h3>Follow-up</h3>
                <div className="row">
                  {[3, 7, 15, 30].map((d) => (
                    <button type="button" key={d} onClick={() => update((c) => ({ ...c, followUp: { ...c.followUp, dueDate: addDays(todayIst(), d) } }))}>
                      {d === 30 ? '1 month' : `${d} days`}
                    </button>
                  ))}
                  <input id="followUpDate" type="date" aria-label="Follow-up date" style={{ width: 'auto' }} value={content.followUp?.dueDate ?? ''}
                         onChange={(e) => update((c) => ({ ...c, followUp: e.target.value ? { ...c.followUp, dueDate: e.target.value } : undefined }))} />
                  {content.followUp && <button type="button" className="link" onClick={() => update((c) => ({ ...c, followUp: undefined }))}>Clear</button>}
                </div>
              </div>

              <label className="field">Private note (only this clinic sees it; never printed or shared)
                <textarea id="privateNote" rows={2} value={content.privateNote ?? ''} onChange={set('privateNote')} />
              </label>
            </fieldset>

            {amending ? (
              <div className="row end">
                <input id="amendReason" placeholder="Reason for the change (required)" value={amendReason}
                       onChange={(e) => setAmendReason(e.target.value)} style={{ maxWidth: 360 }} />
                <button type="button" onClick={() => { setAmending(false); setContent(withDefaults(encounter.content)); }}>Cancel</button>
                <button type="button" className="primary" disabled={amendReason.trim().length < 5} onClick={submitAmendment}>Save amendment</button>
              </div>
            ) : editingDraft && isAuthor && (
              <div className="row end">
                <span className="muted small">Signing locks the visit. Corrections after that are recorded as amendments.</span>
                <button type="button" className="primary" onClick={sign}>Sign visit</button>
              </div>
            )}
          </section>
        )}
      </div>
    </>
  );
}

function SignedView({ encounter, canAmend, onAmend }: { encounter: Encounter; canAmend: boolean; onAmend: () => void }) {
  const c = encounter.content;
  const latest = encounter.versions[encounter.versions.length - 1];
  return (
    <section className="card">
      <div className="between">
        <h2>Visit</h2>
        <div className="row">
          <button className="primary" onClick={() => openPdf(`/clinic/encounters/${encounter.id}/prescription.pdf?paper=A5`)}>Print prescription (A5)</button>
          <button onClick={() => openPdf(`/clinic/encounters/${encounter.id}/prescription.pdf?paper=A4`)}>A4</button>
          {canAmend && <button onClick={onAmend}>Amend</button>}
        </div>
      </div>
      <p className="muted small">
        Signed {dateTime(encounter.signedAt)}
        {latest && latest.version > 1 && <> · amended {dateTime(latest.signedAt)} ({latest.amendmentReason})</>}
      </p>
      <dl className="signed-view" style={{ margin: 0 }}>
        {c.chiefComplaints && <><dt>Complaints</dt><dd>{c.chiefComplaints}</dd></>}
        {c.examination && <><dt>Examination</dt><dd>{c.examination}</dd></>}
        <dt>Diagnosis</dt>
        <dd>{c.diagnoses.map((d) => `${d.display}${d.code ? ` (${d.code})` : ''}`).join('; ')}</dd>
        {c.prescription.length > 0 && (
          <>
            <dt>Rx</dt>
            <dd>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>#</th><th>Medicine</th><th>Dose</th><th>Frequency</th><th>When</th><th>Duration</th></tr></thead>
                  <tbody>
                    {c.prescription.map((r, i) => (
                      <tr key={i}>
                        <td>{i + 1}</td>
                        <td><b>{r.drugDisplay}</b>{r.instructions && <div className="small muted">{r.instructions}</div>}</td>
                        <td>{r.dose}</td><td>{r.frequency}</td><td>{r.timing}</td>
                        <td>{r.longTerm ? 'Continue' : `${r.durationDays} days`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </dd>
          </>
        )}
        {c.investigations.length > 0 && <><dt>Investigations</dt><dd>{c.investigations.map((i) => i.display).join(', ')}</dd></>}
        {c.advice && <><dt>Advice</dt><dd>{c.advice}</dd></>}
        {c.followUp && <><dt>Follow-up</dt><dd>{date(c.followUp.dueDate)}{c.followUp.note ? ` · ${c.followUp.note}` : ''}</dd></>}
        {c.privateNote && <><dt>Private note</dt><dd className="muted">{c.privateNote}</dd></>}
      </dl>
    </section>
  );
}

function RxRow({ n, item, onChange, onRemove }: { n: number; item: RxItem; onChange: (r: RxItem) => void; onRemove: () => void }) {
  const set = (k: keyof RxItem) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    onChange({ ...item, [k]: e.target.value || undefined });
  return (
    <div className="rx-line">
      <span className="num">{n}</span>
      <input aria-label={`Medicine ${n}`} className="rx-wide" value={item.drugDisplay} onChange={(e) => onChange({ ...item, drugDisplay: e.target.value })} />
      <input aria-label={`Dose ${n}`} className="rx-wide" placeholder="Dose" value={item.dose ?? ''} onChange={set('dose')} />
      <input aria-label={`Frequency ${n}`} className="rx-wide" placeholder="1-0-1" list="frequencies" value={item.frequency ?? ''}
             onChange={(e) => onChange({ ...item, frequency: e.target.value })} />
      <select aria-label={`When ${n}`} className="rx-wide" value={item.timing ?? ''} onChange={set('timing')}>
        {TIMINGS.map((t) => <option key={t} value={t}>{t || 'When'}</option>)}
      </select>
      <div className="row rx-wide" style={{ flexWrap: 'nowrap' }}>
        {item.longTerm ? <span className="small">Long-term</span> : (
          <input aria-label={`Days ${n}`} type="number" min={1} max={365} placeholder="Days" value={item.durationDays ?? ''}
                 onChange={(e) => onChange({ ...item, durationDays: e.target.value ? Number(e.target.value) : undefined })} />
        )}
        <label className="check" title="Long-term medicine"><input type="checkbox" checked={!!item.longTerm}
          onChange={(e) => onChange({ ...item, longTerm: e.target.checked, durationDays: e.target.checked ? undefined : item.durationDays })} />LT</label>
      </div>
      <button type="button" className="link danger" aria-label={`Remove medicine ${n}`} onClick={onRemove}>✕</button>
      <input aria-label={`Instructions ${n}`} className="rx-extra small" placeholder="Instructions (optional)" value={item.instructions ?? ''} onChange={set('instructions')} />
    </div>
  );
}

function InvestigationsEditor({ items, editable, onChange }:
  { items: EncounterContent['investigations']; editable: boolean; onChange: (i: EncounterContent['investigations']) => void }) {
  const [text, setText] = useState('');
  function add() {
    const t = text.trim();
    if (!t || items.some((i) => i.display.toLowerCase() === t.toLowerCase())) return;
    onChange([...items, { display: t }]);
    setText('');
  }
  return (
    <div style={{ display: 'grid', gap: 6 }}>
      <h3>Investigations</h3>
      <div className="row">
        {items.map((i, n) => (
          <span key={n} className="chip">{i.display}
            {editable && <button type="button" className="link danger" aria-label={`Remove ${i.display}`}
                                 onClick={() => onChange(items.filter((_, j) => j !== n))}>✕</button>}
          </span>
        ))}
      </div>
      {editable && (
        <input id="testInput" placeholder="Add a test (e.g. CBC, HbA1c) and press Enter" value={text}
               onChange={(e) => setText(e.target.value)}
               onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
      )}
    </div>
  );
}

function VitalInput({ id, label, value, onChange, step }:
  { id: string; label: string; value?: number; onChange: (e: React.ChangeEvent<HTMLInputElement>) => void; step?: string }) {
  return (
    <label className="field">{label}
      <input id={id} type="number" inputMode="decimal" step={step ?? '1'} value={value ?? ''} onChange={onChange} />
    </label>
  );
}

function withDefaults(c: Partial<EncounterContent> | undefined): EncounterContent {
  return { ...c, diagnoses: c?.diagnoses ?? [], prescription: c?.prescription ?? [], investigations: c?.investigations ?? [] };
}

function rxKey(r: RxItem) {
  return (r.genericName || r.drugDisplay).toLowerCase();
}

function fieldLabel(path: string) {
  const m = path.match(/^prescription\[(\d+)\]\.(\w+)$/);
  if (m) return `Medicine ${Number(m[1]) + 1} ${m[2] === 'durationDays' ? 'duration' : m[2]}`;
  return ({ diagnoses: 'Diagnosis', 'followUp.dueDate': 'Follow-up date' } as Record<string, string>)[path] ?? path;
}
