import { useEffect, useRef, useState } from 'react';

export interface Option<T> {
  key: string;
  label: string;
  tag?: string;
  value: T;
}

interface Props<T> {
  id: string;
  placeholder: string;
  load: (q: string) => Promise<Option<T>[]>;
  onSelect: (value: T) => void;
  /** Called on Enter when no suggestion is highlighted, so free text can be added. */
  onFreeText?: (text: string) => void;
}

/** Search-as-you-type with keyboard support: ↑ ↓ to move, Enter to pick, Esc to close. Clears after a pick. */
export function Typeahead<T>({ id, placeholder, load, onSelect, onFreeText }: Props<T>) {
  const [text, setText] = useState('');
  const [options, setOptions] = useState<Option<T>[]>([]);
  const [active, setActive] = useState(-1);
  const [open, setOpen] = useState(false);
  const seq = useRef(0);

  useEffect(() => {
    const q = text.trim();
    if (q.length < 2) {
      setOptions([]);
      return;
    }
    const mine = ++seq.current;
    const t = setTimeout(async () => {
      try {
        const opts = await load(q);
        if (mine === seq.current) {
          setOptions(opts);
          setActive(opts.length ? 0 : -1);
          setOpen(true);
        }
      } catch {
        // search is best-effort; typing continues to work
      }
    }, 180);
    return () => clearTimeout(t);
  }, [text, load]);

  function pick(o: Option<T>) {
    onSelect(o.value);
    setText('');
    setOptions([]);
    setOpen(false);
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, options.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, -1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (open && active >= 0 && options[active]) {
        pick(options[active]);
      } else if (onFreeText && text.trim()) {
        onFreeText(text.trim());
        setText('');
        setOptions([]);
      }
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  }

  return (
    <div className="typeahead">
      <input
        id={id}
        role="combobox"
        aria-expanded={open && options.length > 0}
        aria-controls={`${id}-list`}
        autoComplete="off"
        placeholder={placeholder}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        onFocus={() => options.length && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {open && options.length > 0 && (
        <ul id={`${id}-list`} role="listbox">
          {options.map((o, i) => (
            <li key={o.key} role="option" aria-selected={i === active} className={i === active ? 'active' : ''}
                onMouseDown={(e) => { e.preventDefault(); pick(o); }}>
              <span>{o.label}</span>
              {o.tag && <span className="tag">{o.tag}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
