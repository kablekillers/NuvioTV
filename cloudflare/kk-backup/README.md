# Kable Killers backup (Cloudflare Worker)

Keeps a copy of each login's **Profile 1 setup** outside Nuvio, plus a list of every
device signed in to those logins.

- Logins covered: `kablekillers6@gmail.com` and `millertime42085@gmail.com` (`ALLOWED_ACCOUNTS` in `wrangler.toml`).
- What's saved: add-ons (order, names, on/off), plugins, collections, Home rows (order and
  hidden), TV and Mobile settings, and the profile list. Not saved: library, Continue
  Watching, watch progress.
- Nothing is ever written to Nuvio. The Worker only reads, using the sign-in of a device
  that is already signed in.

## How it works

1. After each successful Nuvio sync, the TV and Mobile apps call `POST /v1/sync` with their
   Nuvio sign-in. The Worker confirms the sign-in with Nuvio, reads Profile 1, and saves a copy
   if anything changed. The device gets its own backup key.
2. If Nuvio can't be reached and Profile 1 is empty on a device, the app restores from
   `GET /v1/config` with its backup key. Restores go through the same code as a Nuvio pull,
   so the customer-login rule (household profiles stay local) still holds.
3. A brand-new device while Nuvio is down: Settings → Account → **Set up from backup**, with
   the login email and the restore PIN.

4. QR sign-in while Nuvio is down: if the app can't get a QR from Nuvio, it shows a backup QR
   (`POST /v1/qr/start`). The customer sends it to the owner, who scans it. The `/link/<code>`
   page asks for the owner password once (that phone stays signed in for a year, or until the
   password changes), then shows the device with **Approve device** and a choice of login
   (kablekillers6 by default). The app polls `POST /v1/qr/poll`, gets its device key and restores
   Profile 1 from the backup. A device approved before (or already signed in through Nuvio) goes
   straight through on later QRs. Signing out on the device forgets its key, so getting back in
   always takes a new QR. Blocked devices can't get a QR.

Safety: a copy where everything disappeared is kept but never put in use automatically.
The owner page can lock a known-good copy or switch back to an older one (the last 40 are kept).

## Owner page

`https://<worker>.workers.dev/admin`. The browser asks for a password: any username, and the
`KK_BACKUP_ADMIN_PASSWORD` you set. It shows each login's backup, older copies, and every
device (name, app version, last seen, country) with a Block button.

## Deploying

The GitHub workflow `.github/workflows/kk-backup-deploy.yml` runs the tests and, once these
repository secrets exist, creates the D1 database, applies `schema.sql`, deploys the Worker
and sets its secrets:

| Secret | What it is |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | Cloudflare API token: "Edit Cloudflare Workers" template plus Account → D1 → Edit |
| `CLOUDFLARE_ACCOUNT_ID` | Your Cloudflare account ID |
| `KK_BACKUP_ADMIN_PASSWORD` | Owner page password (8+ characters) |
| `KK_BACKUP_RESTORE_PIN` | Restore PIN for new devices (6+ digits) |

The apps stay off until their `KableKillersBackupConfig.BASE_URL` is set to the Worker URL.

## Tests

```sh
npm test   # Node 22+, uses Node's built-in SQLite with the real schema
```
