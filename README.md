# Clinic EMR

Cloud system for clinic doctors to record patient visits, diagnoses and treatment, with a patient history
that follows the patient across clinics once they approve. Design and roadmap:
[Clinic EMR & Patient History Plan](https://claude.ai/artifact/NGJ7no2zsLp9N3s8TmWpfv).

## What's built so far

| Area | Endpoints |
|---|---|
| Clinic signup | `POST /api/v1/clinics` (clinic + first admin, optionally also the doctor) |
| Staff login | `POST /api/v1/auth/staff/login` → JWT + clinics and roles |
| Clinic and staff | `GET /api/v1/clinic`, `GET/POST /api/v1/clinic/staff` |
| Patients | `POST /api/v1/clinic/patients` (duplicate check, clinic MRN), `POST /api/v1/clinic/patients/search-by-phone`, `GET /api/v1/clinic/patients?q=`, `GET /api/v1/clinic/patients/{id}` |
| Documents | `POST /api/v1/clinic/patients/{id}/documents` (pre-signed upload URLs), `POST …/{docId}/complete`, `GET …/documents`, `GET …/documents/{docId}` (pre-signed view URLs) |
| Visits | `POST /api/v1/clinic/encounters` (start, client-generated id), `PUT …/{id}` (autosave with `baseVersion`), `POST …/{id}/sign`, `POST …/{id}/amend`, `POST …/{id}/cancel`, `GET …/{id}`, `GET …/{id}/integrity`, `GET /api/v1/clinic/patients/{id}/encounters` |
| Prescription | `GET /api/v1/clinic/encounters/{id}/prescription.pdf?paper=A5\|A4` |
| Terminology | `GET /api/v1/terminology/diagnoses?q=` (ICD-10 + local synonyms), `GET /api/v1/terminology/drugs?q=` |
| Patient summary | `GET /api/v1/clinic/patients/{id}/summary`, `GET …/vitals?code=`, `GET …/last-prescription`, `POST …/problems/resolve`, `POST …/medicines/stop` |
| Allergies | `GET/POST /api/v1/clinic/patients/{id}/allergies`, `POST …/allergies/none-known`, `POST …/allergies/{allergyId}/status` |
| Templates | `GET/POST /api/v1/me/templates`, `PUT/DELETE /api/v1/me/templates/{id}`, `POST /api/v1/clinic/encounters/{id}/apply-template` |
| Favourites | `GET /api/v1/me/favourites?kind=DIAGNOSIS\|DRUG\|INVESTIGATION&q=`, `POST /api/v1/me/favourites/pin` |
| Cross-clinic access (clinic) | `POST /api/v1/clinic/access-requests` (`phone` or `patientId`), `GET …/{id}`, `POST …/{id}/verify`, `POST …/{id}/grant`, `GET /api/v1/clinic/patients/{id}/access`, `GET …/shared-history`, `GET …/shared-history/documents/{docId}` |
| Patient app | `POST /api/v1/auth/patient/otp`, `POST /api/v1/auth/patient/otp/verify`, `GET /api/v1/patient/profiles`, `GET /api/v1/patient/access-requests`, `POST …/{id}/approve`, `POST …/{id}/deny`, `GET /api/v1/patient/access`, `DELETE /api/v1/patient/access/{grantId}`, `GET /api/v1/patient/access-log` |
| Patient records | `GET /api/v1/patient/profiles/{id}/visits`, `GET …/visits/{encounterId}`, `GET …/visits/{encounterId}/prescription.pdf`, `GET/POST …/documents`, `POST …/documents/{docId}/complete`, `GET/DELETE …/documents/{docId}` |

Every call under `/api/v1/clinic/**` needs `Authorization: Bearer <token>` and `X-Clinic-Id: <clinic id>`.
A clinic only sees patients linked to it; lookups never reveal whether a number is registered elsewhere.

### Clinic web app (`frontend/clinic`)

React + TypeScript + Vite. Screens so far:

- **Sign in / set up your clinic**, and a clinic switcher for staff who work at more than one.
- **Patients:** one search box for mobile number, name or patient ID; register with age or date of birth and a
  duplicate warning that offers the existing record.
- **Patient:** header with allergies in red (or "not asked yet"), active problems, current medicines, latest
  vitals, allergy editor, visits at this clinic with print links. Front desk sees the header only.
- **Visit:** complaints, vitals, history, examination, diagnosis and medicine search (doctor's favourites first,
  free text allowed), dose / frequency / timing / days or long-term per line, tests, advice, follow-up shortcuts,
  private note. Autosaves about a second after typing stops (Ctrl+S saves now) and shows allergy and duplicate
  warnings. Templates, repeat last prescription, sign, print A5/A4, and amend with a reason.

The session token is kept in `sessionStorage`, so closing the tab signs out on shared clinic computers.

### How visits work

- **Draft:** the doctor starts a visit; the doctor (or a nurse) autosaves the whole visit body. Each save sends
  the `baseVersion` it last saw; a stale save gets `409 DRAFT_VERSION_CONFLICT`. Drafts aren't validated, so
  half-typed entries are kept.
- **Sign:** only the visit's doctor can sign. Signing validates the visit (at least one diagnosis, complete
  prescription lines, follow-up after the visit date), stores an immutable snapshot with a SHA-256 hash
  (including patient, clinic and doctor details at that moment), and writes the structured rows: notes,
  LOINC-coded vitals, diagnoses, prescription, investigations, follow-up.
