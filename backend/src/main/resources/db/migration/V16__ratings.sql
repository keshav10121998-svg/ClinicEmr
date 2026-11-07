-- Patients rate a visit (and so the clinic and the doctor) from the patient app, once per visit, and can change it.
-- Clinics rate ClinicEMR itself, once per clinic, optionally allowing the feedback to be shown publicly.
CREATE TABLE patient_rating (
  encounter_id  UUID PRIMARY KEY REFERENCES encounter(id),
  clinic_id     BIGINT NOT NULL REFERENCES clinic(id),
  doctor_id     BIGINT NOT NULL REFERENCES staff_user(id),
  profile_id    BIGINT NOT NULL REFERENCES patient_profile(id),
  account_id    BIGINT NOT NULL REFERENCES account(id),
  stars         SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
  comment       TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_patient_rating_clinic ON patient_rating (clinic_id, created_at DESC);
CREATE INDEX ix_patient_rating_doctor ON patient_rating (doctor_id);

CREATE TABLE app_rating (
  clinic_id     BIGINT PRIMARY KEY REFERENCES clinic(id),
  staff_id      BIGINT REFERENCES staff_user(id) ON DELETE SET NULL,
  stars         SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
  comment       TEXT,
  allow_public  BOOLEAN NOT NULL DEFAULT false,   -- the clinic agrees to its feedback and name being shown publicly
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
