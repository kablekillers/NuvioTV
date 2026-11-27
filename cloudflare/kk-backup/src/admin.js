// Owner page: backup status for each login, older copies, and every device.
// Protected by the ADMIN_PASSWORD secret (browser password prompt; any username).

import {
  getCurrent, getSnapshot, listDevices, listSnapshots, makeCurrent, originOf, setDeviceBlocked, setLocked,
} from "./store.js";
import { allowedAccounts, hmacHex, isDeviceId, normalizeEmail, now, safeEqual } from "./util.js";
import { cancelVpn, looksLikeWireGuard, pendingVpn, queueVpn, vpnStatus } from "./vpn.js";

const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => (
  { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
));

function ago(seconds, at = now()) {
  if (!seconds) return "never";
  const diff = Math.max(0, at - seconds);
  if (diff < 90) return "just now";
  if (diff < 3600) return `${Math.round(diff / 60)} min ago`;
  if (diff < 172800) return `${Math.round(diff / 3600)} h ago`;
  return `${Math.round(diff / 86400)} days ago`;
}

const iso = (seconds) => (seconds ? new Date(seconds * 1000).toISOString() : "");

function unauthorized() {
  return new Response("Password required", {
    status: 401,
    headers: { "WWW-Authenticate": 'Basic realm="Kable Killers backup", charset="UTF-8"', "Cache-Control": "no-store" },
  });
}

function passwordFrom(request) {
  const header = request.headers.get("Authorization") || "";
  const match = /^Basic\s+(.+)$/i.exec(header);
  if (!match) return null;
  try {
    const decoded = atob(match[1]);
    return decoded.slice(decoded.indexOf(":") + 1);
  } catch {
    return null;
  }
}

const csrfToken = (env) => hmacHex(env.ADMIN_PASSWORD, "kk-backup-admin-form");

function describe(summary) {
  const s = typeof summary === "string" ? JSON.parse(summary) : summary;
  return `${s.addons} add-ons · ${s.plugins} plugins · ${s.home_rows} Home rows · ${s.collections} collections · ${s.profiles} profiles`;
}

