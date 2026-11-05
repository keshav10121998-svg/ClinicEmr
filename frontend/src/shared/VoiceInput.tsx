import { useEffect, useRef, useState, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';

// The browser's speech recognition (Chrome, Edge, Safari, Android). Not in TypeScript's DOM types yet.
interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}
type RecognitionClass = new () => Recognition;
const SpeechRecognition: RecognitionClass | undefined =
  (window as unknown as { SpeechRecognition?: RecognitionClass; webkitSpeechRecognition?: RecognitionClass }).SpeechRecognition
  ?? (window as unknown as { webkitSpeechRecognition?: RecognitionClass }).webkitSpeechRecognition;

type Field = HTMLInputElement | HTMLTextAreaElement;
const TEXT_TYPES = ['text', 'search', 'tel', 'number', 'url', ''];
const LANGS = [['en-IN', 'English'], ['hi-IN', 'हिन्दी']] as const;
const MIC = 36;

/** Text boxes that get the mic: not passwords, e-mail, dates, read-only ones, or any inside data-no-mic. */
function eligible(el: Element | null): el is Field {
  if (el instanceof HTMLTextAreaElement) return !el.readOnly && !el.disabled && !el.closest('[data-no-mic]');
  return el instanceof HTMLInputElement && TEXT_TYPES.includes(el.getAttribute('type') ?? '')
    && !el.readOnly && !el.disabled && !el.closest('[data-no-mic]');
}

/** Sets a field's value so React sees it as typing (its onChange runs). */
function typeInto(el: Field, value: string) {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value);
  el.dispatchEvent(new Event('input', { bubbles: true }));
}

/** What was said, fitted to the field: digits only for phone and number boxes, joined to existing text otherwise. */
function combine(el: Field, base: string, said: string) {
  const type = el.getAttribute('type');
  let value: string;
  if (type === 'tel') value = said.replace(/\D/g, '');
  else if (type === 'number') value = said.replace(/[^\d.]/g, '');
  else value = base && said ? `${base}${/\s$/.test(base) ? '' : ' '}${said}` : base || said;
  const max = el.maxLength > 0 ? el.maxLength : undefined;
  return max ? value.slice(0, max) : value;
}

function savedLang() {
  try { return localStorage.getItem('voice-lang') ?? 'en-IN'; } catch { return 'en-IN'; }
}

/**
 * Voice typing for every text box in the app: a mic button sits at the right end of whichever box has focus. Tap it,
 * speak, and the words go into the box (added to what is there; phone and number boxes get just the digits). Tap
 * again, or pause, to stop. Mounted once; shows nothing where the browser can't recognise speech.
 */
