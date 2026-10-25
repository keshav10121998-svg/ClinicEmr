-- Staff can sign in with their mobile number instead of email, so a staff mobile number belongs to one account.

-- If two accounts share a number, the older one keeps it; the others can add theirs again
UPDATE staff_user s SET phone_e164 = NULL
 WHERE phone_e164 IS NOT NULL
   AND EXISTS (SELECT 1 FROM staff_user o WHERE o.phone_e164 = s.phone_e164 AND o.id < s.id);

CREATE UNIQUE INDEX ux_staff_phone ON staff_user (phone_e164) WHERE phone_e164 IS NOT NULL;
