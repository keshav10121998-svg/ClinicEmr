-- Every amount a patient paid, so balances can be traced: when, how much, for which visit, recorded by whom.
-- One patient-level payment split across several visits shares a batch id. Corrections are negative entries.
CREATE TABLE payment_entry (
  id            BIGSERIAL PRIMARY KEY,
  encounter_id  UUID NOT NULL REFERENCES encounter(id),
  clinic_id     BIGINT NOT NULL REFERENCES clinic(id),
  profile_id    BIGINT NOT NULL REFERENCES patient_profile(id),
  amount        INT NOT NULL CHECK (amount <> 0),
  kind          TEXT NOT NULL CHECK (kind IN ('AT_VISIT', 'BALANCE', 'CORRECTION')),
  batch_id      UUID,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  recorded_by   BIGINT NOT NULL REFERENCES staff_user(id)
);
CREATE INDEX ix_payment_entry_profile ON payment_entry (clinic_id, profile_id, received_at DESC);

-- Payments recorded before this history existed appear as one entry each, at the time they were first saved
INSERT INTO payment_entry (encounter_id, clinic_id, profile_id, amount, kind, received_at, recorded_by)
SELECT encounter_id, clinic_id, profile_id, paid, 'AT_VISIT', created_at, recorded_by
  FROM visit_payment WHERE paid > 0;
