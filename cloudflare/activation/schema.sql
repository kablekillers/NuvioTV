CREATE TABLE IF NOT EXISTS activation_challenges (
  id TEXT PRIMARY KEY,
  poll_secret_hash TEXT NOT NULL,
  pairing_code TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK (state IN ('pending', 'approved', 'denied', 'consumed')),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  approved_at INTEGER,
  consumed_at INTEGER,
  device_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_activation_expiry ON activation_challenges(expires_at);

CREATE TABLE IF NOT EXISTS registered_devices (
  id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL,
  display_name TEXT,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_household ON registered_devices(household_id);
