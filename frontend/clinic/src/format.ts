import type { Patient } from './types';

const IST: Intl.DateTimeFormatOptions = { timeZone: 'Asia/Kolkata' };

export function date(iso?: string) {
  if (!iso) return '';
  // Plain dates (yyyy-mm-dd) are calendar dates, not instants
  const d = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(iso + 'T00:00:00') : new Date(iso);
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', ...(iso.length > 10 ? IST : {}) });
}

export function dateTime(iso?: string) {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', ...IST });
}

export function sexLabel(g: string) {
  return { M: 'Male', F: 'Female', O: 'Other' }[g] ?? '';
}

export function ageSex(p: Pick<Patient, 'ageYears' | 'dobEstimated' | 'gender'>) {
  const age = p.ageYears == null ? '' : `${p.ageYears} yrs${p.dobEstimated ? ' (approx.)' : ''}`;
  return [age, sexLabel(p.gender)].filter(Boolean).join(' · ');
}

export function todayIst() {
  return new Date().toLocaleDateString('en-CA', IST); // yyyy-mm-dd
}

export function addDays(isoDate: string, days: number) {
  const d = new Date(isoDate + 'T00:00:00');
  d.setDate(d.getDate() + days);
  return d.toLocaleDateString('en-CA');
}
