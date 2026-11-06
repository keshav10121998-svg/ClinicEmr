-- Each clinic gets access from the super admin, agreed with the clinic in person: a number of months, free or at an
-- agreed monthly price. The clinic can use the app while today is on or before paid_until. After that (or if it was
-- never switched on, paid_until is empty) the clinic can still view its records but cannot add or change anything.
-- This replaces the automatic plan (free month, launch price, per-staff charge) from V16.
ALTER TABLE clinic ADD COLUMN monthly_price INT NOT NULL DEFAULT 0 CHECK (monthly_price >= 0);   -- 0 = free
ALTER TABLE clinic DROP COLUMN billing_started_on;

-- Clinics that exist today get 30 days, so nobody is locked out on the day this goes live; set each one after talking to it
UPDATE clinic SET paid_until = ((now() AT TIME ZONE 'Asia/Kolkata')::date + 30);

ALTER TABLE subscription_payment ADD COLUMN monthly_price INT NOT NULL DEFAULT 0;
