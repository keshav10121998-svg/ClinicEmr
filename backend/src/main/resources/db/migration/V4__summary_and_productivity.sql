-- Allergies, status overrides for the computed summary, and doctor productivity (templates, favourites).
--
-- Active problems and current medicines are computed from signed visits at read time, so amendments are
-- reflected automatically. These tables only record a doctor's decisions on top of that:
-- a problem marked resolved, or a long-term medicine stopped.

CREATE TABLE allergy (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id        BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id         BIGINT NOT NULL REFERENCES clinic(id),
  encounter_id      UUID REFERENCES encounter(id),
  category          TEXT NOT NULL DEFAULT 'DRUG' CHECK (category IN ('DRUG','FOOD','ENVIRONMENT','OTHER')),
  substance         TEXT NOT NULL,                      -- e.g. "Penicillin", "Sulfa drugs", "Peanuts"
  reaction          TEXT,
  severity          TEXT CHECK (severity IN ('MILD','MODERATE','SEVERE')),
  status            TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','ENTERED_IN_ERROR')),
  status_reason     TEXT,
  recorded_by       BIGINT NOT NULL REFERENCES staff_user(id),
  recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_allergy_profile ON allergy(profile_id, clinic_id) WHERE status = 'ACTIVE';

-- "Allergies asked about": records either "no known allergies" or that the list was reviewed
CREATE TABLE allergy_review (
  profile_id         BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id          BIGINT NOT NULL REFERENCES clinic(id),
  no_known_allergies BOOLEAN NOT NULL,
  reviewed_by        BIGINT NOT NULL REFERENCES staff_user(id),
  reviewed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (profile_id, clinic_id)
);

CREATE TABLE problem_status (
  profile_id  BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id   BIGINT NOT NULL REFERENCES clinic(id),
  problem_key TEXT NOT NULL,                            -- diagnosis code, or lower-cased display when uncoded
  status      TEXT NOT NULL CHECK (status IN ('RESOLVED')),
  changed_by  BIGINT NOT NULL REFERENCES staff_user(id),
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (profile_id, clinic_id, problem_key)
);

CREATE TABLE medication_stop (
  profile_id     BIGINT NOT NULL REFERENCES patient_profile(id),
  clinic_id      BIGINT NOT NULL REFERENCES clinic(id),
  medication_key TEXT NOT NULL,                         -- lower-cased generic name, or drug display
  reason         TEXT,
  stopped_by     BIGINT NOT NULL REFERENCES staff_user(id),
  stopped_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (profile_id, clinic_id, medication_key)
);

CREATE INDEX ix_rx_encounter ON prescription(encounter_id);

-- ===== doctor productivity =====
CREATE TABLE doctor_template (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  doctor_id  BIGINT NOT NULL REFERENCES doctor(staff_id),
  name       TEXT NOT NULL,
  content    JSONB NOT NULL,                            -- partial visit content
  use_count  INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_template_name ON doctor_template(doctor_id, lower(name));

-- Learned from signed visits (use_count) and optionally pinned by the doctor
CREATE TABLE doctor_favourite (
  doctor_id BIGINT NOT NULL REFERENCES doctor(staff_id),
  kind      TEXT NOT NULL CHECK (kind IN ('DIAGNOSIS','DRUG','INVESTIGATION')),
  ref       TEXT NOT NULL,                              -- code, drug id, or lower-cased display
  payload   JSONB NOT NULL,                             -- last used entry (e.g. dose, frequency, duration)
  use_count INT NOT NULL DEFAULT 0,
  pinned    BOOLEAN NOT NULL DEFAULT false,
  last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (doctor_id, kind, ref)
);
CREATE INDEX ix_favourite_rank ON doctor_favourite(doctor_id, kind, pinned DESC, use_count DESC);
