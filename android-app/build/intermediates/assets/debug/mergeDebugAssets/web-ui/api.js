
// Runtime-safe API base resolution.
// When loaded over http://127.0.0.1:8787/ui/... this becomes same-origin.
export const API_BASE = (() => {
  try {
    if (location && location.origin && location.origin !== "null") return location.origin;
  } catch (_) {}
  return "http://127.0.0.1:8787";
})();

export async function api(path, opts = {}) {
  const res = await fetch(API_BASE + path, Object.assign({ headers: { "Content-Type": "application/json" } }, opts));
  if (!res.ok) throw new Error(`HTTP ${res.status} ${await res.text()}`);
  const ct = res.headers.get("content-type") || "";
  return ct.includes("application/json") ? res.json() : res.text();
}

export async function postJson(path, body) {
  return api(path, { method: "POST", body: JSON.stringify(body ?? {}) });
}

export function sse(path, onMsg) {
  const es = new EventSource(API_BASE + path);
  es.onmessage = (e) => onMsg?.(e.data);
  es.onerror = () => { /* let it retry */ };
  return es;
}
