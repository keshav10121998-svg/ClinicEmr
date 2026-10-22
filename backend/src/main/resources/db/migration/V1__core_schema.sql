-- Core schema: identity, clinics, patients, documents, cross-cutting.
-- Clinical tables (encounters, prescriptions, summaries) arrive in later migrations.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ===== identity =====
CREATE TABLE account (
  id            BIGSERIAL PRIMARY KEY,
  phone_e164    TEXT NOT NULL UNIQUE,
  last_login_at TIMESTAMPTZ,
  status        TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at    TIMESTAMPTZ
);

CREATE TABLE staff_user (
  id            BIGSERIAL PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  phone_e164    TEXT,
  name          TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== clinics =====
CREATE TABLE clinic (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT NOT NULL,
  hfr_id      TEXT UNIQUE,
  address     TEXT,
  city        TEXT,
  state       TEXT,
  pincode     TEXT,
  phone       TEXT,
  letterhead  JSONB NOT NULL DEFAULT '{}',
  settings    JSONB NOT NULL DEFAULT '{}',
  next_mrn    INT NOT NULL DEFAULT 1,
  verified_at TIMESTAMPTZ,
  status      TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','SUSPENDED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clinic_member (
  clinic_id BIGINT NOT NULL REFERENCES clinic(id),
  staff_id  BIGINT NOT NULL REFERENCES staff_user(id),
  role      TEXT NOT NULL CHECK (role IN ('ADMIN','FRONT_DESK','NURSE','DOCTOR')),
  PRIMARY KEY (clinic_id, staff_id, role)
);
CREATE INDEX ix_clinic_member_staff ON clinic_member(staff_id);

CREATE TABLE doctor (
  staff_id             BIGINT PRIMARY KEY REFERENCES staff_user(id),
  registration_no      TEXT NOT NULL,
  registration_council TEXT NOT NULL,
  qualifications       TEXT NOT NULL,
  specialty            TEXT,
  hpr_id               TEXT UNIQUE,
  signature_key        TEXT,
  verified_at          TIMESTAMPTZ
);

-- ===== patients =====
CREATE TABLE patient_profile (
  id                 BIGSERIAL PRIMARY KEY,
  public_id          UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  name               TEXT NOT NULL,
  dob                DATE,
  dob_estimated      BOOLEAN NOT NULL DEFAULT false,
  gender             TEXT NOT NULL CHECK (gender IN ('M','F','O','U')),
  blood_group        TEXT,
  abha_number        TEXT UNIQUE,
  primary_phone_e164 TEXT,
  claimed            BOOLEAN NOT NULL DEFAULT false,
  merged_into_id     BIGINT REFERENCES patient_profile(id),
  created_by         TEXT NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at         TIMESTAMPTZ
);
CREATE INDEX ix_profile_phone ON patient_profile(primary_phone_e164);
CREATE INDEX ix_profile_name_trgm ON patient_profile USING gin (name gin_trgm_ops);

CREATE TABLE profile_access (
  account_id BIGINT NOT NULL REFERENCES account(id),
  profile_id BIGINT NOT NULL REFERENCES patient_profile(id),
  role       TEXT NOT NULL CHECK (role IN ('OWNER','CAREGIVER')),
  relation   TEXT NOT NULL DEFAULT 'SELF',
  added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, profile_id)
);
CREATE INDEX ix_profile_access_profile ON profile_access(profile_id);

-- Links a profile to a clinic (the clinic is custodian of records it creates) with the clinic's own number
CREATE TABLE patient_identifier (
  profile_id BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id  BIGINT NOT NULL REFERENCES clinic(id),
  mrn        TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (profile_id, clinic_id),
  UNIQUE (clinic_id, mrn)
);

-- ===== documents =====
CREATE TABLE medical_document (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id   BIGINT NOT NULL REFERENCES patient_profile(id),
  source       TEXT NOT NULL CHECK (source IN ('PATIENT','CLINIC','ABDM')),
  clinic_id    BIGINT REFERENCES clinic(id),
  encounter_id UUID,
  uploaded_by  TEXT NOT NULL,
  doc_type     TEXT NOT NULL CHECK (doc_type IN
                 ('PRESCRIPTION','LAB_REPORT','IMAGING','DISCHARGE_SUMMARY','VACCINATION','BILL','INSURANCE','OTHER')),
  title        TEXT,
  record_date  DATE,
  verified     BOOLEAN NOT NULL DEFAULT false,
  status       TEXT NOT NULL DEFAULT 'UPLOADING'
                 CHECK (status IN ('UPLOADING','SCANNING','READY','REJECTED','DELETED')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at   TIMESTAMPTZ
);
CREATE INDEX ix_doc_gallery ON medical_document(profile_id, record_date DESC NULLS LAST) WHERE status = 'READY';

CREATE TABLE document_file (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id UUID NOT NULL REFERENCES medical_document(id),
  page_no     SMALLINT NOT NULL DEFAULT 1,
  storage_key TEXT NOT NULL,
  thumb_key   TEXT,
  mime_type   TEXT NOT NULL,
  size_bytes  BIGINT,
  sha256      TEXT,
  scan_result TEXT,
  UNIQUE (document_id, page_no)
);

-- ===== cross-cutting =====
CREATE TABLE outbox_event (
  id              BIGSERIAL PRIMARY KEY,
  topic           TEXT NOT NULL,
  aggregate_id    TEXT NOT NULL,
  payload         JSONB NOT NULL,
  status          TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','DONE','DEAD')),
  attempts        INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_due ON outbox_event(next_attempt_at) WHERE status = 'NEW';

CREATE TABLE idempotency_record (
  scope         TEXT NOT NULL,
  key           TEXT NOT NULL,
  request_hash  TEXT NOT NULL,
  response_code INT,
  response_body JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (scope, key)
);

-- Plain table for now; switch to monthly partitions once volume grows
CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_type  TEXT NOT NULL,
  actor_id    TEXT,
  clinic_id   BIGINT,
  action      TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   TEXT NOT NULL,
  details     JSONB
);
CREATE INDEX ix_audit_entity ON audit_log(entity_type, entity_id, at DESC);
CREATE INDEX ix_audit_clinic ON audit_log(clinic_id, at DESC);
