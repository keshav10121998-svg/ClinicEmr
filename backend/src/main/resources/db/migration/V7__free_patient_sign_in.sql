-- Patient sign-in without paid SMS: a one-time code shown at the clinic counter, then a PIN for signing in at home.

-- A code staff show on screen for the patient to scan or type. Same table and checks as SMS codes.
ALTER TABLE otp_challenge DROP CONSTRAINT otp_challenge_purpose_check;
ALTER TABLE otp_challenge ADD CONSTRAINT otp_challenge_purpose_check CHECK (purpose IN ('PATIENT_LOGIN', 'COUNTER_LOGIN'));
ALTER TABLE otp_challenge ADD COLUMN issued_by_staff BIGINT REFERENCES staff_user(id);
ALTER TABLE otp_challenge ADD COLUMN clinic_id BIGINT REFERENCES clinic(id);

ALTER TABLE account ADD COLUMN pin_hash TEXT;
ALTER TABLE account ADD COLUMN pin_set_at TIMESTAMPTZ;
ALTER TABLE account ADD COLUMN pin_failures SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE account ADD COLUMN pin_locked_until TIMESTAMPTZ;
-- Bumped when the PIN changes: every older sign-in on other phones stops working
ALTER TABLE account ADD COLUMN session_version INT NOT NULL DEFAULT 0;
