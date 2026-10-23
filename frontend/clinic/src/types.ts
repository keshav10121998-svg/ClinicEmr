export type Role = 'ADMIN' | 'FRONT_DESK' | 'NURSE' | 'DOCTOR';

export interface ClinicMembership {
  clinicId: number;
  clinicName: string;
  clinicStatus: string;
  roles: Role[];
}

export interface LoginResponse {
  accessToken: string;
  expiresAt: string;
  staffId: number;
  name: string;
  clinics: ClinicMembership[];
}

export interface Patient {
  publicId: string;
  mrn: string;
  name: string;
  gender: 'M' | 'F' | 'O' | 'U';
  dob?: string;
  dobEstimated: boolean;
  ageYears?: number;
  phone?: string;
  bloodGroup?: string;
  hasAbha: boolean;
}

export interface DuplicateCandidate {
  publicId: string;
  mrn: string;
  name: string;
  gender: string;
  ageYears?: number;
  nameSimilarity: number;
}

export interface Vitals {
  bpSystolic?: number;
  bpDiastolic?: number;
  pulse?: number;
  temperatureF?: number;
  spo2?: number;
  weightKg?: number;
  heightCm?: number;
  respiratoryRate?: number;
  bloodGlucoseMgDl?: number;
}

export interface DiagnosisEntry {
  codeSystem?: string;
  code?: string;
  display: string;
  kind?: 'PRIMARY' | 'SECONDARY' | 'PROVISIONAL';
  chronic?: boolean;
}

export interface RxItem {
  drugId?: number;
  drugDisplay: string;
  genericName?: string;
  dose?: string;
  frequency: string;
  route?: string;
  timing?: string;
  durationDays?: number;
  longTerm?: boolean;
  instructions?: string;
}

export interface InvestigationEntry {
  code?: string;
  display: string;
}

export interface EncounterContent {
  chiefComplaints?: string;
  historyOfPresentIllness?: string;
  pastHistory?: string;
  examination?: string;
  advice?: string;
  privateNote?: string;
  vitals?: Vitals;
  diagnoses: DiagnosisEntry[];
  prescription: RxItem[];
  investigations: InvestigationEntry[];
  followUp?: { dueDate: string; note?: string };
}

export interface Warning {
  type: 'ALLERGY' | 'DUPLICATE_MEDICINE';
  message: string;
  line?: number;
  source?: string;
}

export interface VersionInfo {
  version: number;
  signedAt: string;
  amendmentReason?: string;
  changedFields: string[];
  contentHash: string;
}

export interface Encounter {
  id: string;
  patientId: string;
  status: 'DRAFT' | 'SIGNED' | 'CANCELLED';
  visitType: string;
  doctor: { staffId: number; name: string };
  startedAt: string;
  signedAt?: string;
  draftVersion: number;
  currentVersion: number;
  content: EncounterContent;
  versions: VersionInfo[];
  warnings: Warning[];
}

export interface EncounterListItem {
  id: string;
  visitType: string;
  startedAt: string;
  signedAt?: string;
  doctorName: string;
  currentVersion: number;
  diagnoses: string;
  prescriptionLines: number;
}

export interface AllergyItem {
  id: string;
  category: string;
  substance: string;
  reaction?: string;
  severity?: 'MILD' | 'MODERATE' | 'SEVERE';
  status: string;
  recordedBy: string;
  recordedAt: string;
}

export interface AllergyList {
  reviewed: boolean;
  noKnownAllergies: boolean;
  reviewedAt?: string;
  allergies: AllergyItem[];
}

export interface PatientSummary {
  patient: Patient;
  allergies: AllergyList;
  activeProblems: { problemKey: string; code?: string; display: string; since: string; lastSeen: string }[];
  currentMedicines: {
    medicationKey: string;
    drugDisplay: string;
    genericName?: string;
    dose?: string;
    frequency?: string;
    timing?: string;
    longTerm: boolean;
    startedOn: string;
    endsOn?: string;
    prescribedBy: string;
  }[];
  latestVitals: { code: string; display: string; value: number; unit: string; observedAt: string }[];
  lastVisit?: { encounterId: string; date: string; doctorName: string; diagnoses: string };
}

export interface DiagnosisOption {
  codeSystem: string;
  code: string;
  display: string;
}

export interface DrugOption {
  id: number;
  genericName: string;
  brandName?: string;
  strength?: string;
  form: string;
  display: string;
}

export interface Favourite<T> {
  kind: string;
  ref: string;
  entry: T;
  useCount: number;
  pinned: boolean;
}

export interface Template {
  id: string;
  name: string;
  useCount: number;
}

export interface Problem {
  status: number;
  code?: string;
  detail?: string;
  fields?: Record<string, string>;
  candidates?: DuplicateCandidate[];
  [key: string]: unknown;
}
