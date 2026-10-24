import { useEffect, useState } from 'react';
import { api, ApiError, putFile } from '../api';
import type { DocumentDetail, DocumentSummary } from '../types';
import { date } from '../format';

const KINDS: [string, string][] = [['LAB_REPORT', 'Report'], ['IMAGING', 'Scan / X-ray'], ['PRESCRIPTION', 'Prescription'], ['OTHER', 'Other']];
const KIND_LABEL: Record<string, string> = {
  ...Object.fromEntries(KINDS), DISCHARGE_SUMMARY: 'Discharge summary', VACCINATION: 'Vaccination', BILL: 'Bill', INSURANCE: 'Insurance',
};
const ACCEPTED = ['application/pdf', 'image/jpeg', 'image/png', 'image/webp', 'image/heic'];

/**
 * Optional photos and PDFs. With an encounterId the files belong to that visit; without one, it shows all of the
 * patient's files. Each picked file becomes one document named after the file.
 */
export function AttachedFiles({ patientId, encounterId, canAttach, title = 'Files' }:
  { patientId: string; encounterId?: string; canAttach: boolean; title?: string }) {
  const [docs, setDocs] = useState<DocumentSummary[]>();
  const [kind, setKind] = useState('LAB_REPORT');
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState<string>();

  const query = encounterId ? `?encounterId=${encounterId}` : '';
  useEffect(() => {
    api.get<DocumentSummary[]>(`/clinic/patients/${patientId}/documents${query}`).then(setDocs).catch(() => setDocs([]));
  }, [patientId, query]);

  async function upload(files: FileList | null) {
    if (!files?.length) return;
    setError(undefined);
    const added: DocumentSummary[] = [];
    for (const file of Array.from(files)) {
      const type = file.type || (file.name.toLowerCase().endsWith('.pdf') ? 'application/pdf' : '');
      if (!ACCEPTED.includes(type)) { setError(`${file.name}: only photos (JPG, PNG, HEIC) and PDFs can be attached.`); continue; }
      if (file.size > 20 * 1024 * 1024) { setError(`${file.name} is larger than 20 MB.`); continue; }
      setBusy(file.name);
      try {
        const start = await api.post<{ documentId: string; uploads: { url: string; contentType: string }[] }>(
          `/clinic/patients/${patientId}/documents`,
          { docType: kind, title: file.name.replace(/\.[^.]+$/, '').slice(0, 200), files: [{ mimeType: type, sizeBytes: file.size }], encounterId },
        );
        await putFile(start.uploads[0].url, file, start.uploads[0].contentType);
        added.push(await api.post<DocumentSummary>(`/clinic/patients/${patientId}/documents/${start.documentId}/complete`));
      } catch (err) {
        setError(`${file.name}: ${err instanceof ApiError ? err.message : 'upload failed.'}`);
      }
    }
    setBusy(undefined);
    if (added.length) setDocs((d) => [...added, ...(d ?? [])]);
  }

  async function open(doc: DocumentSummary) {
    const tab = window.open('', '_blank');
    try {
      const detail = await api.get<DocumentDetail>(`/clinic/patients/${patientId}/documents/${doc.id}`);
      const url = detail.pages[0]?.url;
      if (!url) throw new Error();
      if (tab) tab.location.href = url; else window.location.href = url;
    } catch {
      tab?.close();
      setError("That file couldn't be opened.");
    }
  }

  if (!canAttach && docs?.length === 0) return null;

  return (
    <div className="files">
      <h3>{title}{docs && docs.length > 0 ? ` (${docs.length})` : ''}</h3>
      {error && <div className="alert error">{error}</div>}
      {docs?.map((d) => (
        <div key={d.id} className="file-row">
          <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
            <b>{d.title || KIND_LABEL[d.docType] || 'File'}</b>{' '}
            <span className="muted small">{KIND_LABEL[d.docType] ?? d.docType} · {date(d.recordDate ?? d.createdAt)}{d.source === 'PATIENT' ? ' · from patient' : ''}</span>
          </span>
          <button type="button" onClick={() => open(d)}>Open</button>
        </div>
      ))}
      {canAttach && (
        <div className="row">
          <select aria-label="Type of file" value={kind} onChange={(e) => setKind(e.target.value)} style={{ width: 'auto' }}>
            {KINDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
          <label className="attach" style={{ flex: 1 }}>
            {busy ? `Uploading ${busy}…` : '+ Attach photo or PDF (optional)'}
            <input type="file" multiple accept="image/*,application/pdf" disabled={!!busy}
                   onChange={(e) => { upload(e.target.files); e.target.value = ''; }} />
          </label>
        </div>
      )}
    </div>
  );
}
