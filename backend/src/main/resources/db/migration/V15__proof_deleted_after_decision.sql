-- A payment proof (screenshot or PDF) is deleted once the doctor approves or rejects the request: it is only needed
-- to decide. The request itself (amount, who decided, comment) stays in the history.
ALTER TABLE payment_request ALTER COLUMN proof_key DROP NOT NULL;
ALTER TABLE payment_request ALTER COLUMN proof_type DROP NOT NULL;
ALTER TABLE payment_request ADD COLUMN proof_deleted_at TIMESTAMPTZ;

-- Proofs of requests that were decided before this change are removed from storage by the app on startup
