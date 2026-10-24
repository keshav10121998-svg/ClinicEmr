import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, openPdf } from '../api';
import { useSession } from '../session';
import type {
  DiagnosisEntry, DiagnosisOption, DrugOption, Encounter, EncounterContent, Favourite, PatientSummary, RxItem,
  Template, Vitals,
} from '../types';
import { LastVisitCard, PatientHeader, SummaryPanel } from '../components/PatientPanels';
import { AttachedFiles } from '../components/AttachedFiles';
import { useIsPhone } from '../useIsPhone';
import { appendSpoken, MicButton, useDictation, VoiceTextArea } from '../components/Dictation';
import { addDays, date, dateTime, todayIst } from '../format';

type SaveState = 'saved' | 'saving' | 'unsaved' | 'conflict' | 'error';

const FREQUENCIES = ['1-0-1', '1-1-1', '1-0-0', '0-0-1', '0-1-0', '1-1-1-1', 'SOS', 'At bedtime', 'Once a week'];
const TIMINGS = ['', 'after food', 'before food', 'empty stomach', 'at bedtime'];
const DAYS = [1, 2, 3, 5, 7, 10, 14, 21, 30, 60, 90];

const emptyContent = (): EncounterContent => ({ diagnoses: [], prescription: [], investigations: [] });

/**
 * One visit on one screen. The short form holds what most visits need; everything is optional. Rarely used
 * sections sit under "More details". Drafts save themselves about a second after typing stops.
 */
