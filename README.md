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

Every call under `/api/v1/clinic/**` needs `Authorization: Bearer <token>` and `X-Clinic-Id: <clinic id>`.
A clinic only sees patients linked to it; lookups never reveal whether a number is registered elsewhere.

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

The ICD-10 and drug lists in `V3__terminology_seed.sql` are a starter set. Have them reviewed by a doctor and
extended before launch. Prescriptions use the PDF's built-in Latin fonts, so Hindi or other Indian-script
names need an embedded font (not done yet).

Next up: patient summary (allergies, active problems, current medicines, vitals trends), doctor templates and
favourites, patient-approved cross-clinic access, patient app, front-end.

## Run locally

Requirements: Java 21, Docker Desktop (for PostgreSQL and MinIO; or point `DB_URL` and `STORAGE_*` at your own).

```bash
docker compose up -d
cd backend
./mvnw spring-boot:run
```

- API docs: http://localhost:8080/swagger-ui.html
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)

### Try it

```bash
curl -s -X POST localhost:8080/api/v1/clinics -H "Content-Type: application/json" -d '{
  "clinic": {"name": "City Care Clinic", "city": "Bengaluru", "state": "Karnataka", "pincode": "560038"},
  "admin":  {"name": "Dr. Asha Mehta", "email": "asha@example.com", "password": "change-me-please",
             "doctor": {"registrationNo": "KMC-12345", "registrationCouncil": "Karnataka Medical Council",
                        "qualifications": "MBBS, MD (General Medicine)"}}
}'

curl -s -X POST localhost:8080/api/v1/auth/staff/login -H "Content-Type: application/json" \
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
| `PORT` | `8080` |
