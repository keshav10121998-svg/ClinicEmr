-- A patient says they paid (by UPI, bank transfer...) and sends a screenshot as proof. The clinic's doctor approves
-- it, which records the payment like "Received" (latest visit first), or rejects it with a reason.
CREATE TABLE payment_request (
  id               UUID PRIMARY KEY,
  clinic_id        BIGINT NOT NULL REFERENCES clinic(id),
  profile_id       BIGINT NOT NULL REFERENCES patient_profile(id),
  account_id       BIGINT NOT NULL REFERENCES account(id),
  amount           INT NOT NULL CHECK (amount > 0),
  note             TEXT,
  proof_key        TEXT NOT NULL,
  proof_type       TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  approved_amount  INT,
  reject_reason    TEXT,
  decided_by       BIGINT REFERENCES staff_user(id),
  decided_at       TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_request_clinic ON payment_request (clinic_id, status, created_at DESC);
CREATE INDEX ix_payment_request_profile ON payment_request (profile_id, created_at DESC);
