(function (global) {
  const API_BASE = (() => {
    try {
      if (location && location.origin && location.origin !== "null") {
        const origin = String(location.origin);
        if (!origin.startsWith("file:")) return origin;
      }
    } catch (_) {}
    return "http://127.0.0.1:8787";
  })();

  function resolveApiUrl(url) {
    if (typeof url !== "string") return url;
    if (url.startsWith("/")) return API_BASE + url;
    return url;
  }

  // Local engine auth token:
  // - Android app appends ?token=... when loading the UI.
  // - Persist in localStorage so subsequent navigations still work.
  const token = (() => {
    try {
      const u = new URL(String(location.href));
      const t = (u.searchParams.get("token") || u.searchParams.get("t") || "").trim();
      if (t) {
        try {
          localStorage.setItem("nn_token", t);
        } catch (_) {}
        try {
          u.searchParams.delete("token");
          u.searchParams.delete("t");
          history.replaceState(null, "", u.toString());
        } catch (_) {}
        return t;
      }
    } catch (_) {}
    try {
      return (localStorage.getItem("nn_token") || "").trim();
    } catch (_) {}
    return "";
  })();

  async function fetchJson(url, options) {
    const opts = options || {};
    const headers = {
      Accept: "application/json",
      ...(opts.headers || {}),
    };
    if (token) headers.Authorization = `Bearer ${token}`;

    const { timeoutMs, ...fetchOptions } = opts;
    const controller = new AbortController();
    const timeoutBudget = Number.isFinite(timeoutMs) ? timeoutMs : 8000;
    const timeout = timeoutBudget > 0 ? setTimeout(() => controller.abort(), timeoutBudget) : null;

    const res = await fetch(resolveApiUrl(url), { ...fetchOptions, headers, signal: controller.signal });
    if (timeout) clearTimeout(timeout);

    if (res.status === 401) {
      if (typeof global.showToast === "function") {
        global.showToast("Unauthorized", "Access rejected by the local engine.");
      }
      throw new Error("Unauthorized");
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  async function postJson(url, body, options) {
    return fetchJson(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...((options || {}).headers || {}) },
      body: JSON.stringify(body),
      ...(options || {}),
    });
  }

  const api = { baseUrl: API_BASE, token, resolveApiUrl, fetchJson, postJson };
  global.NetNinjaApi = api;
  global.fetchJson = global.fetchJson || fetchJson;
  global.resolveApiUrl = global.resolveApiUrl || resolveApiUrl;
  global.NN_TOKEN = token;
})(window);