function page(body) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Kable Killers backup</title>
<style>
:root{color-scheme:dark;--bg:#0b0b0d;--card:#151518;--line:#2a2a30;--text:#f2f0ea;--muted:#a19d93;--gold:#d4af37;--bad:#e5484d;--ok:#46a758}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:980px;margin:0 auto;padding:20px 16px 60px}h1{font-size:22px;margin:0 0 4px}h1 span{color:var(--gold)}
h2{font-size:17px;margin:28px 0 10px;color:var(--gold)}.muted{color:var(--muted)}.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin:10px 0}
.row{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center;justify-content:space-between}.pill{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;border:1px solid var(--line)}
.pill.ok{color:var(--ok);border-color:var(--ok)}.pill.bad{color:var(--bad);border-color:var(--bad)}.pill.gold{color:var(--gold);border-color:var(--gold)}
button,.btn{background:transparent;color:var(--gold);border:1px solid var(--gold);border-radius:8px;padding:6px 12px;font:inherit;font-size:13px;cursor:pointer;text-decoration:none}
button.danger{color:var(--bad);border-color:var(--bad)}form{display:inline}table{width:100%;border-collapse:collapse;font-size:14px}
td,th{padding:8px 6px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{color:var(--muted);font-weight:500;font-size:12px;text-transform:uppercase;letter-spacing:.04em}
@media(max-width:640px){table,tbody,tr,td{display:block}thead{display:none}tr{border-bottom:1px solid var(--line);padding:8px 0}td{border:0;padding:2px 0}}
</style></head><body><main>${body}</main></body></html>`, {
    headers: {
      "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Frame-Options": "DENY",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
      "Referrer-Policy": "no-referrer",
    },
  });
}

function actionForm(csrf, fields, label, danger = false) {
  const inputs = Object.entries({ ...fields, csrf })
    .map(([k, v]) => `<input type="hidden" name="${escapeHtml(k)}" value="${escapeHtml(v)}">`).join("");
  return `<form method="post" action="/admin/action">${inputs}<button${danger ? ' class="danger"' : ""}>${escapeHtml(label)}</button></form>`;
}

/** VPN status for one device, plus Send / Remove (IPVanish WireGuard .conf). */
function vpnCell(csrf, d, waiting, state) {
  let status = '<span class="muted">None</span>';
  if (state?.has_profile) status = state.is_on ? '<span class="pill ok">On</span>' : '<span class="pill">Off (customer)</span>';
  if (waiting) {
    status += ` <span class="pill gold">${waiting.action === "remove" ? "Removal" : "Profile"} waiting for the device</span> ` +
      actionForm(csrf, { action: "vpn_cancel", device: d.id }, "Cancel");
  }
  if (d.platform !== "tv") return status;
  return `${status}<details style="margin-top:6px"><summary>Send VPN</summary>
    <form method="post" action="/admin/action" enctype="multipart/form-data" style="display:block;margin-top:6px">
      <input type="hidden" name="action" value="vpn_send"><input type="hidden" name="device" value="${escapeHtml(d.id)}">
      <input type="hidden" name="csrf" value="${escapeHtml(csrf)}">
      <input type="file" name="file" accept=".conf,text/plain" style="display:block;margin:4px 0">
      <textarea name="conf" rows="4" placeholder="…or paste the WireGuard .conf here" style="width:100%;background:#0b0b0d;color:#f2f0ea;border:1px solid #2a2a30;border-radius:8px;font:12px monospace"></textarea>
      <button style="margin-top:6px">Send VPN to this TV</button>
    </form></details>
    ${state?.has_profile ? actionForm(csrf, { action: "vpn_remove", device: d.id }, "Remove VPN", true) : ""}`;
}

async function renderDashboard(env) {
  const csrf = await csrfToken(env);
  const accounts = allowedAccounts(env);
  const devices = await listDevices(env.DB);
  const vpnWaiting = await pendingVpn(env.DB);
  const vpnState = await vpnStatus(env.DB);
  let html = `<h1>Kable Killers <span>backup</span></h1><p class="muted">Copies of each login's Profile 1 setup, kept outside Nuvio. Devices restore from the copy marked "in use".</p>`;

  for (const email of accounts) {
    const current = await getCurrent(env.DB, email);
    const snapshots = await listSnapshots(env.DB, email);
    const accountDevices = devices.filter((d) => d.account_email === email);
    html += `<h2>${escapeHtml(email)}</h2><div class="card">`;
    if (!current) {
      html += `<p class="muted">No copy yet. One is saved the first time a device signed in to this login opens the app.</p>`;
    } else {
      html += `<div class="row"><div><strong>In use:</strong> saved ${ago(current.created_at)}
        <span class="muted" title="${iso(current.last_checked_at)}">· last checked against Nuvio ${ago(current.last_checked_at)}</span><br>
        <span class="muted">${escapeHtml(describe(current.summary))}</span>
        ${originOf(current) === "device" ? '<br><span class="pill gold" title="Profile 1 was changed on a device while Nuvio was down. It goes back up to Nuvio from the first device on Profile 1 once Nuvio is back.">Changed while Nuvio was down</span>' : ""}</div><div>
        ${current.locked ? `<span class="pill gold">Locked</span> ${actionForm(csrf, { action: "unlock", account: email }, "Unlock")}`
          : actionForm(csrf, { action: "lock", account: email }, "Lock this copy")}</div></div>
        <p class="muted" style="font-size:13px;margin:8px 0 0">Locking keeps this copy in use even if Nuvio changes. New copies are still saved below.</p>`;
    }
    html += `</div>`;

    if (snapshots.length) {
      html += `<details class="card"><summary>Older copies (${snapshots.length})</summary><table><thead><tr><th>Saved</th><th>Contents</th><th></th></tr></thead><tbody>`;
      for (const s of snapshots) {
        const inUse = current && current.snapshot_id === s.id;
        html += `<tr><td title="${iso(s.created_at)}">${ago(s.created_at)}${inUse ? ' <span class="pill ok">In use</span>' : ""}
          ${s.suspicious ? ' <span class="pill bad" title="Everything disappeared compared with the copy before it, so it was not put in use automatically.">Looked wiped</span>' : ""}</td>
          <td class="muted">${escapeHtml(describe(s.summary))}</td>
          <td>${inUse ? "" : actionForm(csrf, { action: "use", account: email, id: s.id }, "Use this copy")}
          <a class="btn" href="/admin/snapshot?account=${encodeURIComponent(email)}&id=${s.id}">Download</a></td></tr>`;
      }
      html += `</tbody></table></details>`;
    }

    html += `<div class="card"><strong>Devices (${accountDevices.length})</strong>`;
    if (!accountDevices.length) {
      html += `<p class="muted">None yet.</p>`;
    } else {
      html += `<table><thead><tr><th>Device</th><th>App</th><th>Last seen</th><th>VPN</th><th></th></tr></thead><tbody>`;
      for (const d of accountDevices) {
        html += `<tr><td>${escapeHtml(d.name || d.model || "Unnamed device")}<br><span class="muted" style="font-size:12px">${escapeHtml(d.model || "")} · ${escapeHtml(d.id.slice(0, 8))}</span></td>
          <td>${escapeHtml(d.platform === "tv" ? "TV" : d.platform === "mobile" ? "Mobile" : d.platform || "")} ${escapeHtml(d.app_version || "")}</td>
          <td title="${iso(d.last_seen_at)}">${ago(d.last_seen_at)}${d.last_country ? ` <span class="muted">(${escapeHtml(d.last_country)})</span>` : ""}
          ${d.last_restore_at ? `<br><span class="muted" style="font-size:12px">restored ${ago(d.last_restore_at)}</span>` : ""}</td>
          <td>${vpnCell(csrf, d, vpnWaiting.get(d.id), vpnState.get(d.id))}</td>
          <td>${d.revoked_at ? `<span class="pill bad">Blocked</span> ${actionForm(csrf, { action: "unblock", device: d.id }, "Allow")}`
            : actionForm(csrf, { action: "block", device: d.id }, "Block", true)}</td></tr>`;
      }
      html += `</tbody></table>`;
    }
    html += `</div>`;
  }
  return page(html);
}

