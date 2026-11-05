import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { fetchPdf, renderPdf, revoke, type RenderedPdf } from './PdfPages';

/**
 * What to show: a title and a way to get the file links (made on the server when asked). Each link is a PDF or a
 * photo; a document of several pages has several links.
 */
export interface PdfRequest {
  title: string;
  filename: string;
  getUrls: () => Promise<string[]>;
}

type Listener = (r: PdfRequest | undefined) => void;
let listener: Listener | undefined;

/** Opens the in-app PDF screen (mounted once with {@link PdfViewerHost}). */
export function showPdf(request: PdfRequest) {
  if (listener) listener(request);
  else request.getUrls().then((urls) => { if (urls[0]) window.location.href = urls[0]; }).catch(() => {});
}

/** Holds the PDF screen; mount once per app. */
export function PdfViewerHost() {
  const [request, setRequest] = useState<PdfRequest>();
  useEffect(() => {
    listener = setRequest;
    return () => { listener = undefined; };
  }, []);
  // Straight under <body>, so printing can leave out everything else on the page
  return request ? createPortal(<PdfViewer key={request.filename + request.title} request={request} onClose={() => setRequest(undefined)} />, document.body) : null;
}

/**
 * The prescription (or any PDF or photo) shown first, full screen, with Print, Download and Share underneath. Phones
 * never get a surprise download: the PDF is drawn inside the app. Print sends just the pages, at the PDF's own paper
 * size.
 */
function PdfViewer({ request, onClose }: { request: PdfRequest; onClose: () => void }) {
  const [step, setStep] = useState('Preparing…');
  const [files, setFiles] = useState<File[]>();
  const [pdf, setPdf] = useState<RenderedPdf>();
  const [error, setError] = useState<string>();
  const [note, setNote] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    let made: string[] = [];
    (async () => {
      try {
        const urls = await request.getUrls();
        if (urls.length === 0) throw new Error();
        if (cancelled) return;
        setStep('Loading…');
        const blobs = await Promise.all(urls.map(fetchPdf));
        if (cancelled) return;
        const isPdf = await Promise.all(blobs.map(looksLikePdf));
        setFiles(blobs.map((b, i) => new File([b], nameFor(request.filename, i, blobs.length, isPdf[i], b.type), {
          type: isPdf[i] ? 'application/pdf' : b.type,
        })));
        // PDFs are drawn page by page; photos are shown as they are
        const pages: string[] = [];
        made = pages;
        let size: RenderedPdf | undefined;
        for (const [i, b] of blobs.entries()) {
          if (cancelled) break;
          if (isPdf[i]) {
            const r = await renderPdf(await b.arrayBuffer());
            pages.push(...r.pages);
            size ??= r;
          } else {
            pages.push(URL.createObjectURL(b));
          }
        }
        if (cancelled) { pages.forEach(revoke); return; }
        // Print at the PDF's own paper size only when everything is PDF; photos fit the printer's paper
        const pdfSize = isPdf.every(Boolean) && size;
        setPdf({ pages, widthPt: pdfSize ? pdfSize.widthPt : 0, heightPt: pdfSize ? pdfSize.heightPt : 0 });
      } catch (e) {
        if (!cancelled) setError(e instanceof Error && e.message ? e.message : "This couldn't be opened.");
      }
    })();
    return () => { cancelled = true; made.forEach(revoke); };
  }, [request]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = overflow; };
  }, [onClose]);

  const canShare = !!files && typeof navigator.canShare === 'function' && navigator.canShare({ files });

  function download() {
    files?.forEach((file) => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(file);
      a.download = file.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
    });
  }

  async function share() {
    if (!files) return;
    try {
      await navigator.share({ files, title: request.title });
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) setNote("Couldn't share from here. Use Download instead.");
    }
  }

  return (
    <div className="pdfv" role="dialog" aria-modal="true" aria-label={request.title}>
      <style>{CSS}</style>
      {pdf && <style>{pdf.widthPt ? `@page { size: ${Math.round(pdf.widthPt)}pt ${Math.round(pdf.heightPt)}pt; margin: 0; }` : '@page { margin: 10mm; }'}</style>}
      <div className="pdfv-head">
        <b>{request.title}</b>
        <button type="button" className="pdfv-close" onClick={onClose} autoFocus>Close</button>
      </div>
      <div className="pdfv-body">
        {error ? (
          <div className="pdfv-msg"><b>{error}</b><span>Close this and try again.</span></div>
        ) : !pdf ? (
          <div className="pdfv-msg" role="status"><span className="pdfv-spin" aria-hidden="true" /><b>{step}</b><span>This can take a few seconds.</span></div>
        ) : (
          <div className="pdfv-print">
            {pdf.pages.map((src, i) => <img key={src} src={src} alt={pdf.pages.length > 1 ? `Page ${i + 1}` : request.title} />)}
          </div>
        )}
      </div>
      <div className="pdfv-actions">
        {note && <span className="pdfv-note">{note}</span>}
        <button type="button" className="pdfv-primary" disabled={!pdf} onClick={() => window.print()}>Print</button>
        <button type="button" disabled={!files} onClick={download}>Download</button>
        {canShare && <button type="button" onClick={share}>Share</button>}
      </div>
    </div>
  );
}

