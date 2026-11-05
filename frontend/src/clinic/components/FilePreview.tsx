import { useEffect, useState } from 'react';
import { PdfPages } from '../../shared/pdf/PdfPages';

export interface PreviewPage {
  url: string;
  mimeType?: string;
}

/**
 * Shows photos and PDFs inside the app instead of downloading them: images at full width, PDF pages drawn as
 * pictures (phone browsers would download a PDF). "Open in new tab" stays available.
 * Opens at once: "Opening…" until the pages are known ({@code pages} undefined), then "Loading…" on each page until
 * it has arrived, so it never looks blank. Closes with the button, Esc, or a tap outside.
 */
export function FilePreview({ title, pages, error, onClose }:
  { title: string; pages?: PreviewPage[]; error?: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [onClose]);

  return (
    <div className="preview-backdrop" onClick={onClose}>
      <div className="preview" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="preview-head">
          <b>{title}</b>
          <span className="row">
            {pages?.length === 1 && <a className="small" href={pages[0].url} target="_blank" rel="noopener noreferrer">Open in new tab</a>}
            <button type="button" onClick={onClose} autoFocus>Close</button>
          </span>
        </div>
        <div className="preview-body">
          {error ? <div className="alert error">{error}</div>
            : !pages ? <Waiting text="Opening…" />
            : pages.map((p, i) => <Page key={p.url} page={p} label={pages.length > 1 ? `${title}, page ${i + 1}` : title} />)}
        </div>
      </div>
    </div>
  );
}

function Page({ page, label }: { page: PreviewPage; label: string }) {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');
  // Drawn inside the app: phone browsers can't show a PDF in a frame and download it instead
  if (isPdf(page)) return <div className="preview-pdf"><PdfPages url={page.url} title={label} /></div>;
  return (
    <div className="preview-frame image">
      <img src={page.url} alt={label} onLoad={() => setState('ready')} onError={() => setState('failed')}
           style={state === 'ready' ? undefined : { minHeight: 200, minWidth: 'min(320px, 100%)' }} />
      {state === 'loading' && <Waiting text="Loading the photo…" overlay />}
      {state === 'failed' && (
        <div className="preview-waiting overlay">
          <span>This photo couldn't be loaded.</span>
          <a className="small" href={page.url} target="_blank" rel="noopener noreferrer">Try opening it in a new tab</a>
        </div>
      )}
    </div>
  );
}

function Waiting({ text, overlay = false }: { text: string; overlay?: boolean }) {
  return (
    <div className={`preview-waiting${overlay ? ' overlay' : ''}`} role="status">
      <span className="spinner" aria-hidden="true" />
      <span>{text}</span>
    </div>
  );
}

function isPdf(p: PreviewPage) {
  return p.mimeType === 'application/pdf' || /\.pdf(\?|$)/i.test(p.url) || /key=[^&]*\.pdf/i.test(p.url);
}
