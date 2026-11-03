-- What the patient paid for a visit and what is still to collect. Total = paid + balance (whole rupees).
-- A balance has a due date; on and after that day the visit's doctor sees it in "Money to collect".
CREATE TABLE visit_payment (
  encounter_id  UUID PRIMARY KEY REFERENCES encounter(id),
  clinic_id     BIGINT NOT NULL REFERENCES clinic(id),
  profile_id    BIGINT NOT NULL REFERENCES patient_profile(id),
  paid          INT NOT NULL DEFAULT 0 CHECK (paid >= 0),
  balance       INT NOT NULL DEFAULT 0 CHECK (balance >= 0),
  due_date      DATE,
  settled_at    TIMESTAMPTZ,                       -- when the balance was received
  recorded_by   BIGINT NOT NULL REFERENCES staff_user(id),
  updated_by    BIGINT NOT NULL REFERENCES staff_user(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (balance = 0 OR due_date IS NOT NULL)
);
CREATE INDEX ix_payment_due ON visit_payment (clinic_id, due_date) WHERE balance > 0;
CREATE INDEX ix_payment_profile ON visit_payment (profile_id) WHERE balance > 0;
