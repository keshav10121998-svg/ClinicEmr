/** Minimal shape used by the shared format helpers. */
export interface Patient {
  gender: string;
  ageYears?: number;
  dobEstimated: boolean;
}

export interface Profile {
  profileId: string;
  name: string;
  gender: string;
  ageYears?: number;
  role: 'OWNER' | 'CAREGIVER';
  relation: string;
}

export interface VisitItem {
  encounterId: string;
  clinicName: string;
  clinicCity?: string;
  doctorName: string;
  date: string;
  version: number;
  diagnoses: string;
  medicines: number;
}

export interface RxItem {
  drugDisplay: string;
  genericName?: string;
  dose?: string;
  frequency: string;
  timing?: string;
  durationDays?: number;
  longTerm?: boolean;
  instructions?: string;
}

export interface VisitDetail {
  encounterId: string;
  clinicName: string;
  doctorName: string;
  doctorQualifications: string;
  date: string;
  version: number;
  amended: boolean;
  content: {
    chiefComplaints?: string;
    examination?: string;
    advice?: string;
    vitals?: Record<string, number>;
    diagnoses: { code?: string; display: string }[];
    prescription: RxItem[];
    investigations: { display: string }[];
    followUp?: { dueDate: string; note?: string };
  };
}

export interface PatientDocument {
  id: string;
  docType: string;
  title?: string;
  recordDate?: string;
  source: 'CLINIC' | 'PATIENT' | 'ABDM';
  clinicName?: string;
  verified: boolean;
  pages: number;
  createdAt: string;
  canDelete: boolean;
}

export interface UploadStart {
  documentId: string;
  uploads: { pageNo: number; method: string; url: string; contentType: string }[];
}

export interface PendingRequest {
  requestId: string;
  clinicName: string;
  clinicCity?: string;
  requestedBy: string;
  createdAt: string;
  expiresAt: string;
}

export interface Grant {
  grantId: string;
  profileId: string;
  patientName: string;
  clinicName: string;
  doctorName?: string;
  scopeTypes: ('VISITS' | 'DOCUMENTS')[];
  fromDate?: string;
  expiresAt: string;
  createdAt: string;
  method: 'OTP' | 'APP';
}

export interface AccessLogEntry {
  at: string;
  patientName: string;
  clinicName?: string;
  actorName: string;
  action: string;
}

export interface Problem {
  status: number;
  code?: string;
  detail?: string;
  fields?: Record<string, string>;
  [key: string]: unknown;
}
