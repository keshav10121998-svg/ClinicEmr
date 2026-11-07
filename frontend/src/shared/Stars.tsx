const COLOR_ON = '#f5a623';
const COLOR_OFF = '#cfd6d3';

const CSS = `
.stars { display: inline-flex; gap: 2px; vertical-align: middle; }
.star-picker { display: flex; gap: 4px; }
.star-picker button { width: 48px; height: 48px; min-height: 48px; padding: 0; display: grid; place-items: center; border: 0;
  border-radius: 8px; background: transparent; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.star-picker button:focus-visible { outline: 2px solid var(--accent, #0d6b5a); outline-offset: 2px; }
`;

function Star({ on, size }: { on: boolean; size: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true">
      <path fill={on ? COLOR_ON : COLOR_OFF} d="m12 2.5 2.9 6.1 6.6.9-4.8 4.6 1.2 6.6L12 17.5l-5.9 3.2 1.2-6.6L2.5 9.5l6.6-.9z" />
    </svg>
  );
}

/** A rating shown as stars (rounded to the nearest whole star), with the number readable by screen readers. */
export function Stars({ value, size = 18 }: { value: number; size?: number }) {
  const full = Math.round(value);
  return (
    <span className="stars" role="img" aria-label={`${value} out of 5 stars`}>
      <style>{CSS}</style>
      {[1, 2, 3, 4, 5].map((n) => <Star key={n} on={n <= full} size={size} />)}
    </span>
  );
}

/** Five big stars to tap: tapping the same star again clears it. */
export function StarPicker({ value, onChange, label }: { value: number; onChange: (stars: number) => void; label: string }) {
  return (
    <div className="star-picker" role="radiogroup" aria-label={label}>
      <style>{CSS}</style>
      {[1, 2, 3, 4, 5].map((n) => (
        <button key={n} type="button" role="radio" aria-checked={value === n} aria-label={`${n} ${n === 1 ? 'star' : 'stars'}`}
                onClick={() => onChange(value === n ? 0 : n)}>
          <Star on={n <= value} size={36} />
        </button>
      ))}
    </div>
  );
}