export function VoiceInput() {
  const [field, setField] = useState<Field>();
  const [listening, setListening] = useState(false);
  const [lang, setLang] = useState(savedLang);
  const [note, setNote] = useState<string>();
  const button = useRef<HTMLButtonElement>(null);
  const bubble = useRef<HTMLDivElement>(null);
  const rec = useRef<Recognition>();

  // Follow focus: the mic belongs to the focused text box
  useEffect(() => {
    if (!SpeechRecognition) return;
    const onIn = (e: FocusEvent) => { if (eligible(e.target as Element)) setField(e.target as Field); };
    const onOut = () => window.setTimeout(() => {
      if (!eligible(document.activeElement)) { rec.current?.abort(); setField(undefined); }
    }, 0);
    document.addEventListener('focusin', onIn);
    document.addEventListener('focusout', onOut);
    return () => { document.removeEventListener('focusin', onIn); document.removeEventListener('focusout', onOut); };
  }, []);

  // Room for the mic inside the box, and keep it placed while the page scrolls or the box moves
  useEffect(() => {
    if (!field) return;
    const padding = field.style.paddingRight;
    field.style.paddingRight = `${MIC + 8}px`;
    let frame = 0;
    const place = () => {
      const r = field.getBoundingClientRect();
      const top = field instanceof HTMLTextAreaElement ? r.top + 4 : r.top + (r.height - MIC) / 2;
      const hidden = r.width === 0 || !field.isConnected;
      if (button.current) {
        button.current.style.transform = `translate(${r.right - MIC - 4}px, ${top}px)`;
        button.current.style.visibility = hidden ? 'hidden' : '';
      }
      if (bubble.current) {
        bubble.current.style.transform = `translate(${Math.max(8, r.right - bubble.current.offsetWidth)}px, ${Math.max(4, r.top - bubble.current.offsetHeight - 6)}px)`;
      }
      frame = requestAnimationFrame(place);
    };
    place();
    return () => { cancelAnimationFrame(frame); field.style.paddingRight = padding; };
  }, [field]);

  useEffect(() => {
    if (!note) return;
    const t = window.setTimeout(() => setNote(undefined), 4000);
    return () => window.clearTimeout(t);
  }, [note]);

  useEffect(() => () => rec.current?.abort(), []);

  function start(el: Field, language = lang) {
    if (!SpeechRecognition) return;
    rec.current?.abort();
    const r = new SpeechRecognition();
    r.lang = language;
    r.interimResults = true;
    r.continuous = el instanceof HTMLTextAreaElement; // notes can be long; a name or search is one phrase
    const base = el.value;
    r.onresult = (e) => {
      const said = Array.from(e.results).map((x) => x[0].transcript).join(' ').replace(/\s+/g, ' ').trim();
      typeInto(el, combine(el, base, said));
    };
    r.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') setNote('Allow the microphone for this site to type by voice.');
      else if (e.error === 'no-speech') setNote("Didn't hear anything. Tap the mic and speak.");
      else if (e.error === 'network') setNote('Voice typing needs the internet.');
      else if (e.error !== 'aborted') setNote("Voice typing didn't work. Try again.");
    };
    r.onend = () => { if (rec.current === r) { rec.current = undefined; setListening(false); } };
    rec.current = r;
    setNote(undefined);
    setListening(true);
    try { r.start(); } catch { setListening(false); }
  }

  function toggle() {
    if (!field) return;
    if (listening) rec.current?.stop();
    else start(field);
  }

  function switchLang() {
    const next = lang === 'en-IN' ? 'hi-IN' : 'en-IN';
    setLang(next);
    try { localStorage.setItem('voice-lang', next); } catch { /* remembered for this visit only */ }
    if (listening && field) start(field, next);
  }

  // Tapping the mic must not take focus away from the box (that would close the phone keyboard and hide the mic)
  const keepFocus = (e: SyntheticEvent) => e.preventDefault();

  if (!SpeechRecognition || !field) return null;
  const langName = LANGS.find(([code]) => code === lang)?.[1] ?? 'English';
  return createPortal(
    <>
      <style>{CSS}</style>
      <button ref={button} type="button" className={`voice-mic${listening ? ' on' : ''}`} onPointerDown={keepFocus} onMouseDown={keepFocus}
              onClick={toggle} aria-label={listening ? 'Stop voice typing' : 'Type by voice'} aria-pressed={listening}>
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
          <path fill="currentColor" d="M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-2.08A7 7 0 0 0 19 12h-2Z" />
        </svg>
      </button>
      {(listening || note) && (
        <div ref={bubble} className="voice-bubble" role="status" onPointerDown={keepFocus} onMouseDown={keepFocus}>
          {note ?? <>Listening… <button type="button" onClick={switchLang} aria-label={`Language: ${langName}. Tap to change`}>{langName}</button></>}
        </div>
      )}
    </>,
    document.body,
  );
}

const CSS = `
.voice-mic { position: fixed; left: 0; top: 0; z-index: 70; width: ${MIC}px; height: ${MIC}px; padding: 0; display: grid; place-items: center;
  border-radius: 50%; border: 0; background: transparent; color: var(--muted, #5c6b66); cursor: pointer; -webkit-tap-highlight-color: transparent; }
.voice-mic:hover { background: rgba(0,0,0,.06); }
.voice-mic.on { background: var(--danger, #c0392b); color: #fff; animation: voice-pulse 1.2s ease-in-out infinite; }
@keyframes voice-pulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(192,57,43,.45); } 50% { box-shadow: 0 0 0 7px rgba(192,57,43,0); } }
.voice-bubble { position: fixed; left: 0; top: 0; z-index: 70; display: flex; align-items: center; gap: 8px; max-width: calc(100vw - 16px);
  padding: 6px 8px 6px 12px; border-radius: 8px; background: #18221f; color: #fff; font-size: 13px; box-shadow: 0 2px 8px rgba(0,0,0,.25); }
.voice-bubble button { font: inherit; font-size: 13px; padding: 4px 10px; min-height: 30px; border-radius: 6px; border: 1px solid rgba(255,255,255,.4);
  background: transparent; color: #fff; cursor: pointer; }
@media print { .voice-mic, .voice-bubble { display: none !important; } }
`;