- **Amend:** signed visits are never edited. The doctor submits corrected content and a reason; this creates
  version 2, 3… with the list of changed sections. Earlier versions stay in `encounter_version`.
- **Prescription PDF:** rendered from the latest signed snapshot, so reprints match what was signed. Shows the
  amendment notice on amended versions. The doctor's private note is never printed.
- **Integrity:** `GET …/integrity` re-hashes every stored version to detect tampering.

### Patient summary

What the doctor sees first when opening a patient:

- **Allergies**, with three distinct states: not asked yet, "no known allergies", or a list of allergies by
  severity.
- **Active problems:** diagnoses marked *chronic* in signed visits. A doctor can mark one resolved; it comes back
  if it's diagnosed again later.
- **Current medicines:** the latest prescription of each medicine, if it's long-term or its course hasn't ended
  (a 3-day course started on day D runs through D+2, in IST). A doctor can stop a long-term medicine.
- **Latest vitals**, the **last visit**, and up to 30 readings per vital for trend charts.

Problems and medicines are computed from signed visits when read, so amendments show up immediately. For now
only the current clinic's records are used; other clinics' records come with patient-approved access.

While prescribing, each visit response includes **advisory warnings**: medicines that may conflict with a recorded
allergy (including common classes such as penicillins, sulfa drugs, NSAIDs, cephalosporins) and duplicate
medicines. They never block signing. This is a small starter list, not a drug-interaction database.

### Templates and favourites

Both belong to the doctor and follow them across clinics.

- **Templates** ("Viral fever", "Diabetes follow-up") hold complaints, examination, advice, diagnoses, medicines,
  tests and a follow-up interval. Applying one fills empty sections and adds missing diagnoses, medicines and
  tests; it never overwrites what the doctor already typed.
- **Favourites** are learned from every signed visit (with the doctor's usual dose and frequency) and ranked by
  pinned, then use count, then last use.
- **Repeat last prescription** returns the medicines from the patient's latest signed visit at this clinic.

### Cross-clinic history (patient-approved)

Each clinic is the custodian of the records it creates. Another clinic sees them only with the patient's approval:

1. **Request.** Staff enter the patient's phone number (or pick a patient they already have). The patient gets an
   SMS code and, if they use the app, an approval prompt. The response is identical whether or not the number is
   registered anywhere, and shows only the masked number.
2. **Approve.** Either the patient reads the code out at the counter (5 tries, then the request locks; codes last
   10 minutes), or they tap Approve in the app.
3. **Choose.** After approval, staff see the people on that number (name, age, gender) and pick who and what:
   `VISITS` (signed visits and allergies) and/or `DOCUMENTS`, optionally only from a date, for `VISIT` (24 hours),
   `DAYS_30` or `YEAR_1`. A doctor can make the grant **only for themselves**. Someone new to the clinic is linked
   with a new patient number; linking alone shares nothing. In the app flow the patient makes these choices.
4. **Read.** Doctors and nurses see the other clinics' signed visits (latest version, **without the private
   note**), active allergies and documents within the grant. Front desk can't. Every read goes to the access log.
5. **Control.** The patient gets an SMS for every new grant, sees active access and the full access log in the app,
   and can revoke at any time. Revoking takes effect immediately.

Every grant records the consent behind it (method, scope, notice version) for DPDP purposes. Limits: 30 requests
per staff member and 5 per phone number per hour.

The patient app signs in by phone OTP. On first sign-in, every profile registered with that number at any clinic
becomes the account's own.

### Patient app records

For every profile the account manages, the patient sees:

- **Visits from all clinics:** clinic, doctor, date, diagnoses, and the full visit as signed (latest version,
  marked if amended), without the doctor's private note. The **prescription PDF** for any visit can be downloaded.
