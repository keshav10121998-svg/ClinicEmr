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

/** Listens until stopped; each finished phrase goes to onFinal, the words so far to `interim`. */
export function useDictation(onFinal: (text: string) => void) {
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string>();
  const rec = useRef<Recognition | null>(null);
  const finalRef = useRef(onFinal);
  finalRef.current = onFinal;

  useEffect(() => () => rec.current?.stop(), []);

  function start() {
    if (!Ctor) return;
    active?.stop();
    const r = new Ctor();
    r.lang = 'en-IN';
    r.continuous = true;
    r.interimResults = true;
    r.onresult = (e) => {
      let partial = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        if (res.isFinal) {
          const text = res[0].transcript.trim();
          if (text) finalRef.current(text);
        } else {
          partial += res[0].transcript;
        }
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
  const d = useDictation((text) => onChange(appendSpoken(valueRef.current, text)));
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