export function VisitPage() {
  const { visitId } = useParams();
  const navigate = useNavigate();
  const { session, has } = useSession();
  const phone = useIsPhone();
  const [encounter, setEncounter] = useState<Encounter>();
  const [summary, setSummary] = useState<PatientSummary>();
  const [content, setContent] = useState<EncounterContent>(emptyContent());
  const [saveState, setSaveState] = useState<SaveState>('saved');
  const [error, setError] = useState<ApiError>();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [amending, setAmending] = useState(false);
  const [amendReason, setAmendReason] = useState('');
  const [diagnosisOptions, setDiagnosisOptions] = useState<DiagnosisOption[]>([]);
  const [drugOptions, setDrugOptions] = useState<RxItem[]>([]);

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

  // Suggestions for the diagnosis and medicine boxes: the doctor's own favourites first, then the common list
  useEffect(() => {
    Promise.all([
      api.get<Favourite<DiagnosisEntry>[]>('/me/favourites?kind=DIAGNOSIS').catch(() => []),
      api.get<DiagnosisOption[]>('/terminology/diagnoses/all').catch(() => []),
    ]).then(([favs, all]) => {
      const seen = new Set<string>();
      const list: DiagnosisOption[] = [];
      for (const f of favs) {
        if (!seen.has(f.entry.display)) { seen.add(f.entry.display); list.push({ codeSystem: f.entry.codeSystem ?? 'ICD10', code: f.entry.code ?? '', display: f.entry.display }); }
      }
      for (const d of all) if (!seen.has(d.display)) { seen.add(d.display); list.push(d); }
      setDiagnosisOptions(list);
    });
    Promise.all([
      api.get<Favourite<RxItem>[]>('/me/favourites?kind=DRUG').catch(() => []),
      api.get<DrugOption[]>('/terminology/drugs/all').catch(() => []),
    ]).then(([favs, all]) => {
      const seen = new Set<string>();
      const list: RxItem[] = [];
      for (const f of favs) if (!seen.has(f.entry.drugDisplay)) { seen.add(f.entry.drugDisplay); list.push(f.entry); }
      for (const d of all) {
        if (!seen.has(d.display)) { seen.add(d.display); list.push({ drugId: d.id, drugDisplay: d.display, genericName: d.genericName }); }
      }
      setDrugOptions(list);
    });
  }, []);

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

  // Save on Ctrl+S, and warn before leaving with unsaved changes
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

  if (!encounter || !summary) {
    return error ? <div className="alert error">{error.message}</div> : <p className="muted">Loading…</p>;
  }

  const signed = encounter.status === 'SIGNED';
  const fieldErrors = error?.problem.fields as Record<string, string> | undefined;
  const set = (k: keyof EncounterContent) => (value: string) => update((c) => ({ ...c, [k]: value || undefined }));
  const setVital = (k: keyof Vitals) => (e: React.ChangeEvent<HTMLInputElement>) =>
    update((c) => ({ ...c, vitals: { ...c.vitals, [k]: e.target.value === '' ? undefined : Number(e.target.value) } }));

  const lastVisitCard = summary.lastVisit && summary.lastVisit.encounterId !== encounter.id && (
    <LastVisitCard lastVisit={summary.lastVisit}
                   onOpen={async () => { await flush(); navigate(`/visits/${summary.lastVisit!.encounterId}`); }} />
  );
  const saveLabel = { saved: 'Saved', saving: 'Saving…', unsaved: 'Saving soon…', conflict: 'Not saved', error: 'Not saved, will retry' }[saveState];

  return (
    <>
      <div className="between">
        <button className="link" onClick={async () => { await flush(); navigate(`/patients/${encounter.patientId}`); }}>
          ← {summary.patient.name}
        </button>
        <div className="row">
          <span className="chip status">{signed ? `Signed${encounter.currentVersion > 1 ? ` · v${encounter.currentVersion}` : ''}` : encounter.status === 'DRAFT' ? 'Draft' : 'Cancelled'}</span>
          <span className="muted small">{dateTime(encounter.startedAt)}</span>
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
        {phone ? (
          <details className="peek">
            <summary>Last visit and health summary</summary>
            {lastVisitCard}
            <SummaryPanel summary={summary} />
          </details>
        ) : (
          <aside className="sticky" style={{ display: 'grid', gap: 16 }}>
            {lastVisitCard}
            <SummaryPanel summary={summary} />
          </aside>
        )}

        {signed && !amending ? (
          <SignedView encounter={encounter} canAmend={isAuthor} onAmend={() => { setAmending(true); contentRef.current = content; }} />
        ) : (
          <section className="card" aria-label="Visit">
            <div className="between">
              <h2>{amending ? `Correct this visit (becomes version ${encounter.currentVersion + 1})` : 'Visit'}</h2>
              {!amending && editingDraft && (
                <span className={`save-state ${saveState === 'error' || saveState === 'conflict' ? 'bad' : ''}`}>{saveLabel}</span>
              )}
            </div>

            <fieldset disabled={!editable} style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 14 }}>
              <VoiceTextArea id="complaints" label="Complaints" value={content.chiefComplaints} onChange={set('chiefComplaints')}
                             placeholder="e.g. Fever 3 days, cough" autoFocus />

              <DiagnosisField content={content} options={diagnosisOptions} onChange={(diagnoses) => update((c) => ({ ...c, diagnoses }))} />

              <VoiceTextArea id="treatment" label="Treatment given" value={content.treatmentGiven} onChange={set('treatmentGiven')}
                             placeholder="e.g. Injection, dressing, nebulisation" />

              <div style={{ display: 'grid', gap: 8 }}>
                <div className="section-title">
                  <h3>Medicines</h3>
                  {editingDraft && !amending && summary.lastVisit && (
                    <button type="button" className="link small" onClick={repeatLastPrescription}>Same as last time</button>
                  )}
                </div>
                {(encounter.warnings ?? []).map((w, i) => (
                  <div key={i} className={`alert ${w.type === 'ALLERGY' ? 'error' : 'warn'}`} role="alert">{w.message}</div>
                ))}
                {content.prescription.map((r, i) => (
                  <MedicineRow key={i} n={i + 1} item={r} options={drugOptions}
                    onChange={(next) => update((c) => ({ ...c, prescription: c.prescription.map((x, j) => (j === i ? next : x)) }))}
                    onRemove={() => update((c) => ({ ...c, prescription: c.prescription.filter((_, j) => j !== i) }))} />
                ))}
                {editable && (
                  <button type="button" onClick={() => {
                    update((c) => ({ ...c, prescription: [...c.prescription, { drugDisplay: '', timing: 'after food' }] }));
                    requestAnimationFrame(() => (document.querySelectorAll<HTMLInputElement>('.med-name input')).forEach((el, i, all) => i === all.length - 1 && el.focus()));
                  }}>+ Add medicine</button>
                )}
                <datalist id="drug-options">{drugOptions.map((d) => <option key={d.drugDisplay} value={d.drugDisplay} />)}</datalist>
                <datalist id="frequencies">{FREQUENCIES.map((f) => <option key={f} value={f} />)}</datalist>
              </div>

              <VoiceTextArea id="advice" label="Advice for the patient" value={content.advice} onChange={set('advice')}
                             placeholder="e.g. Rest, plenty of fluids" />

              <div style={{ display: 'grid', gap: 6 }}>
                <h3>Next visit</h3>
                <div className="row">
                  {[3, 7, 15, 30].map((d) => (
                    <button type="button" key={d} aria-pressed={content.followUp?.dueDate === addDays(todayIst(), d)}
                            onClick={() => update((c) => ({ ...c, followUp: { ...c.followUp, dueDate: addDays(todayIst(), d) } }))}>
                      {d === 30 ? '1 month' : `${d} days`}
                    </button>
                  ))}
                  <input id="followUpDate" type="date" aria-label="Next visit date" style={{ width: 'auto' }} value={content.followUp?.dueDate ?? ''}
                         onChange={(e) => update((c) => ({ ...c, followUp: e.target.value ? { ...c.followUp, dueDate: e.target.value } : undefined }))} />
                  {content.followUp && <button type="button" className="link" onClick={() => update((c) => ({ ...c, followUp: undefined }))}>None</button>}
                </div>
              </div>

              <details className="more">
                <summary>More details (vitals, history, examination, tests, private note)</summary>
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
                <VoiceTextArea id="history" label="History" value={content.historyOfPresentIllness} onChange={set('historyOfPresentIllness')} />
                <VoiceTextArea id="exam" label="Examination" value={content.examination} onChange={set('examination')} />
                <TestsField items={content.investigations} editable={editable}
                            onChange={(items) => update((c) => ({ ...c, investigations: items }))} />
                <VoiceTextArea id="privateNote" label="Private note (only this clinic sees it; never printed or shared)"
                               value={content.privateNote} onChange={set('privateNote')} />
                {!amending && editingDraft && templates.length > 0 && (
                  <label className="field">Fill from a template
                    <select id="template" value="" onChange={(e) => applyTemplate(e.target.value)}>
                      <option value="">Choose a template…</option>
                      {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                    </select>
                  </label>
                )}
              </details>
            </fieldset>

            <AttachedFiles patientId={encounter.patientId} encounterId={encounter.id} canAttach={has('DOCTOR', 'NURSE')} />

            {amending ? (
              <div className="action-bar" style={{ display: 'grid', gap: 8 }}>
                <input id="amendReason" placeholder="Reason for the change (required)" value={amendReason}
                       onChange={(e) => setAmendReason(e.target.value)} />
                <div className="row end">
                  <button type="button" onClick={() => { setAmending(false); setContent(withDefaults(encounter.content)); }}>Cancel</button>
                  <button type="button" className="primary" disabled={amendReason.trim().length < 5} onClick={submitAmendment}>Save correction</button>
                </div>
              </div>
            ) : editingDraft && isAuthor && (
              <div className="action-bar">
                {phone && <span className={`save-state ${saveState === 'error' || saveState === 'conflict' ? 'bad' : ''}`}>{saveLabel}</span>}
                <button type="button" className="primary block" onClick={sign}>Finish visit</button>
              </div>
            )}
          </section>
        )}
      </div>
    </>
  );
}