async function handleAction(request, env, url) {
  const origin = request.headers.get("Origin");
  if (origin && origin !== url.origin) return new Response("Forbidden", { status: 403 });
  const form = await request.formData();
  if (!safeEqual(String(form.get("csrf") || ""), await csrfToken(env))) return new Response("Forbidden", { status: 403 });

  const action = String(form.get("action") || "");
  const email = normalizeEmail(form.get("account"));
  const deviceId = String(form.get("device") || "");
  const accountOk = allowedAccounts(env).includes(email);

  if (action === "vpn_send" && isDeviceId(deviceId)) {
    const file = form.get("file");
    let conf = String(form.get("conf") || "").trim();
    if (!conf && file && typeof file.text === "function") conf = (await file.text()).trim();
    if (!looksLikeWireGuard(conf)) {
      return new Response("That isn't a WireGuard .conf file (it needs [Interface] with PrivateKey and Address, and a [Peer] with PublicKey and Endpoint). Go back and try again.", { status: 400 });
    }
    await queueVpn(env.DB, env, deviceId, "set", conf);
  } else if (action === "vpn_remove" && isDeviceId(deviceId)) {
    await queueVpn(env.DB, env, deviceId, "remove");
  } else if (action === "vpn_cancel" && isDeviceId(deviceId)) {
    await cancelVpn(env.DB, deviceId);
  } else if ((action === "block" || action === "unblock") && isDeviceId(deviceId)) {
    await setDeviceBlocked(env.DB, deviceId, action === "block");
  } else if ((action === "lock" || action === "unlock") && accountOk) {
    await setLocked(env.DB, email, action === "lock");
  } else if (action === "use" && accountOk) {
    await makeCurrent(env.DB, email, Number(form.get("id")));
  } else {
    return new Response("Unknown action", { status: 400 });
  }
  return new Response(null, { status: 303, headers: { Location: "/admin", "Cache-Control": "no-store" } });
}

export async function handleAdmin(request, env, url) {
  if (!env.ADMIN_PASSWORD || String(env.ADMIN_PASSWORD).length < 8) {
    return new Response("Owner page is turned off until ADMIN_PASSWORD is set.", { status: 503 });
  }
  if (!safeEqual(passwordFrom(request) ?? "", String(env.ADMIN_PASSWORD))) return unauthorized();

  if (request.method === "GET" && url.pathname === "/admin") return renderDashboard(env);
  if (request.method === "POST" && url.pathname === "/admin/action") return handleAction(request, env, url);
  if (request.method === "GET" && url.pathname === "/admin/snapshot") {
    const email = normalizeEmail(url.searchParams.get("account"));
    const snapshot = allowedAccounts(env).includes(email)
      ? await getSnapshot(env.DB, email, Number(url.searchParams.get("id"))) : null;
    if (!snapshot) return new Response("Not found", { status: 404 });
    return new Response(snapshot.payload, {
      headers: {
        "Content-Type": "application/json", "Cache-Control": "no-store",
        "Content-Disposition": `attachment; filename="kk-backup-${email.split("@")[0]}-${snapshot.id}.json"`,
      },
    });
  }
  return new Response("Not found", { status: 404 });
}
