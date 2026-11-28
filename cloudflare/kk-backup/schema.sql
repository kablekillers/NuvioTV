-- Kable Killers backup database (Cloudflare D1).
-- Safe to run more than once.

-- Every captured copy of an account's Profile 1 setup. Old copies are pruned.
CREATE TABLE IF NOT EXISTS snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_email TEXT NOT NULL,
  nuvio_user_id TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  payload TEXT NOT NULL,
  summary TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  source_device_id TEXT,
  suspicious INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_snapshots_account ON snapshots(account_email, created_at DESC);

-- Which copy devices restore from. "locked" keeps a known-good copy current
-- even while newer copies keep arriving.
CREATE TABLE IF NOT EXISTS current_snapshot (
  account_email TEXT PRIMARY KEY,
  snapshot_id INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_checked_at INTEGER NOT NULL,
  locked INTEGER NOT NULL DEFAULT 0
);

-- Every TV and phone that has checked in. Only a SHA-256 of the device key is stored.
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  account_email TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  name TEXT,
  model TEXT,
  platform TEXT,
  app_version TEXT,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  last_country TEXT,
  last_backup_at INTEGER,
  last_restore_at INTEGER,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_email, last_seen_at DESC);

-- Restore-PIN attempt counters (brute-force protection).
CREATE TABLE IF NOT EXISTS pin_attempts (
  bucket TEXT PRIMARY KEY,
  attempts INTEGER NOT NULL,
  window_start INTEGER NOT NULL
);

-- Backup QR sign-in (for when Nuvio is down). The TV/phone shows a QR, the
-- owner scans it and approves the device once. A device that was approved
-- before (and isn't blocked) goes straight through on later QR scans.
-- status: waiting -> approved -> done (key collected), or denied.
-- device_key is held here only until the TV/phone collects it, then cleared.
CREATE TABLE IF NOT EXISTS qr_logins (
  code TEXT PRIMARY KEY,
  poll_hash TEXT NOT NULL,
  device_id TEXT NOT NULL,
  device_name TEXT,
  device_model TEXT,
  platform TEXT,
  app_version TEXT,
  country TEXT,
  account_email TEXT,
  requester TEXT,
  status TEXT NOT NULL,
  device_key TEXT,
  auto_approved INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  claimed_at INTEGER,
  decided_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_qr_logins_status ON qr_logins(status, created_at DESC);

-- After a Nuvio outage, the device that is sending the outage changes up to Nuvio.
CREATE TABLE IF NOT EXISTS upload_leases (
  account_email TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  snapshot_id INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

-- Built-in VPN: IPVanish WireGuard configs the owner uploaded on /admin.
-- Each device gets its own config (device_id) the first time it asks and keeps it.
CREATE TABLE IF NOT EXISTS vpn_configs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sha256 TEXT NOT NULL UNIQUE,
  config TEXT NOT NULL,
  label TEXT,
  created_at INTEGER NOT NULL,
  device_id TEXT UNIQUE,
  assigned_at INTEGER,
  delivered_at INTEGER
);
