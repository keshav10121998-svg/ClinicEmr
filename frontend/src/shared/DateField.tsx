import { useRef, type CSSProperties } from 'react';

/**
 * A date box that always reads day first with the month in words ("11 Nov 2026"), whatever language the phone or
 * browser is set to. The browser's own date box shows its system format, often month first (11/04/2026), which is
 * easy to misread in India. Tapping opens the usual calendar; the value stays yyyy-mm-dd.
 */
export function DateField({ id, value, onChange, min, max, label, placeholder = 'Pick a date', clearable = false, style }: {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  min?: string;
  max?: string;
  /** Accessible name when there is no visible label around the box */
  label?: string;
  placeholder?: string;
  clearable?: boolean;
  style?: CSSProperties;
}) {
  const input = useRef<HTMLInputElement>(null);

  function open() {
    const el = input.current;
    if (!el) return;
    try {
      el.showPicker();
    } catch {
      el.focus();
    }
  }

  return (
    <span className="date-field" style={style}>
      <button type="button" className="date-display" onClick={open} aria-hidden="true" tabIndex={-1}>
        <span className={value ? undefined : 'date-placeholder'}>{value ? indianDate(value) : placeholder}</span>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 10h17M8 3v4M16 3v4" />
        </svg>
      </button>
      {/* The real date input, invisible on top: it keeps keyboard use, screen readers and the native calendar */}
      <input ref={input} id={id} type="date" className="date-native" value={value} min={min} max={max}
             aria-label={label} onChange={(e) => onChange(e.target.value)} />
      {clearable && value && (
        <button type="button" className="date-clear" aria-label="Clear date" onClick={() => onChange('')}>✕</button>
      )}
    </span>
  );
}

/** yyyy-mm-dd as "11 Nov 2026" */
export function indianDate(isoDate: string) {
  const d = new Date(isoDate + 'T00:00:00');
  return Number.isNaN(d.getTime()) ? isoDate : d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
