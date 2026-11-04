import { useEffect } from 'react';

export interface PreviewPage {
  url: string;
  mimeType?: string;
}

/**
 * Shows photos and PDFs inside the app instead of downloading them: images at full width, PDFs in an embedded
 * viewer. "Open in new tab" stays available, for phones that can't show a PDF inside the page.
 * Closes with the button, Esc, or a tap outside.
 */
export function FilePreview({ title, pages, onClose }: { title: string; pages: PreviewPage[]; onClose: () => void }) {
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
            {pages.length === 1 && <a className="small" href={pages[0].url} target="_blank" rel="noopener noreferrer">Open in new tab</a>}
            <button type="button" onClick={onClose} autoFocus>Close</button>
          </span>
        </div>
        <div className="preview-body">
          {pages.map((p, i) => isPdf(p) ? (
            <div key={i} className="preview-pdf">
              <iframe src={p.url} title={`${title}, page ${i + 1}`} />
              <a className="small" href={p.url} target="_blank" rel="noopener noreferrer">Can't see it? Open the PDF</a>
            </div>
          ) : (
            <img key={i} src={p.url} alt={pages.length > 1 ? `${title}, page ${i + 1}` : title} />
          ))}
        </div>
      </div>
    </div>
  );
}

function isPdf(p: PreviewPage) {
  return p.mimeType === 'application/pdf' || /\.pdf(\?|$)/i.test(p.url) || /key=[^&]*\.pdf/i.test(p.url);
}
