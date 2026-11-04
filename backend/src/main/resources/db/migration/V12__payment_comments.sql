-- Optional comments: on any payment the clinic records (shown in the history), and the doctor's comment when
-- approving a patient's payment proof (shown to the patient).
ALTER TABLE payment_entry ADD COLUMN comment TEXT;
ALTER TABLE payment_request ADD COLUMN decision_comment TEXT;
