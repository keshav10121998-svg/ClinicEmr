import { useEffect, useState } from 'react';

/** Each page drawn to a picture, plus the page size in points (1/72 inch) for printing at the right paper size. */
export interface RenderedPdf {
  pages: string[];
  widthPt: number;
  heightPt: number;
}

// pdf.js is large, so it loads only the first time a PDF is shown. The "legacy" build runs on older phone browsers.
let pdfjs: Promise<typeof import('pdfjs-dist')> | undefined;
export function loadPdfjs() {
  pdfjs ??= Promise.all([
    import('pdfjs-dist/legacy/build/pdf.mjs'),
    import('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'),
  ]).then(([lib, worker]) => {
    lib.GlobalWorkerOptions.workerSrc = worker.default;
    return lib as unknown as typeof import('pdfjs-dist');
  });
  return pdfjs;
}

/**
 * Draws a PDF's pages inside the page as pictures. Phone browsers (Chrome on Android in particular) can't show a PDF
 * themselves and download it instead; this shows it everywhere. Pages are drawn at twice the screen's sharpness so
 * they also print clearly.
 */
export async function renderPdf(data: ArrayBuffer): Promise<RenderedPdf> {
  const lib = await loadPdfjs();
  const doc = await lib.getDocument({ data: new Uint8Array(data), isEvalSupported: false }).promise;
  try {
    const pages: string[] = [];
    let widthPt = 0;
    let heightPt = 0;
    const scale = Math.max(2, Math.min(3, (window.devicePixelRatio || 1) * 1.5));
    for (let n = 1; n <= doc.numPages; n++) {
      const page = await doc.getPage(n);
      const base = page.getViewport({ scale: 1 });
      if (n === 1) {
        widthPt = base.width;
        heightPt = base.height;
      }
      const viewport = page.getViewport({ scale });
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      const ctx = canvas.getContext('2d')!;
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      await page.render({ canvasContext: ctx, viewport }).promise;
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
      pages.push(blob ? URL.createObjectURL(blob) : canvas.toDataURL('image/png'));
      page.cleanup();
    }
    return { pages, widthPt, heightPt };
  } finally {
    doc.destroy();
  }
}

/** Fetches a PDF by its (signed, same-site) link. */
export async function fetchPdf(url: string): Promise<Blob> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`The file couldn't be loaded (${res.status}).`);
  return res.blob();
}

/** A PDF shown inline from a link, with "Loading…" until its pages are drawn. Used inside the file preview. */
export function PdfPages({ url, title }: { url: string; title: string }) {
  const [pdf, setPdf] = useState<RenderedPdf>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    let made: string[] = [];
    setPdf(undefined);
    setError(undefined);
    fetchPdf(url)
      .then((b) => b.arrayBuffer())
      .then(renderPdf)
      .then((r) => {
        made = r.pages;
        if (cancelled) r.pages.forEach(revoke);
        else setPdf(r);
      })
      .catch(() => { if (!cancelled) setError("This PDF couldn't be shown."); });
    return () => { cancelled = true; made.forEach(revoke); };
  }, [url]);

  if (error) {
    return (
      <div className="pdf-pages-msg">
        <span>{error}</span>
        <a href={url} target="_blank" rel="noopener noreferrer">Open the PDF instead</a>
      </div>
    );
  }
  if (!pdf) {
    return (
      <div className="pdf-pages-msg" role="status">
        <span className="spinner" aria-hidden="true" />
        <span>Loading the PDF…</span>
      </div>
    );
  }
  return (
    <>
      {pdf.pages.map((src, i) => (
        <img key={src} className="pdf-page" src={src} alt={pdf.pages.length > 1 ? `${title}, page ${i + 1}` : title} />
      ))}
    </>
  );
}

export function revoke(src: string) {
  if (src.startsWith('blob:')) URL.revokeObjectURL(src);
}
