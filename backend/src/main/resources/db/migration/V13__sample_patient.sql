-- A practice patient a new clinic can open to try ClinicEMR. Kept out of visit counts; removed with one tap.
ALTER TABLE patient_profile ADD COLUMN sample BOOLEAN NOT NULL DEFAULT false;
