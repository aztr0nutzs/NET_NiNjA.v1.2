const API_BASE = location.origin;

function getToken() {
  try {
    return localStorage.getItem("nn_token") || "";
  } catch {
    return "";
  }
}

function authHeaders(extra = {}) {
  const token = getToken();
  const headers = Object.assign({ "Content-Type": "application/json" }, extra);

  if (token && token !== "local") {
    headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
}

export async function api(path, opts = {}) {
  const headers = authHeaders(opts.headers || {});
  const res = await fetch(API_BASE + path, Object.assign({}, opts, { headers }));

  let text = "";
  try { text = await res.text(); } catch {}

  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }

  if (!res.ok) {
    throw new Error(
      `API ${path} failed (${res.status}): ${
        typeof data === "string" ? data : JSON.stringify(data)
      }`
    );
  }
  return { ok: true, status: res.status, data };
}

export async function postJson(path, body) {
  return api(path, { method: "POST", body: JSON.stringify(body || {}) });
}

// EventSource can't set headers. Pass token via ?token=...
export function sse(path, onMsg) {
  const token = getToken();
  const url = new URL(API_BASE + path);
  if (token && token !== "local") {
    url.searchParams.set("token", token);
  }

  const es = new EventSource(url.toString());
  es.onmessage = (evt) => {
    try { onMsg(evt.data); } catch (err) { console.error("SSE handler error:", err); }
  };
  es.onerror = (err) => {
    console.warn("SSE connection issue:", err);
  };
  return es;
}
