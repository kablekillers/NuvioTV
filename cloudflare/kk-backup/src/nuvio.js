// Reads an account's Profile 1 setup from Nuvio's cloud, using the access token
// of a device that is signed in to that account. Nothing is ever written to Nuvio.

export class NuvioUnauthorized extends Error {}
export class NuvioUnavailable extends Error {}

const HOME_CATALOG_PLATFORM = "home_catalog_shared";

function baseUrl(env) {
  const url = (env.SUPABASE_URL || "").trim().replace(/\/+$/, "");
  if (!/^https:\/\/[^/\s]+$/.test(url) || !env.SUPABASE_ANON_KEY) {
    throw new NuvioUnavailable("Backup is missing its Nuvio connection settings");
  }
  return url;
}

async function call(env, token, path, { method = "GET", body } = {}) {
  let response;
  try {
    response = await fetch(baseUrl(env) + path, {
      method,
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    throw new NuvioUnavailable(`Nuvio unreachable: ${error.message}`);
  }
  if (response.status === 401) throw new NuvioUnauthorized("Nuvio rejected the sign-in");
  if (!response.ok) {
    const error = new NuvioUnavailable(`Nuvio ${path.split("?")[0]} returned HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/** Confirms the token with Nuvio and returns the signed-in account. */
export async function verifyNuvioUser(env, token) {
  if (typeof token !== "string" || token.length < 20 || token.length > 8192) {
    throw new NuvioUnauthorized("Missing Nuvio sign-in");
  }
  const user = await call(env, token, "/auth/v1/user");
  const email = typeof user?.email === "string" ? user.email.trim().toLowerCase() : "";
  if (!user?.id || !email) throw new NuvioUnauthorized("Nuvio account has no email");
  return { id: String(user.id), email };
}

const rpc = (env, token, name, body = {}) =>
  call(env, token, `/rest/v1/rpc/${name}`, { method: "POST", body });

// A surface Nuvio doesn't offer (old server, feature removed) is recorded as null
// instead of failing the whole backup. Outages still fail the whole capture, so a
// half-read copy is never stored.
async function optional(promise) {
  try {
    return await promise;
  } catch (error) {
    if (error instanceof NuvioUnavailable && error.status >= 400 && error.status < 500) return null;
    throw error;
  }
}

/**
 * Captures everything created profiles copy from Profile 1, plus the profile list.
 * Returned rows are exactly what Nuvio returns, so the apps can restore them with
 * the same code they use for a normal Nuvio pull.
 */
export async function captureProfileOne(env, token, user) {
  const owner = await rpc(env, token, "get_sync_owner").catch((error) => {
    if (error instanceof NuvioUnavailable && error.status >= 400 && error.status < 500) return user.id;
    throw error;
  });
  const ownerId = typeof owner === "string" && owner ? owner : user.id;
  const rowsFilter = `select=*&user_id=eq.${encodeURIComponent(ownerId)}&profile_id=eq.1&order=sort_order.asc`;

  const [addons, plugins, collections, homeCatalog, settingsTv, settingsMobile, profiles] = await Promise.all([
    call(env, token, `/rest/v1/addons?${rowsFilter}`),
    optional(call(env, token, `/rest/v1/plugins?${rowsFilter}`)),
    rpc(env, token, "sync_pull_collections", { p_profile_id: 1 }),
    rpc(env, token, "sync_pull_home_catalog_settings", { p_profile_id: 1, p_platform: HOME_CATALOG_PLATFORM }),
    optional(rpc(env, token, "sync_pull_profile_settings_blob", { p_profile_id: 1, p_platform: "tv" })),
    optional(rpc(env, token, "sync_pull_profile_settings_blob", { p_profile_id: 1, p_platform: "mobile" })),
    rpc(env, token, "sync_pull_profiles"),
  ]);

  return {
    owner_user_id: ownerId,
    surfaces: {
      addons: Array.isArray(addons) ? addons : [],
      plugins: Array.isArray(plugins) ? plugins : null,
      collections: Array.isArray(collections) ? collections : [],
      home_catalog_settings: Array.isArray(homeCatalog) ? homeCatalog : [],
      profile_settings_tv: Array.isArray(settingsTv) ? settingsTv : null,
      profile_settings_mobile: Array.isArray(settingsMobile) ? settingsMobile : null,
      profiles: Array.isArray(profiles) ? profiles : [],
    },
  };
}

/** Short counts shown on the owner page and used to spot a wiped account. */
export function summarize(surfaces) {
  const firstJson = (rows, key) => (Array.isArray(rows) && rows[0] ? rows[0][key] : null);
  const collections = firstJson(surfaces.collections, "collections_json");
  const home = firstJson(surfaces.home_catalog_settings, "settings_json");
  return {
    addons: surfaces.addons?.length ?? 0,
    plugins: surfaces.plugins?.length ?? 0,
    collections: Array.isArray(collections) ? collections.length : 0,
    home_rows: Array.isArray(home?.items) ? home.items.length : 0,
    profiles: surfaces.profiles?.length ?? 0,
    tv_settings: Boolean(firstJson(surfaces.profile_settings_tv, "settings_json")),
    mobile_settings: Boolean(firstJson(surfaces.profile_settings_mobile, "settings_json")),
  };
}

/** A copy that lost everything the previous one had looks like a wipe, not an edit. */
export function looksWiped(next, previous) {
  if (!previous) return false;
  const lostAddons = previous.addons > 0 && next.addons === 0;
  const lostRows = previous.home_rows > 0 && next.home_rows === 0;
  const lostProfiles = previous.profiles > 0 && next.profiles === 0;
  return lostAddons || (lostRows && next.addons === 0) || lostProfiles;
}
