-- Each clinic's plan: a free first month from billing_started_on, six months at the launch price, then the standard
-- price, plus a small amount for every staff member. Payments are collected outside the app (UPI) and recorded by the
-- super admin, which moves paid_until forward. Existing clinics start their free month on the day this runs.
ALTER TABLE clinic ADD COLUMN billing_started_on DATE NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Kolkata')::date);
ALTER TABLE clinic ADD COLUMN paid_until DATE;

CREATE TABLE subscription_payment (
  id            BIGSERIAL PRIMARY KEY,
  clinic_id     BIGINT NOT NULL REFERENCES clinic(id),
  amount        INT NOT NULL CHECK (amount >= 0),
  months        INT NOT NULL CHECK (months BETWEEN 1 AND 12),
  covers_from   DATE NOT NULL,
  covers_until  DATE NOT NULL,                  -- paid_until after this payment
  note          TEXT,
  recorded_by   BIGINT REFERENCES platform_admin(id) ON DELETE SET NULL,
  recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscription_payment_clinic ON subscription_payment (clinic_id, recorded_at DESC);
