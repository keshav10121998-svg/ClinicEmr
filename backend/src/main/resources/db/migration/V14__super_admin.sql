-- The platform owner's own sign-in (not a clinic role): sees every clinic, doctor and visit, and removes test ones.
CREATE TABLE platform_admin (
  id              BIGSERIAL PRIMARY KEY,
  email           TEXT NOT NULL UNIQUE,
  name            TEXT NOT NULL,
  password_hash   TEXT NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until    TIMESTAMPTZ,
  last_login_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Removed by the super admin. Rows stay for the record; the app treats them as gone.
ALTER TABLE staff_user ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE staff_user ADD COLUMN deleted_email TEXT;   -- the email it had, since email is freed for reuse
ALTER TABLE clinic ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE encounter ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE encounter ADD COLUMN deleted_reason TEXT;
