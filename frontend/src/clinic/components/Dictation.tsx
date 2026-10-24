import { useEffect, useRef, useState } from 'react';

/*
 * Speak instead of typing, using the browser's own speech recognition (Chrome, Edge, Android, iPhone Safari).
 * Where the browser has none (e.g. Firefox) the mic simply isn't shown. Only one field listens at a time.
 */

interface RecognitionResult { isFinal: boolean; 0: { transcript: string } }
interface RecognitionEvent { resultIndex: number; results: ArrayLike<RecognitionResult> }
interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: RecognitionEvent) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}
type RecognitionCtor = new () => Recognition;

const Ctor: RecognitionCtor | undefined =
  (window as unknown as { SpeechRecognition?: RecognitionCtor }).SpeechRecognition
  ?? (window as unknown as { webkitSpeechRecognition?: RecognitionCtor }).webkitSpeechRecognition;

export const dictationSupported = !!Ctor;

let active: Recognition | null = null;

// Chrome on Android repeats results it already sent (the same phrase twice, or a phrase then a longer version of
// it), and its continuous mode is unreliable. There we listen for one phrase per tap.
const isAndroid = /Android/i.test(navigator.userAgent);

/**
 * Turns a session's finished results into one text, dropping repeats: identical phrases, a phrase followed by a
 * longer version of itself (the longer one wins), and a phrase said back to back inside one result ("abcd abcd").
 */
export function joinSpoken(finals: string[]) {
  const out: string[] = [];
  for (const raw of finals) {
    const t = collapseRepeat(raw.trim());
    if (!t) continue;
    const last = out[out.length - 1];
    const lt = t.toLowerCase();
    const ll = last?.toLowerCase();
    if (ll === undefined) out.push(t);
    else if (lt === ll || ll.startsWith(lt)) continue;
    else if (lt.startsWith(ll)) out[out.length - 1] = t;
    else out.push(t);
  }
  return out.join(' ');
}

function collapseRepeat(t: string) {
  const words = t.split(/\s+/);
  if (words.length % 2 === 0) {
    const half = words.length / 2;
    if (words.slice(0, half).join(' ').toLowerCase() === words.slice(half).join(' ').toLowerCase()) {
      return words.slice(0, half).join(' ');
    }
  }
  return t;
}

/**
 * Listens until stopped. `onStart` runs when listening begins (so a field can remember what it held), and `onText`
 * gets everything said since then, rebuilt from scratch on every update, so a repeated result can't add text twice.
 */
export function useDictation({ onStart, onText }: { onStart?: () => void; onText: (spoken: string) => void }) {
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string>();
  const rec = useRef<Recognition | null>(null);
  const handlers = useRef({ onStart, onText });
  handlers.current = { onStart, onText };

  useEffect(() => () => rec.current?.stop(), []);

  function start() {
    if (!Ctor) return;
    active?.stop();
    const r = new Ctor();
    r.lang = 'en-IN';
    r.continuous = !isAndroid;
    r.interimResults = true;
    let sent = '';
    r.onresult = (e) => {
      const finals: string[] = [];
      let partial = '';
      for (let i = 0; i < e.results.length; i++) {
        const res = e.results[i];
        if (res.isFinal) finals.push(res[0].transcript);
        else partial = res[0].transcript;
      }
      const text = joinSpoken(finals);
      if (text && text !== sent) {
        sent = text;
        handlers.current.onText(text);
      }
      setInterim(partial.trim());
    };
    r.onerror = (e) => {
      setError(e.error === 'not-allowed' || e.error === 'service-not-allowed'
        ? 'Microphone access is blocked. Allow it in the browser settings for this site.'
        : e.error === 'no-speech' ? undefined : "Couldn't hear that. Try again.");
    };
    r.onend = () => {
      setListening(false);
      setInterim('');
      if (active === r) active = null;
    };
    setError(undefined);
    handlers.current.onStart?.();
    rec.current = r;
    active = r;
    r.start();
    setListening(true);
  }

  function stop() {
    rec.current?.stop();
  }

  return { listening, interim, error, toggle: () => (listening ? stop() : start()) };
}

/** Adds a spoken phrase to what is already in a field. */
export function appendSpoken(existing: string | undefined, spoken: string, separator = ', ') {
  const before = (existing ?? '').trimEnd();
  if (!before) return spoken.charAt(0).toUpperCase() + spoken.slice(1);
  return /[.,;:]$/.test(before) ? `${before} ${spoken}` : `${before}${separator}${spoken}`;
}

export function MicButton({ listening, onClick, label }: { listening: boolean; onClick: () => void; label: string }) {
  if (!dictationSupported) return null;
  return (
    <button type="button" className={`mic${listening ? ' on' : ''}`} onClick={onClick}
            aria-pressed={listening} aria-label={listening ? `Stop speaking into ${label}` : `Speak into ${label}`}
            title={listening ? 'Stop' : 'Speak'}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
           strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /><path d="M12 18v3" />
      </svg>
      {listening && <span className="mic-text">Stop</span>}
    </button>
  );
}

/** A labelled text area with a mic beside the label. */
export function VoiceTextArea({ id, label, value, onChange, placeholder, rows = 2, autoFocus }: {
  id: string; label: string; value: string | undefined; onChange: (v: string) => void;
  placeholder?: string; rows?: number; autoFocus?: boolean;
}) {
  const valueRef = useRef(value);
  valueRef.current = value;
  const base = useRef<string | undefined>();
  const d = useDictation({
    onStart: () => { base.current = valueRef.current; },
    onText: (spoken) => onChange(appendSpoken(base.current, spoken)),
  });
  return (
    <div className="field">
      <div className="field-head">
        <label htmlFor={id}>{label}</label>
        <MicButton listening={d.listening} onClick={d.toggle} label={label} />
      </div>
      <textarea id={id} rows={rows} value={value ?? ''} placeholder={placeholder} autoFocus={autoFocus}
                className={d.listening ? 'listening' : undefined}
                onChange={(e) => onChange(e.target.value)} />
      {d.listening && <span className="dictating" aria-live="polite">{d.interim || 'Listening… speak now'}</span>}
      {d.error && <span className="dictating bad">{d.error}</span>}
    </div>
  );
}
