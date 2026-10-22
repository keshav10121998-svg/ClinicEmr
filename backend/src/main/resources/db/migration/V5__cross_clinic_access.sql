-- Patient-approved access to records held by other clinics, and patient OTP login.

CREATE TABLE otp_challenge (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_e164  TEXT NOT NULL,
  purpose     TEXT NOT NULL CHECK (purpose IN ('PATIENT_LOGIN')),
  code_hash   TEXT NOT NULL,
  attempts    SMALLINT NOT NULL DEFAULT 0,
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_phone ON otp_challenge(phone_e164, created_at DESC);

-- A clinic asking a phone number's owner to share records. Approved with the OTP read out at the counter,
-- or in the patient app.
CREATE TABLE access_request (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id    BIGINT NOT NULL REFERENCES clinic(id),
  staff_id     BIGINT NOT NULL REFERENCES staff_user(id),
  phone_e164   TEXT NOT NULL,
  otp_hash     TEXT NOT NULL,
  attempts     SMALLINT NOT NULL DEFAULT 0,
  status       TEXT NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','VERIFIED','APPROVED','DENIED','LOCKED')),
  verified_at  TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ NOT NULL,                 -- 10 min to verify; then 15 min to create grants
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_access_req_staff ON access_request(staff_id, created_at DESC);
CREATE INDEX ix_access_req_phone ON access_request(phone_e164, created_at DESC);

CREATE TABLE consent (
  id             BIGSERIAL PRIMARY KEY,
  phone_e164     TEXT NOT NULL,                      -- the number that approved
  account_id     BIGINT REFERENCES account(id),      -- set when approved in the app
  profile_id     BIGINT NOT NULL REFERENCES patient_profile(id),
  purpose        TEXT NOT NULL CHECK (purpose IN ('CLINIC_HISTORY_ACCESS')),
  method         TEXT NOT NULL CHECK (method IN ('OTP','APP')),
  details        JSONB NOT NULL,
  notice_version TEXT NOT NULL,
  granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  withdrawn_at   TIMESTAMPTZ
);

CREATE TABLE record_access_grant (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id        BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id         BIGINT NOT NULL REFERENCES clinic(id),
  doctor_id         BIGINT REFERENCES doctor(staff_id),  -- NULL = any doctor or nurse of the clinic
  scope_types       TEXT[] NOT NULL,                     -- VISITS, DOCUMENTS
  scope_from_date   DATE,                                -- NULL = full history
  consent_id        BIGINT NOT NULL REFERENCES consent(id),
  access_request_id UUID REFERENCES access_request(id),
  expires_at        TIMESTAMPTZ NOT NULL,
  revoked_at        TIMESTAMPTZ,
  revoked_by        TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_grant_active ON record_access_grant(clinic_id, profile_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_grant_profile ON record_access_grant(profile_id) WHERE revoked_at IS NULL;

-- Every look at shared records, visible to the patient
CREATE TABLE access_log (
  id           BIGSERIAL PRIMARY KEY,
  profile_id   BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id    BIGINT REFERENCES clinic(id),
  actor_type   TEXT NOT NULL,
  actor_id     TEXT NOT NULL,
  action       TEXT NOT NULL,
  entity_type  TEXT,
  entity_id    TEXT,
  via_grant_id UUID,
  at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_access_log_profile ON access_log(profile_id, at DESC);
