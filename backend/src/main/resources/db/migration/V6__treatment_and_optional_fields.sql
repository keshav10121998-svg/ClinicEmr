-- Treatment given during the visit (injection, dressing, nebulisation, procedure…), optional.
ALTER TABLE encounter_note ADD COLUMN treatment_given TEXT;

-- Only the medicine's name is required on a prescription line; dose, frequency and duration are optional.
ALTER TABLE prescription_item ALTER COLUMN frequency DROP NOT NULL;