- **Documents from all clinics and their own uploads.** Patients can upload old reports, prescriptions and
  discharge summaries. These are labelled self-uploaded and never marked verified. Patients can delete their own
  uploads (files are removed from storage) but not documents from a clinic, which are part of that clinic's
  records. A patient's uploads reach a clinic only through a grant that includes `DOCUMENTS`.

Another account asking for a profile it doesn't manage gets `404`, so profiles can't be probed.

### Allergies from other clinics in prescribing warnings

When the patient has shared their **visits** with a clinic, prescribing warnings there also check allergies
recorded at other clinics, labelled "recorded at Clinic A". This uses shared data, so the first such warning on a
visit is written to the patient's access log (once per visit per grant, so autosaves don't flood it). After the
patient revokes access, those allergies are no longer used.

**SMS in development:** with no SMS provider configured, messages (including codes) are written to the
application log, with a warning at startup. Never run like that in production. A DLT-registered provider plugs in
by adding an `SmsSender` bean.

The ICD-10 and drug lists in `V3__terminology_seed.sql` are a starter set. Have them reviewed by a doctor and
extended before launch. Prescriptions use the PDF's built-in Latin fonts, so Hindi or other Indian-script
names need an embedded font (not done yet).

Next up: the front-end (clinic web app, then patient app), shared allergies and medicines in the clinic summary,
a real SMS provider through the outbox with retries, virus scanning of uploads, and deployment to Oracle Cloud.

## Run locally

Requirements: Java 21, Node 18+. Docker Desktop is optional (PostgreSQL and MinIO).

**Quick start without Docker** (embedded PostgreSQL in `backend/target/dev-db`; document uploads need MinIO or Oracle):

```bash
cd backend
./mvnw spring-boot:test-run
```

**With Docker** (PostgreSQL + MinIO, everything works):

```bash
docker compose up -d
cd backend
./mvnw spring-boot:run
```

**Clinic web app** (proxies `/api` to the backend on :18080):

```bash
cd frontend/clinic
npm install
npm run dev
```

Open http://localhost:5173, choose **Set up your clinic**, and sign in.

- API docs: http://localhost:18080/swagger-ui.html
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)

### Try it

```bash
curl -s -X POST localhost:18080/api/v1/clinics -H "Content-Type: application/json" -d '{
  "clinic": {"name": "City Care Clinic", "city": "Bengaluru", "state": "Karnataka", "pincode": "560038"},
  "admin":  {"name": "Dr. Asha Mehta", "email": "asha@example.com", "password": "change-me-please",
             "doctor": {"registrationNo": "KMC-12345", "registrationCouncil": "Karnataka Medical Council",
                        "qualifications": "MBBS, MD (General Medicine)"}}
}'

curl -s -X POST localhost:18080/api/v1/auth/staff/login -H "Content-Type: application/json" \
  -d '{"email": "asha@example.com", "password": "change-me-please"}'
```

## Tests

```bash
cd backend
./mvnw test
```

The API flow test runs against a real PostgreSQL 16 using embedded binaries, so it doesn't need Docker.
Object storage is mocked in tests.

## File storage: Oracle Cloud free tier

The app talks to any S3-compatible store. Locally that's MinIO; in the cloud use **Oracle Object Storage**
(Always Free: 20 GB, with Mumbai and Hyderabad regions).

1. Create an Oracle Cloud account and choose **India West (Mumbai)** or **India South (Hyderabad)** as the
   home region. Always Free resources only exist in the home region, and it can't be changed later.
2. Object Storage → create a **private** bucket, e.g. `clinic-emr-documents`.
3. Note your **Object Storage namespace** (Tenancy details page).
4. Profile → **Customer Secret Keys** → generate a key. This gives an access key and a secret key for the
   S3 compatibility API.
5. Set these environment variables for the backend:

```bash
STORAGE_ENDPOINT=https://<namespace>.compat.objectstorage.ap-mumbai-1.oraclecloud.com
STORAGE_REGION=ap-mumbai-1
STORAGE_BUCKET=clinic-emr-documents
STORAGE_ACCESS_KEY=<customer secret key access key>
STORAGE_SECRET_KEY=<customer secret key secret>
STORAGE_PATH_STYLE=true
```

(Use `ap-hyderabad-1` for Hyderabad.) Browser uploads also need a CORS rule on the bucket for your front-end
origin, set from the Oracle console or CLI.

The free tier has no SLA. Keep a nightly copy of the bucket elsewhere, and move to paid storage once clinics
depend on it.

## Configuration

| Variable | Default (local) |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/clinic_emr`, `clinic`, `clinic` |
| `JWT_SECRET` | dev-only value; **must** be set to a random 32+ byte secret everywhere else |
| `STORAGE_*` | MinIO from docker-compose |
| `PORT` | `18080` |
