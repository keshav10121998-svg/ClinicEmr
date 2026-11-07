import { loadPdfjs } from './pdf/PdfPages';

/**
 * Makes a big scanned PDF (pages that are really photos) much smaller before it is uploaded: each page is drawn as a
 * picture at the same size as a shrunk photo and the pictures are put back together into a new PDF. An 8 MB scan
 * becomes roughly 1 to 1.5 MB.
 *
 * Only PDFs over 1 MB that carry no real text are touched. A PDF made on a computer (lab reports, discharge summaries)
 * keeps its sharp, selectable text and any digital signature, so it is sent as it is. Long PDFs, ones that can't be
 * read, and results that wouldn't be clearly smaller also go unchanged, and a failure never stops the upload.
 */
const MIN_BYTES = 1024 * 1024;
const MAX_PAGES = 40;
const MAX_SIDE = 1800;
const MAX_SCALE = 2.5;
const QUALITY = 0.7;
const TEXT_CHARS_PER_PAGE = 100;

export async function shrinkPdf(file: File): Promise<File> {
  if (file.size <= MIN_BYTES || (file.type !== 'application/pdf' && !/\.pdf$/i.test(file.name))) return file;
  try {
    const lib = await loadPdfjs();
    const doc = await lib.getDocument({ data: new Uint8Array(await file.arrayBuffer()), isEvalSupported: false }).promise;
    try {
      if (doc.numPages > MAX_PAGES || (await hasRealText(doc))) return file;
      const { PDFDocument } = await import('pdf-lib');
      const out = await PDFDocument.create();
      for (let n = 1; n <= doc.numPages; n++) {
        const page = await doc.getPage(n);
        const base = page.getViewport({ scale: 1 });
        const scale = Math.min(MAX_SCALE, MAX_SIDE / Math.max(base.width, base.height));
        const viewport = page.getViewport({ scale });
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.floor(viewport.width));
        canvas.height = Math.max(1, Math.floor(viewport.height));
        const ctx = canvas.getContext('2d');
        if (!ctx) return file;
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        await page.render({ canvasContext: ctx, viewport }).promise;
        const jpeg = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY));
        page.cleanup();
        if (!jpeg) return file;
        const picture = await out.embedJpg(new Uint8Array(await jpeg.arrayBuffer()));
        // The new page keeps the old page's size on paper
        out.addPage([base.width, base.height]).drawImage(picture, { x: 0, y: 0, width: base.width, height: base.height });
      }
      const bytes = await out.save();
      if (bytes.length > file.size * 0.7) return file;
      return new File([bytes as unknown as BlobPart], file.name, { type: 'application/pdf', lastModified: file.lastModified });
    } finally {
      doc.destroy();
    }
  } catch {
    return file;
  }
}

/** True when the first pages carry real text (a PDF made on a computer) rather than being pictures of pages. */
async function hasRealText(doc: { numPages: number; getPage(n: number): Promise<{ getTextContent(): Promise<{ items: unknown[] }> }> }) {
  const sample = Math.min(3, doc.numPages);
  let chars = 0;
  for (let n = 1; n <= sample; n++) {
    const content = await (await doc.getPage(n)).getTextContent();
    for (const item of content.items) chars += ((item as { str?: string }).str ?? '').trim().length;
  }
  return chars / sample > TEXT_CHARS_PER_PAGE;
}
