# Clinic EMR

Cloud system for clinic doctors to record patient visits, diagnoses and treatment, with a patient history
that follows the patient across clinics once they approve. Design and roadmap:
[Clinic EMR & Patient History Plan](https://claude.ai/artifact/NGJ7no2zsLp9N3s8TmWpfv).

## What's built so far

| Area | Endpoints |
|---|---|
| Clinic signup | `POST /api/v1/clinics` (clinic + first admin, optionally also the doctor) |
| Staff login | `POST /api/v1/auth/staff/login` with `username` = email or 10-digit mobile → JWT + clinics and roles; admins set a staff mobile with `PUT /api/v1/clinic/staff/{id}/phone` |
| Clinic and staff | `GET /api/v1/clinic`, `GET/POST /api/v1/clinic/staff` |
| Patients | `POST /api/v1/clinic/patients` (duplicate check, clinic MRN), `POST /api/v1/clinic/patients/search-by-phone`, `GET /api/v1/clinic/patients?q=`, `GET /api/v1/clinic/patients/recent?limit=`, `GET /api/v1/clinic/patients/{id}` |
| Visit numbers | `GET /api/v1/clinic/visit-stats` (patients and visits today, yesterday, last 7 days, this year, all time; India time), `GET /api/v1/clinic/visit-stats/visitors?range=today\|yesterday\|last_7_days\|this_year\|all` (who came, newest first, up to 500; diagnoses for doctors and nurses only) |
| Payments | `GET/PUT /api/v1/clinic/encounters/{id}/payment` (paid, balance, dueDate), `POST …/payment/settle`, `POST …/payment/receive` (amount), `POST /api/v1/clinic/patients/{id}/payments/receive` (amount or full; latest visit first), `GET …/payments/history`, `GET /api/v1/clinic/payments/due?when=now\|all&mine=&patientId=` |
| Earnings | `GET /api/v1/clinic/earnings?mine=` (today, this month, this year, all time), `GET …/earnings/owing?range=today\|this_month\|this_year\|all&mine=` |
| Payment proofs | Patient: `GET /api/v1/patient/profiles/{id}/balances`, `POST …/payment-requests` (multipart: clinicId, amount, note, proof), `GET …/payment-requests`. Clinic: `GET /api/v1/clinic/payment-requests?status=&patientId=`, `POST …/{id}/approve`, `POST …/{id}/reject` (reason) |
| Documents | `POST /api/v1/clinic/patients/{id}/documents` (pre-signed upload URLs), `POST …/{docId}/complete`, `GET …/documents?encounterId=`, `GET …/documents/{docId}` (pre-signed view URLs); attach to a visit with `encounterId` |
| Visits | `POST /api/v1/clinic/encounters` (start, client-generated id), `PUT …/{id}` (autosave with `baseVersion`), `POST …/{id}/sign`, `POST …/{id}/amend`, `POST …/{id}/cancel`, `GET …/{id}`, `GET …/{id}/integrity`, `GET /api/v1/clinic/patients/{id}/encounters` |
| Prescription | `GET /api/v1/clinic/encounters/{id}/prescription.pdf?paper=A5\|A4` |
| Terminology | `GET /api/v1/terminology/diagnoses?q=` (ICD-10 + local synonyms), `GET /api/v1/terminology/drugs?q=`, `GET …/diagnoses/all`, `GET …/drugs/all` (whole lists for suggestions) |
| Patient summary | `GET /api/v1/clinic/patients/{id}/summary`, `GET …/vitals?code=`, `GET …/last-prescription`, `POST …/problems/resolve`, `POST …/medicines/stop` |
| Allergies | `GET/POST /api/v1/clinic/patients/{id}/allergies`, `POST …/allergies/none-known`, `POST …/allergies/{allergyId}/status` |
| Templates | `GET/POST /api/v1/me/templates`, `PUT/DELETE /api/v1/me/templates/{id}`, `POST /api/v1/clinic/encounters/{id}/apply-template` |
| Favourites | `GET /api/v1/me/favourites?kind=DIAGNOSIS\|DRUG\|INVESTIGATION&q=`, `POST /api/v1/me/favourites/pin` |
| Cross-clinic access (clinic) | `POST /api/v1/clinic/access-requests` (`phone` or `patientId`), `GET …/{id}`, `POST …/{id}/verify`, `POST …/{id}/grant`, `GET /api/v1/clinic/patients/{id}/access`, `GET …/shared-history`, `GET …/shared-history/documents/{docId}` |
| Patient app | `POST /api/v1/auth/patient/pin` (mobile + PIN), `POST /api/v1/auth/patient/code/verify` (mobile + counter code), `POST /api/v1/auth/patient/otp/verify` (code from the QR or SMS), `POST /api/v1/auth/patient/otp` (SMS, needs a provider), `GET /api/v1/patient/account`, `POST /api/v1/patient/pin`, `POST /api/v1/patient/session/renew`, `POST /api/v1/clinic/patients/{id}/app-sign-in-code` (staff), `GET /api/v1/patient/profiles`, `GET /api/v1/patient/access-requests`, `POST …/{id}/approve`, `POST …/{id}/deny`, `GET /api/v1/patient/access`, `DELETE /api/v1/patient/access/{grantId}`, `GET /api/v1/patient/access-log` |
| Patient records | `GET /api/v1/patient/profiles/{id}/visits`, `GET …/visits/{encounterId}`, `GET …/visits/{encounterId}/prescription.pdf`, `GET/POST …/documents`, `POST …/documents/{docId}/complete`, `GET/DELETE …/documents/{docId}` |

Every call under `/api/v1/clinic/**` needs `Authorization: Bearer <token>` and `X-Clinic-Id: <clinic id>`.
A clinic only sees patients linked to it; lookups never reveal whether a number is registered elsewhere.

### Web app (`frontend`): clinic screens

One React + TypeScript + Vite app for clinic staff and patients, served by the backend. The sign-in page has
a *Clinic staff* / *Patient / family* switch. Staff screens live at `/`; patient screens at `/my`. Each side keeps its
own sign-in and its own look.

Most clinics run on a phone, so the clinic screens are built phone-first. Under 760px wide they get a bottom tab bar,
a round **+** button for a new patient, Visits / Health / Files tabs on a patient, visit cards instead of a table,
and the main action (*Start visit*, *Finish visit*) pinned within thumb reach. Wider screens keep the two-column
layout. Clinic screens so far:

- **Sign in / set up your clinic** with email or 10-digit mobile number and password, and a clinic switcher for staff
  who work at more than one. A staff mobile number belongs to one account; admins add or change it on the Staff page.
  Staff stay signed in when the app or browser is closed, until they tap **Sign out**: the sign-in lasts 30 days
  (`STAFF_SESSION_TTL`) and renews each time the app opens. Suspended staff are signed out on their next request.
- **Mobile numbers** everywhere (patients, staff, clinic, patient sign-in) are exactly 10 digits starting with 6–9.
  The boxes take digits only, stop at 10, clean up pasted `+91 …` numbers, and say how many digits are missing; the
  server checks the same rule.
- **Payments:** each visit has Paid + Balance = Total (whole rupees). A balance needs a due date (in 3 / 7 / 15 / 30 days
  or a date). From that day the visit's doctor sees it under **Money to collect** at the top of Overview, with a red
  count on the Overview tab; admins and front desk see the whole clinic's. **Balance received** moves it into Paid. A
  patient's page shows what they still owe. Any staff member can record payments; nothing about money is printed or
  shown in the patient app. On a patient's visit list each visit shows **Paid ₹X** or **₹X due · date** with a box for
  the amount received now (never more than what's due; the rest stays due) and **Full ₹X**, or **+ Add payment**. Paid and Balance also
  have a mic: say "three hundred fifty" or "₹1,200". A patient's page has a **Payments** card: Total / Paid / Balance across
  visits, an amount box that can't go above the total balance (taken off the visits latest first), **Fully paid**,
  and **Payment history**: every amount received, when, for which visit and by whom (a split payment is shown once).
- **Payment proofs from patients:** in the patient app's Visits tab a patient sees what they owe each clinic and taps
  **I've paid, send proof**: amount (never more than they owe, counting proofs still waiting), a screenshot or PDF
  (checked to really be an image/PDF, up to 8 MB) and an optional UPI reference. The clinic sees **Payment requests**
  on Overview (counted on the tab) and on the patient's page, with **View proof**. A doctor or admin **Approves**
  (recorded like Received, latest visit first, and added to the history) or **Rejects** with a reason the patient sees.
- **Comments (optional):** every way of recording money (the visit's Payment card, the visit-list amount box, the
  patient's Payments card) has a Comment box; the comment is saved with that payment and shown in Payment history.
  Approving a patient's proof asks for an optional comment, which the patient sees in the app.
- **Preview, not download:** on the clinic side, **View proof** and **Open** on attached files show the file inside the
  app: photos at full width, PDF pages drawn inside the page with pdf.js (all pages of a document), with **Open in
  new tab** as a fallback. Chrome on Android has no PDF viewer and would download a PDF straight away, so nothing is
  shown in a frame.
- **Voice typing everywhere:** every text box (search, patient registration, visit notes, medicines, the patient
  app…) shows a mic at its right end while it has focus. Tap it and speak: the words are added to what is already
  there, phone and number boxes get just the digits, and long boxes keep listening until tapped again. English
  (India) or Hindi, switched from the "Listening…" bubble and remembered. It uses the browser's own speech
  recognition (Chrome sends the audio to Google to turn it into text), needs the internet, and is hidden in browsers
  without it. Passwords, e-mail and date boxes have no mic; add `data-no-mic` to leave out others.
- **Print shows the prescription first:** **Print** opens the prescription full screen inside the app (pdf.js,
  loaded only when first needed), with **Print**, **Download** and, where the phone supports it, **Share** underneath.
  Print sends just the pages at the PDF's own paper size (A5 or A4). The patient app opens prescriptions and
  documents (PDFs and photos) the same way.
- **Earnings:** a tab with four cards (today, this month, this year, all time), each showing the total charged for
  the visits in that period, how much of it was paid and the balance still owed (total = paid + balance), plus the
  money actually received in the period when that differs (old balances paid now). Tap a balance to list the
  patients who still owe for that period, the longest overdue first, with Call and Open. Doctors see their own
  visits; admins and front desk see the whole clinic.
  Nurses don't see it. The sample patient, cancelled visits and removed patients don't count.
- **Overview:** how many patients came today (large tile), yesterday, in the last 7 days, this year and in total, with
  the visit count when someone came more than once. Tap a tile to see who came: name, age, patient ID, doctor,
  time, total visits so far, and the diagnosis for doctors and nurses. Tap a name to open the patient. Unfinished
  visits show as *In progress*; cancelled ones are not counted.
- **Patients:** the clinic's patients, most recently seen first, with each one's number of visits; tap one to open.
  Typing only narrows the list. A patient's page shows total visits, first and last visit, then every visit.
  Registering asks for name, sex, age and mobile; date of birth, ABHA and blood group are optional extras. A
  duplicate warning offers the existing record.
- **Getting started:** until a clinic has three patients, Patients shows **Start in 3 steps** (add a patient, start
  a visit, finish visit) and **Try with a sample patient**: "Sunita Sharma (sample)" with one finished visit
  (vitals, diagnosis, two medicines, a test, advice, follow-up). The sample has no mobile number, so it never
  reaches the patient app or other clinics, it is left out of Overview counts, and **Remove sample patient** on its
  page hides it.
- **Installs like an app:** both sides are installable web apps (`manifest.json` for clinic staff,
  `manifest-my.json` "My health" for patients, plus `sw.js`). A banner offers **Install** on Android/desktop
  Chrome, and step-by-step help on iPhone and inside WhatsApp's browser. It shows on the sign-in pages and the home
  screens until the app is installed; **Not now** hides it only until the app is opened again, and closing Chrome's
  install box switches it to the menu steps. With no internet the app shows a "No internet connection" page. Only
  the patients list, Overview and Earnings are kept on the phone (for instant display), and Sign out wipes them.
- **Patient:** header with allergies in red (or "not asked yet"), active problems, current medicines, latest
  vitals, allergy editor, visits at this clinic with print links, and reports and files (attach a photo or PDF).
  Front desk sees the header only.
- **Visit:** a short form where every field is optional: complaints, diagnosis, treatment given, medicines (name,
  how often, when, days or ongoing), advice, next visit, and optional photo or PDF attachments linked to the visit.
  Diagnosis and medicine boxes suggest as you type (doctor's favourites first) but accept any text. Vitals,
  history, examination, tests, private note and templates sit under *More details*. Autosaves about a second after
  typing stops (Ctrl+S saves now) and shows allergy and duplicate warnings. Same as last time, finish, print
  A5/A4, and correct with a reason. Works on phones (form first, summary below) as well as desktops.
- **Speak instead of typing:** a mic beside complaints, diagnosis, treatment, advice, history, examination, the private
  note and each medicine name. Tap it, speak (English, India), tap *Stop*. Words appear as you talk and are added
  to what is already there. A spoken diagnosis or medicine is matched to the suggestion list (favourites first), so
  "paracetamol" fills the doctor's usual dose. Uses the browser's own speech recognition (Chrome, Edge, Android,
  iPhone Safari); where there is none the mic is hidden. The microphone only works over HTTPS (or localhost). Note
  that Chrome sends the audio to Google's speech service to turn it into text; Safari uses Apple's.

- **Staff** (admins): list staff and add doctors (with registration details), nurses, front desk and admins.
  Someone who already uses ClinicEMR at another clinic joins by email and keeps their sign-in.
- **Records from other clinics:**
  - From **Patients**, a phone search with no match offers *Ask patient to share records*; from a patient's page,
    *Ask patient to share*.
  - The patient reads out the SMS code (or approves in the app, which the screen picks up automatically).
  - Staff then choose the person on that number, what to share (visits / documents), for how long, an optional
    start date, and for doctors *only me*. A person new to the clinic gets a patient number here.
  - The patient page then shows how long sharing lasts, allergies recorded elsewhere, other clinics' visits
    (expandable, no private notes) and documents. Front desk can request but not view.
- Prescribing warnings at the new clinic include allergies recorded at other clinics while visits are shared.

The session token is kept in `sessionStorage`, so closing the tab signs out on shared clinic computers.

**Trying the sharing flow locally:** with no SMS provider configured, codes are written to the backend log
(`backend/target/dev-server.log` when started as in the run instructions, look for `[DEV SMS`).

### Super admin (`/admin`)

The platform owner's own sign-in, separate from clinic staff and patients.

- **Created on first start** from `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_PASSWORD` (12+ characters; see DEPLOY.md).
  Once a super admin exists the settings are ignored. The password is never in the code. Five wrong passwords lock
  the sign-in for 15 minutes; a sign-in lasts 12 hours.
- **Totals:** clinics, doctors (and all staff accounts), patients, patient app users, and visits today / last 7 days /
  in total, with new clinics and patients this week. Practice (sample) patients are never counted.
- **Doctors and staff:** everyone who signs in to a clinic, with their clinics, visits, patients and last visit;
  search, "doctors only" and "show removed". A doctor's page lists their clinics and their latest 300 visits.
- **Remove a visit** (with an optional reason) or **remove a doctor** (optionally with all their visits). A clinic left
  with nobody in it is removed too, with the patients registered only there. Removed doctors can't sign in, and
  their email and mobile number can be registered again. Nothing is erased: rows keep when and why they were removed,
  the app hides them, and every removal is in the audit log.
- **Permanently delete** (second step, only for something already removed): erases a visit with its notes,
  medicines, payments and attached files; or a doctor's account with their removed clinics and everything in them
  (patients registered only there, visits, payments, documents and stored files). A doctor is typed-confirmed
  (DELETE). It is refused while the doctor still has visits at a clinic in use. Files are deleted from storage only
  after the database change commits; the audit log keeps a note.
- Local dev server: `admin@example.com` / `change-me-admin-please` (only when `SUPER_ADMIN_EMAIL` isn't set).

### Web app: patient screens (`/my`)

Phone-first screens for patients and families, in the same app under `/my`:

- **Sign in, free (no SMS):**
  - *First time, or a forgotten PIN:* at the clinic, staff tap **Patient app sign-in code** on the patient's page.
    A QR and a 6-digit code appear (one use, 15 minutes). The patient scans the QR with their phone camera, or types
    their mobile number and the code under *I have a code from the clinic*.
  - Either way they must choose a 4–6 digit PIN in the same step; they aren't signed in until it is saved (easy
    ones like 1111 or 1234 are refused, without using up the code). A forgotten PIN is replaced the same way.
  - *At home:* mobile number + PIN. Five wrong PINs lock it for 15 minutes. A wrong PIN and an unknown number get
    the same answer, so the page doesn't reveal who uses the app.
  - The phone stays signed in for 90 days and renews each time the app opens. Changing the PIN signs out every
    other phone. *Change PIN* is in the header.
  - SMS codes still work once an SMS provider is set up (`SmsSender`).
- **Family switcher:** every profile linked to the number (registered at any clinic, or managed as caregiver).
- **Visits:** every signed visit from every clinic, newest first. The detail page shows diagnoses, medicines in
  plain words ("for 7 days", "continue until the doctor says"), tests and next visit, marks visits the doctor
  updated, and shows the prescription inside the app with Print, Download and Share. Doctors' private notes never appear.
- **Documents:** clinic documents (marked "From <clinic>") and the family's own uploads (marked "Added by you").
  Upload photos or PDFs (several pages per document) (checked by the backend before storing); open inside the app (with Print, Download and Share); delete own uploads after a
  confirmation step.
- **Sharing:** requests from clinics appear at the top of every tab, checked every 10 seconds while the app is
  open. The patient picks whose records, what (visits / documents) and for how long, or declines. The tab also
  lists who can see records now (with *Stop sharing*) and who looked, in plain words.

Not done yet: push notifications for requests, profile management (add a family member, invite a caregiver),
Hindi and regional-language UI. Uploads abandoned midway stay as unfinished rows until a cleanup job is added.

### How visits work

- **Draft:** the doctor starts a visit; the doctor (or a nurse) autosaves the whole visit body. Each save sends
  the `baseVersion` it last saw; a stale save gets `409 DRAFT_VERSION_CONFLICT`. Drafts aren't validated, so
  half-typed entries are kept.
- **Everything is optional:** diagnosis, treatment given, medicines and every other section. A visit can be
  just a dressing, just a prescription, or a full consultation. A medicine line needs only the medicine's name;
  dose, frequency, timing and duration are optional. The only rule is that a visit can't be signed empty.
- **Sign:** only the visit's doctor can sign. Signing checks the visit isn't empty and the follow-up is after
  the visit date, stores an immutable snapshot with a SHA-256 hash (including patient, clinic and doctor details
  at that moment), and writes the structured rows: notes, treatment given, LOINC-coded vitals, diagnoses,
  prescription, investigations, follow-up.
- **Visit history:** every visit keeps its visit time. The patient page shows the **last visit** (date and time,
  doctor, diagnosis, treatment given, each medicine with dose, frequency and duration) and a history table with
  the same for every visit. The patient app and shared history show visit time and treatment too.
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

Next up: shared allergies and medicines in the clinic summary,
a real SMS provider through the outbox with retries, virus scanning of uploads, and deployment to Oracle Cloud.

## Deploy for free

Step by step in [DEPLOY.md](DEPLOY.md): the app on Render's free plan (from the root `Dockerfile` and `render.yaml`),
the database and file storage on Supabase (Mumbai), and a built-in self-ping (`KEEP_AWAKE=true`) to keep it awake. Files are then served through
the backend (`STORAGE_DOWNLOAD_MODE=proxy`), and the database pool stays small (`DB_POOL_SIZE=5`).

### Speed on the free plan

On the free plan the server has a tenth of a CPU and sits in Singapore while the database is in Mumbai, so each
database round trip costs time. The app is built around that:

- Responses are gzip-compressed (`server.compression`): the app's script goes over the network as about 115 KB
  instead of 390 KB. Scripts have long-lived cache headers, so a phone downloads them once per release.
- Earnings and Overview fetch all their periods in one query each, instead of one query per period.
- The patients list, Overview and Earnings show the copy kept on the phone straight away and refresh it from the
  server in the background (`clinic/cache.ts`). The copy is kept per clinic and wiped on Sign out or when another
  staff member signs in on the same phone.

The biggest further gain is hosting, not code: a paid Render plan (more CPU), or running the app server in Mumbai
next to the database.

## Run locally

Requirements: Java 21, Node 18+. Docker Desktop is optional (PostgreSQL and MinIO).

**Quick start without Docker** (embedded PostgreSQL in `backend/target/dev-db`, documents on disk in
`backend/target/dev-storage`; everything works, including uploads):

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

**Web app, while developing** (hot reload; proxies `/api` to the backend on :18080):

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. For a clinic, choose **Set up your clinic** and sign in. For a patient, tap
**Patient / family**. To sign a patient in, open them in the clinic screens, tap **Patient app sign-in code**, and
scan the QR or type their mobile number and the code, choosing a PIN in the same step. Next time, sign in with
mobile + PIN.

**One server for everything** (how it is deployed): `mvnw package` builds the web app and packs it into the
backend jar, so the backend serves the screens and the API from one address:

```bash
cd backend
./mvnw package
java -jar target/clinic-emr-backend-0.1.0-SNAPSHOT.jar
```

Open http://localhost:18080 (clinic) or http://localhost:18080/my (patients). After a `package`, the
`spring-boot:test-run` quick start serves the same build too. Add `-Dweb.skip=true` to package the API alone.

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
Object storage is mocked in the API tests; the disk store has its own unit tests (signing, expiry, path safety).

## File storage

### How uploads and downloads work

- **Uploads go through the backend** (`STORAGE_UPLOAD_MODE=proxy`, the default). The app starts an upload, PUTs
  each page to the backend, which checks the size (20 MB per file) and the file's real type from its first bytes
  (PDF, JPEG, PNG, WebP, HEIC; anything else is refused), then writes it to storage. The bucket needs **no CORS
  rules**, and nothing unchecked reaches it.
- **Downloads use short-lived signed links** (5 minutes) issued after the permission check. The app opens them
  in a new tab, so CORS doesn't apply there either.
- `STORAGE_UPLOAD_MODE=direct` hands browsers pre-signed PUT URLs instead (S3 only). It skips the content check
  and needs a CORS rule on the bucket for the front-end origins. Use it only if upload traffic through the
  backend becomes a problem.

### Choose a store (`STORAGE_TYPE`)

| Store | When | Setup |
|---|---|---|
| `local` | Development | Nothing. The dev server (`spring-boot:test-run`) uses it by default: files go to `backend/target/dev-storage` and download links are signed by the backend. Not for production. |
| `s3` + MinIO | Development with Docker | `docker compose up -d`, then `./mvnw spring-boot:run` (defaults point at MinIO). To use MinIO with the dev server, set `STORAGE_TYPE=s3`. |
| `s3` + Oracle | Cloud (free tier) | See below. |

### Oracle Cloud Object Storage (Always Free: 20 GB, Mumbai and Hyderabad regions)

You do steps 1–4 in the Oracle console; creating the account and keys has to be done by you.

1. Create an Oracle Cloud account and choose **India West (Mumbai)** or **India South (Hyderabad)** as the
   home region. Always Free resources only exist in the home region, and it can't be changed later.
2. **Storage → Buckets → Create bucket**: name `clinic-emr-documents`, *Standard* tier, visibility
   **Private** (the default). Leave encryption on Oracle-managed keys.
3. **Tenancy details**: note the **Object Storage namespace** (a short random string).
4. **Profile → Customer secret keys → Generate secret key**. Copy the secret right away (it's shown once); the
   access key appears in the list. These are the S3-compatible credentials. For a real deployment, create them
   for a dedicated user whose group can only manage objects in this bucket, not for your admin user.
5. Start the backend with these environment variables (never commit them):

```bash
STORAGE_TYPE=s3
STORAGE_ENDPOINT=https://<namespace>.compat.objectstorage.ap-mumbai-1.oraclecloud.com
STORAGE_REGION=ap-mumbai-1
STORAGE_BUCKET=clinic-emr-documents
STORAGE_ACCESS_KEY=<access key>
STORAGE_SECRET_KEY=<secret key>
STORAGE_PATH_STYLE=true
```

Use `ap-hyderabad-1` in both places for Hyderabad. No CORS setup is needed with the default upload mode.
To check the connection, upload a document from the patient app and confirm it appears in the bucket under
`documents/<id>/1.pdf`.

The free tier has no SLA. Keep a nightly copy of the bucket elsewhere, and move to paid storage once clinics
depend on it.

## Configuration

| Variable | Default (local) |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/clinic_emr`, `clinic`, `clinic` |
| `JWT_SECRET` | dev-only value; **must** be set to a random 32+ byte secret everywhere else |
| `STORAGE_TYPE` | `s3` (the dev server switches to `local` unless set) |
| `STORAGE_UPLOAD_MODE` | `proxy` |
| `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE` | MinIO from docker-compose |
| `STORAGE_LOCAL_DIR`, `STORAGE_LOCAL_BASE_URL` | `target/dev-storage`, `http://localhost:18080` |
| `PORT` | `18080` |
