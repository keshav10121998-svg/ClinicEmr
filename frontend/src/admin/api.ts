import { ApiError } from '../clinic/api';
import type { Plan, Problem } from '../clinic/types';

export { ApiError };

/** The super admin's own API client: its own token, separate from clinic and patient sign-ins. */
let token: string | undefined;
let onUnauthorized: () => void = () => {};

export function setAdminToken(t?: string) {
  token = t;
}

export function setAdminUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK', detail: "Can't reach the server. Check your connection." });
  }
  if (res.status === 401 && token) onUnauthorized();
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = { ...(await res.json()), status: res.status };
    } catch {
      // not JSON
    }
    throw new ApiError(problem);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const adminApi = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
};

export interface Overview {
  clinics: number;
  doctors: number;
  staff: number;
  patients: number;
  appUsers: number;
  visits: number;
  visitsToday: number;
  visitsLast7Days: number;
  newClinicsLast7Days: number;
  newPatientsLast7Days: number;
  deletedDoctors: number;
  deletedVisits: number;
}

export interface Doctor {
  id: number;
  name: string;
  email: string;
  phone?: string;
  status: string;
  createdAt: string;
  deletedAt?: string;
  isDoctor: boolean;
  registrationNo?: string;
  qualifications?: string;
  specialty?: string;
  clinics: string;
  visits: number;
  patients: number;
  lastVisitAt?: string;
}

export interface ClinicInfo {
  id: number;
  name: string;
  city?: string;
  status: string;
  createdAt: string;
  deletedAt?: string;
  roles: string;
  patients: number;
  staff: number;
}

export interface AdminVisit {
  id: string;
  startedAt: string;
  status: 'DRAFT' | 'SIGNED' | 'CANCELLED';
  deletedAt?: string;
  deletedReason?: string;
  patientName: string;
  mrn?: string;
  clinicName: string;
  diagnoses: string;
}

export interface DoctorDetail {
  doctor: Doctor;
  clinics: ClinicInfo[];
  visits: AdminVisit[];
  draftVisits: number;
}

export interface DeleteResult {
  visitsDeleted: number;
  clinicsDeleted: string[];
  patientsDeleted: number;
}

export interface PurgeResult {
  visits: number;
  clinics: number;
  patients: number;
  files: number;
}

/** A clinic's plan and who to ask for the payment. */
export interface ClinicPlan {
  plan: Plan;
  city?: string;
  ownerName?: string;
  ownerPhone?: string;
  ownerEmail?: string;
  payments: number;
}

export interface BillingOverview {
  clinics: number;
  freeMonth: number;
  paid: number;
  due: number;
  monthlyIncome: number;
  items: ClinicPlan[];
}

export interface SubscriptionPayment {
  id: number;
  amount: number;
  months: number;
  coversFrom: string;
  coversUntil: string;
  note?: string;
  recordedAt: string;
}
