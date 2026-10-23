import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, openUrl, putFile } from '../api';
import type { PatientDocument, UploadStart } from '../types';
import { date } from '../format';

const DOC_TYPES: Record<string, string> = {
  PRESCRIPTION: 'Prescription',
  LAB_REPORT: 'Lab report',
  IMAGING: 'Scan / X-ray',
  DISCHARGE_SUMMARY: 'Discharge summary',
  VACCINATION: 'Vaccination record',
  BILL: 'Bill',
  INSURANCE: 'Insurance',
  OTHER: 'Other',
};

const EXT_TYPES: Record<string, string> = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', heic: 'image/heic' };

function mimeOf(f: File) {
  return f.type || EXT_TYPES[f.name.split('.').pop()?.toLowerCase() ?? ''] || '';
}

export function DocumentsTab({ profileId }: { profileId: string }) {
  const [docs, setDocs] = useState<PatientDocument[]>();
  const [error, setError] = useState<string>();
  const [uploading, setUploading] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<string>();

  const load = useCallback(() => {
    api.get<PatientDocument[]>(`/patient/profiles/${profileId}/documents`)
      .then(setDocs)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load documents.'));
  }, [profileId]);

  useEffect(() => { setDocs(undefined); load(); }, [load]);

  async function open(id: string) {
    setError(undefined);
    try {
      const d = await api.get<{ pages: { url: string }[] }>(`/patient/profiles/${profileId}/documents/${id}`);
      d.pages.forEach((p) => openUrl(p.url));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not open the document.');
    }
  }

  async function remove(id: string) {
    setError(undefined);
    try {
      await api.del(`/patient/profiles/${profileId}/documents/${id}`);
      setConfirmDelete(undefined);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete.');
    }
  }

  return (
    <>
      {error && <div className="alert error">{error}</div>}
      {uploading ? (
        <UploadForm profileId={profileId} onDone={() => { setUploading(false); load(); }} onCancel={() => setUploading(false)} />
      ) : (
        <button className="primary block" onClick={() => setUploading(true)}>Add an old report or prescription</button>
      )}
      <div className="card">
        <h3>Documents</h3>
        {!docs && <p className="muted">Loading…</p>}
        {docs?.length === 0 && <p className="muted">No documents yet. Add photos or PDFs of old prescriptions and reports so your doctors can see them.</p>}
        <div className="list">
          {docs?.map((d) => (
            <div key={d.id} style={{ display: 'grid', gap: 4 }}>
              <div className="between">
                <b>{d.title || DOC_TYPES[d.docType] || d.docType}</b>
                {d.verified ? <span className="chip ok">From {d.clinicName}</span> : <span className="chip">Added by you</span>}
              </div>
              <div className="small muted">
                {DOC_TYPES[d.docType]}{d.recordDate ? ` · ${date(d.recordDate)}` : ''} · {d.pages} page{d.pages === 1 ? '' : 's'}
              </div>
              <div className="row">
                <button onClick={() => open(d.id)}>Open</button>
                {d.canDelete && (confirmDelete === d.id ? (
                  <>
                    <button className="danger" onClick={() => remove(d.id)}>Yes, delete</button>
                    <button onClick={() => setConfirmDelete(undefined)}>Keep</button>
                  </>
                ) : (
                  <button className="link danger" onClick={() => setConfirmDelete(d.id)}>Delete</button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

function UploadForm({ profileId, onDone, onCancel }: { profileId: string; onDone: () => void; onCancel: () => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [docType, setDocType] = useState('PRESCRIPTION');
  const [title, setTitle] = useState('');
  const [recordDate, setRecordDate] = useState('');
  const [progress, setProgress] = useState<string>();
  const [error, setError] = useState<string>();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(undefined);
    try {
      setProgress('Preparing…');
      const start = await api.post<UploadStart>(`/patient/profiles/${profileId}/documents`, {
        docType, title: title || undefined, recordDate: recordDate || undefined,
        files: files.map((f) => ({ mimeType: mimeOf(f), sizeBytes: f.size })),
      });
      for (const [i, u] of start.uploads.entries()) {
        setProgress(`Uploading page ${i + 1} of ${start.uploads.length}…`);
        await putFile(u.url, u.contentType, files[i]);
      }
      setProgress('Finishing…');
      await api.post(`/patient/profiles/${profileId}/documents/${start.documentId}/complete`);
      onDone();
    } catch (err) {
      setProgress(undefined);
      setError(err instanceof ApiError && err.problem.detail ? err.message : 'Upload failed. Check your connection and try again.');
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>Add a document</h2>
      {error && <div className="alert error">{error}</div>}
      <label className="field">Photos or PDF (one document can have several pages)
        <input id="files" type="file" multiple required accept="application/pdf,image/jpeg,image/png,image/webp,image/heic,.heic"
               onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
      </label>
      <label className="field">Type
        <select id="docType" value={docType} onChange={(e) => setDocType(e.target.value)}>
          {Object.entries(DOC_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </label>
      <label className="field">Title (optional)
        <input id="docTitle" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Blood test, Apollo, 2023" />
      </label>
      <label className="field">Date on the document (optional)
        <input id="docDate" type="date" value={recordDate} onChange={(e) => setRecordDate(e.target.value)} max={new Date().toISOString().slice(0, 10)} />
      </label>
      <p className="small muted">Documents you add are marked as added by you, so doctors know they didn't come from a clinic.</p>
      {progress && <div className="alert info">{progress}</div>}
      <div className="row">
        <button type="button" onClick={onCancel} disabled={!!progress}>Cancel</button>
        <button className="primary" disabled={!!progress || files.length === 0}>Upload</button>
      </div>
    </form>
  );
}
