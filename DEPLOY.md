# Deploying ClinicEMR for free (Render + Supabase)

Everything runs on free plans, with no card needed:

| Piece | Service | Free plan |
|---|---|---|
| The app (screens + API, one Docker image) | **Render** web service, Singapore | 512 MB, sleeps after 15 min idle (the app pings itself every 14 min) |
| Database (PostgreSQL) | **Supabase**, Mumbai | 500 MB, paused after 7 days with no use at all |
| Reports and photos | **Supabase Storage** (S3-compatible), Mumbai | 1 GB |
| Keep-awake ping | built in (`KEEP_AWAKE=true`) | free |

Patient data stays in Mumbai; only the app server runs in Singapore. When the pilot grows, move the app to a paid
server. Nothing in the app changes, only these settings.

> **Keep secrets out of chat.** Passwords and keys below go straight from Supabase into Render's settings.
> Nobody else needs to see them.

---

## 1. Put the code on GitHub (private)

1. Sign in at **github.com** (create a free account if needed).
2. Go to **github.com/new**: name `clinic-emr`, choose **Private**, and **don't** add a README or .gitignore.
3. Click **Create repository** and copy its address, e.g. `https://github.com/<you>/clinic-emr.git`.
4. Push the code. Run this in the project folder (`C:\Projects\clinic-emr`); a browser window asks you to sign in to GitHub the first time:

   ```bash
   git remote add origin https://github.com/<you>/clinic-emr.git
   git push -u origin master
   ```

## 2. Supabase: database and file storage

1. Sign in at **supabase.com** → **New project**.
   - Name: `clinicemr`
   - **Database password:** click *Generate*, then **copy it somewhere safe**.
   - Region: **South Asia (Mumbai)**
   - Click **Create new project** and wait about 2 minutes.
2. **Database address:** click **Connect** (top of the project page) → **Session pooler**. Note:
   - **Host** (like `aws-0-ap-south-1.pooler.supabase.com`), port **5432**, database **postgres**
   - **User** (like `postgres.abcdxyz`)

   Render needs the address in this form (replace HOST):
   `jdbc:postgresql://HOST:5432/postgres?sslmode=require`
3. **Bucket for files:** left menu **Storage** → **New bucket** → name `clinic-emr-documents` → keep it **Private** (public switch off) → **Create**.
4. **Storage keys:** **Project Settings** → **Storage** → **S3 Connection**:
   - make sure **Enable connection via S3 protocol** is on
   - note the **Endpoint** (like `https://abcdxyz.storage.supabase.co/storage/v1/s3`) and the **Region** (like `ap-south-1`)
   - **New access key** → description `render` → copy the **Access key ID** and **Secret access key**. The secret is shown only once.

## 3. Render: the app

1. Sign in at **render.com** → **New** → **Blueprint**.
2. Connect your GitHub account if asked, and pick the `clinic-emr` repository. Render reads `render.yaml` from it.
3. Fill in the values it asks for:

   | Setting | Value |
   |---|---|
   | `PUBLIC_BASE_URL` | `https://clinic-emr.onrender.com` (if Render gives a different address later, update this) |
   | `DB_URL` | the `jdbc:postgresql://…?sslmode=require` address from step 2.2 |
   | `DB_USER` | the pooler user, `postgres.xxxx` |
   | `DB_PASSWORD` | the database password from step 2.1 |
   | `STORAGE_ENDPOINT` | the S3 endpoint from step 2.4 |
   | `STORAGE_REGION` | the region from step 2.4 |
   | `STORAGE_ACCESS_KEY` | access key ID from step 2.4 |
   | `STORAGE_SECRET_KEY` | secret access key from step 2.4 |

4. Click **Apply**. The first build takes about 10 minutes. In **Logs**, wait for `Started ClinicEmrApplication`.
5. Open the address shown at the top (like `https://clinic-emr.onrender.com`). If it differs from what you put in
   `PUBLIC_BASE_URL`, go to **Environment**, fix it and save; Render restarts the app.

## 4. Keep it awake (built in)

Render's free app sleeps after 15 minutes without visitors, and then takes about a minute to wake. With
`KEEP_AWAKE=true` (already in `render.yaml`) the app visits its own `PUBLIC_BASE_URL/actuator/health` every 14 minutes,
so it never sleeps; that visit also touches the database, so Supabase doesn't pause. **`PUBLIC_BASE_URL` must be the
app's real Render address** for this to work. If you created the service by hand, add `KEEP_AWAKE` = `true` under
**Environment**. In the logs you'll see `Keep-awake on: visiting … every 14 minutes`.

Optional extra safety: a free **cron-job.org** job hitting the same address every 10 minutes also wakes the app
after a Render restart.

Render's free plan includes 750 hours a month, enough for one app awake all the time.

## 5. First use

1. Open the app and choose **Set up your clinic**. Use a **real, strong password**, not the test one from the README.
2. Register a test patient, record a visit, attach a photo, print a prescription, and use **Patient app sign-in code** on your phone.
3. Patients use `https://<your-app>.onrender.com/my`.

## Updating later

Push to GitHub (`git push`); Render rebuilds and redeploys automatically in about 10 minutes.

## Good to know

- **Database pauses:** Supabase pauses a free project after 7 days with no activity. The built-in keep-awake visit
  touches the database every 14 minutes, which prevents it. If it happens, click **Restore** in Supabase.
- **Backups:** the free plan has no automatic backups you can restore yourself. Before real patients rely on it,
  move to a paid database or add a nightly export.
- **SMS:** not needed. Patients sign in with the clinic's QR code and their PIN.
- **Microphone:** works, since Render gives the app HTTPS.