/** A PDF starts with "%PDF", whatever the storage says its type is. */
async function looksLikePdf(b: Blob) {
  if (b.type === 'application/pdf') return true;
  if (b.type.startsWith('image/')) return false;
  return new TextDecoder().decode(await b.slice(0, 5).arrayBuffer()).startsWith('%PDF');
}

function nameFor(filename: string, i: number, count: number, isPdf: boolean, type: string) {
  const base = filename.replace(/\.pdf$/i, '');
  const ext = isPdf ? 'pdf' : (type.split('/')[1] ?? 'jpg').replace('jpeg', 'jpg');
  return `${base}${count > 1 ? `-${i + 1}` : ''}.${ext}`;
}

const CSS = `
.pdfv { position: fixed; inset: 0; z-index: 60; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; background: #525659; }
.pdfv-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 12px; background: var(--surface, #fff); color: var(--ink, #18221f); }
.pdfv-head b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.pdfv-body { overflow: auto; padding: 12px; -webkit-overflow-scrolling: touch; }
.pdfv-print { display: grid; gap: 12px; justify-items: center; }
.pdfv-print img { width: 100%; max-width: 820px; height: auto; background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.35); }
.pdfv-msg { min-height: 100%; display: grid; place-content: center; justify-items: center; gap: 8px; color: #fff; text-align: center; padding: 24px; }
.pdfv-msg span { color: #d6dbd9; font-size: 14px; }
.pdfv-spin { width: 40px; height: 40px; border-radius: 50%; border: 4px solid rgba(255,255,255,.25); border-top-color: #fff; animation: pdfv-spin .9s linear infinite; }
@keyframes pdfv-spin { to { transform: rotate(360deg); } }
.pdfv-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; align-items: center; padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); background: var(--surface, #fff); }
.pdfv-actions button, .pdfv-close { font: inherit; min-height: 44px; padding: 0 20px; border-radius: 8px; border: 1px solid var(--line, #d8e0dd); background: var(--surface, #fff); color: var(--ink, #18221f); cursor: pointer; }
.pdfv-actions button:disabled { opacity: .5; cursor: default; }
.pdfv-actions .pdfv-primary { background: var(--accent, #0d6b5a); border-color: var(--accent, #0d6b5a); color: #fff; font-weight: 600; min-width: 120px; }
.pdfv-note { flex-basis: 100%; text-align: center; font-size: 13px; color: var(--danger, #a12a1f); }
@media print {
  body > *:not(.pdfv) { display: none !important; }
  .pdfv { position: static; display: block; background: none; }
  .pdfv-head, .pdfv-actions { display: none !important; }
  .pdfv-body { overflow: visible; padding: 0; }
  .pdfv-print { display: block; position: absolute; left: 0; top: 0; width: 100%; }
  .pdfv-print img { display: block; width: 100%; max-width: none; box-shadow: none; break-after: page; page-break-after: always; }
  .pdfv-print img:last-child { break-after: auto; page-break-after: auto; }
}
`;