/** Diagnosis as plain text. Several can be separated with ";". Picking a suggestion keeps its code. */
function DiagnosisField({ content, options, onChange }:
  { content: EncounterContent; options: DiagnosisOption[]; onChange: (d: DiagnosisEntry[]) => void }) {
  const [text, setText] = useState(() => content.diagnoses.map((d) => d.display).join('; '));
  const chronic = content.diagnoses.some((d) => d.chronic);

  // Follow changes made elsewhere (template, reload) without fighting the user's typing
  const joined = content.diagnoses.map((d) => d.display).join('; ');
  useEffect(() => {
    if (normalise(text) !== normalise(joined)) setText(joined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [joined]);

  function parse(value: string, isChronic: boolean): DiagnosisEntry[] {
    return value.split(';').map((s) => s.trim()).filter(Boolean).map((display, i) => {
      const match = options.find((o) => o.display.toLowerCase() === display.toLowerCase());
      return {
        display: match?.display ?? display,
        code: match?.code || undefined,
        codeSystem: match?.code ? match.codeSystem : undefined,
        kind: i === 0 ? 'PRIMARY' : 'SECONDARY',
        chronic: isChronic,
      };
    });
  }

  function setValue(value: string) {
    setText(value);
    onChange(parse(value, chronic));
  }
  const textRef = useRef(text);
  textRef.current = text;
  // A spoken diagnosis is matched to the list when it names one, so it keeps its code
  const base = useRef('');
  const d = useDictation({
    onStart: () => { base.current = textRef.current; },
    onText: (spoken) => {
      const s = spoken.toLowerCase();
      const match = options.find((o) => o.display.toLowerCase() === s) ?? options.find((o) => o.display.toLowerCase().startsWith(s));
      setValue(appendSpoken(base.current, match?.display ?? spoken, '; '));
    },
  });

  return (
    <div style={{ display: 'grid', gap: 6 }}>
      <div className="field">
        <div className="field-head">
          <label htmlFor="diagnosis">Diagnosis</label>
          <MicButton listening={d.listening} onClick={d.toggle} label="Diagnosis" />
        </div>
        <input id="diagnosis" list="diagnosis-options" value={text} placeholder="e.g. Viral fever"
               className={d.listening ? 'listening' : undefined} onChange={(e) => setValue(e.target.value)} />
        {d.listening && <span className="dictating" aria-live="polite">{d.interim || 'Listening… say the diagnosis'}</span>}
        {d.error && <span className="dictating bad">{d.error}</span>}
      </div>
      <datalist id="diagnosis-options">{options.map((o) => <option key={o.display} value={o.display} />)}</datalist>
      {text.trim() && (
        <label className="check small">
          <input type="checkbox" checked={chronic} onChange={(e) => onChange(parse(text, e.target.checked))} />
          Long-term condition (shows in the patient's active problems)
        </label>
      )}
    </div>
  );
}

function normalise(s: string) {
  return s.split(';').map((x) => x.trim()).filter(Boolean).join(';').toLowerCase();
}

/** One medicine: name, how often, when, for how long. Picking a suggestion fills the doctor's usual dose. */
function MedicineRow({ n, item, options, onChange, onRemove }:
  { n: number; item: RxItem; options: RxItem[]; onChange: (r: RxItem) => void; onRemove: () => void }) {
  const days = item.longTerm ? 'ongoing' : item.durationDays ? String(item.durationDays) : '';
  function setName(value: string, match = options.find((o) => o.drugDisplay === value)) {
    onChange(match && !item.frequency
      ? { ...item, ...match, timing: match.timing ?? item.timing }
      : { ...item, drugDisplay: match?.drugDisplay ?? value, drugId: match?.drugId, genericName: match?.genericName ?? item.genericName });
  }
  // A spoken name picks the first suggestion it matches (the doctor's favourites come first)
  const d = useDictation({
    onText: (spoken) => {
      const s = spoken.toLowerCase();
      const match = options.find((o) => o.drugDisplay.toLowerCase() === s)
        ?? options.find((o) => o.drugDisplay.toLowerCase().startsWith(s) || o.genericName?.toLowerCase() === s);
      setName(spoken.charAt(0).toUpperCase() + spoken.slice(1), match);
    },
  });
  return (
    <div className="med-row">
      <div className="med-name">
        <input aria-label={`Medicine ${n}`} list="drug-options" placeholder={d.listening ? (d.interim || 'Say the medicine…') : 'Medicine name'}
               value={item.drugDisplay} className={d.listening ? 'listening' : undefined}
               onChange={(e) => setName(e.target.value)} />
        <MicButton listening={d.listening} onClick={d.toggle} label={`medicine ${n}`} />
      </div>
      <input className="med-freq" aria-label={`How often ${n}`} list="frequencies" placeholder="How often (1-0-1)" value={item.frequency ?? ''}
             onChange={(e) => onChange({ ...item, frequency: e.target.value || undefined })} />
      <select className="med-when" aria-label={`When ${n}`} value={item.timing ?? ''} onChange={(e) => onChange({ ...item, timing: e.target.value || undefined })}>
        {TIMINGS.map((t) => <option key={t} value={t}>{t || 'When?'}</option>)}
      </select>
      <select className="med-days" aria-label={`Days ${n}`} value={days}
              onChange={(e) => onChange(e.target.value === 'ongoing'
                ? { ...item, longTerm: true, durationDays: undefined }
                : { ...item, longTerm: false, durationDays: e.target.value ? Number(e.target.value) : undefined })}>
        <option value="">Days</option>
        {DAYS.map((d) => <option key={d} value={d}>{d} {d === 1 ? 'day' : 'days'}</option>)}
        <option value="ongoing">Ongoing</option>
      </select>
      <button type="button" className="link danger med-x" aria-label={`Remove medicine ${n}`} onClick={onRemove}>✕</button>
    </div>
  );
}

function SignedView({ encounter, canAmend, onAmend }: { encounter: Encounter; canAmend: boolean; onAmend: () => void }) {
  const { has } = useSession();
  const c = encounter.content;
  const latest = encounter.versions[encounter.versions.length - 1];
  return (
    <section className="card">
      <div className="between">
        <h2>Visit</h2>
        <div className="row">
          <button className="primary" onClick={() => openPdf(`/clinic/encounters/${encounter.id}/prescription.pdf?paper=A5`)}>Print</button>
          <button onClick={() => openPdf(`/clinic/encounters/${encounter.id}/prescription.pdf?paper=A4`)}>Print A4</button>
          {canAmend && <button onClick={onAmend}>Correct</button>}
        </div>
      </div>
      <p className="muted small">
        Visit {dateTime(encounter.startedAt)} · finished {dateTime(encounter.signedAt)}
        {latest && latest.version > 1 && <> · corrected {dateTime(latest.signedAt)} ({latest.amendmentReason})</>}
      </p>
      <dl className="signed-view" style={{ margin: 0 }}>
        {c.chiefComplaints && <><dt>Complaints</dt><dd>{c.chiefComplaints}</dd></>}
        {c.diagnoses.length > 0 && (
          <><dt>Diagnosis</dt><dd>{c.diagnoses.map((d) => d.display).join('; ')}</dd></>
        )}
        {c.treatmentGiven && <><dt>Treatment given</dt><dd>{c.treatmentGiven}</dd></>}
        {c.prescription.length > 0 && (
          <>
            <dt>Medicines</dt>
            <dd>
              {c.prescription.map((r, i) => (
                <div key={i}>
                  {i + 1}. <b>{r.drugDisplay}</b>{' '}
                  <span className="muted">{[r.dose, r.frequency, r.timing, r.longTerm ? 'ongoing' : r.durationDays ? `${r.durationDays} days` : ''].filter(Boolean).join(' · ')}</span>
                  {r.instructions && <div className="small muted">{r.instructions}</div>}
                </div>
              ))}
            </dd>
          </>
        )}
        {c.advice && <><dt>Advice</dt><dd>{c.advice}</dd></>}
        {c.followUp && <><dt>Next visit</dt><dd>{date(c.followUp.dueDate)}{c.followUp.note ? ` · ${c.followUp.note}` : ''}</dd></>}
        {c.examination && <><dt>Examination</dt><dd>{c.examination}</dd></>}
        {c.investigations.length > 0 && <><dt>Tests</dt><dd>{c.investigations.map((i) => i.display).join(', ')}</dd></>}
        {c.privateNote && <><dt>Private note</dt><dd className="muted">{c.privateNote}</dd></>}
      </dl>
      <AttachedFiles patientId={encounter.patientId} encounterId={encounter.id} canAttach={has('DOCTOR', 'NURSE')} />
    </section>
  );
}

function TestsField({ items, editable, onChange }:
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
      <h3>Tests advised</h3>
      <div className="row">
        {items.map((i, n) => (
          <span key={n} className="chip">{i.display}
            {editable && <button type="button" className="link danger" aria-label={`Remove ${i.display}`}
                                 onClick={() => onChange(items.filter((_, j) => j !== n))}>✕</button>}
          </span>
        ))}
      </div>
      {editable && (
        <div className="row" style={{ flexWrap: 'nowrap' }}>
          <input id="testInput" placeholder="e.g. CBC" value={text} onChange={(e) => setText(e.target.value)}
                 onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <button type="button" onClick={add}>Add</button>
        </div>
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
  if (m) return `Medicine ${Number(m[1]) + 1} ${m[2] === 'drugDisplay' ? 'name' : m[2]}`;
  return ({ visit: 'Visit', diagnoses: 'Diagnosis', 'followUp.dueDate': 'Next visit' } as Record<string, string>)[path] ?? path;
}
