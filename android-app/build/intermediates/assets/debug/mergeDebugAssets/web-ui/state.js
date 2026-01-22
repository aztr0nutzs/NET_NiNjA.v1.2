
import { api, postJson, sse } from "./api.js";

let logStream = null;

function byId(id){ return document.getElementById(id); }

export async function runScan(subnet) {
  // optional UI progress integration if present
  const prog = byId("jobProg");
  if (prog) prog.style.width = "10%";

  await postJson("/api/v1/discovery/scan", { subnet: subnet ?? null, timeoutMs: 250 });

  if (prog) prog.style.width = "70%";
  const devices = await api("/api/v1/discovery/results");

  const list = byId("devicesList");
  if (list) {
    list.innerHTML = "";
    devices.forEach(d => {
      const row = document.createElement("div");
      row.className = "devCard";
      row.textContent = `${d.ip} ${d.online ? "ONLINE" : "OFFLINE"} ${d.vendor ? "(" + d.vendor + ")" : ""}`;
      row.onclick = async () => {
        const detail = byId("deviceDetail");
        if (detail) {
          const hist = await api(`/api/v1/devices/${encodeURIComponent(d.id)}/history`);
          const up = await api(`/api/v1/devices/${encodeURIComponent(d.id)}/uptime`);
          detail.style.display = "block";
          detail.innerHTML = `
            <h3>${d.ip}</h3>
            <div><b>MAC</b>: ${d.mac ?? "-"}</div>
            <div><b>Vendor</b>: ${d.vendor ?? "-"}</div>
            <div><b>Status</b>: ${d.online ? "ONLINE" : "OFFLINE"}</div>
            <div><b>Uptime 24h</b>: ${Number(up.uptimePct24h ?? up.uptimePct24h).toFixed?.(2) ?? up.uptimePct24h}%</div>
            <div style="margin-top:8px"><b>Events</b>:</div>
            <pre style="white-space:pre-wrap">${hist.map(h => `${new Date(h.ts).toLocaleString()} ${h.event}`).join("\n")}</pre>
          `;
        }
      };
      list.appendChild(row);
    });
  }

  if (prog) prog.style.width = "100%";

  const term = byId("termBody");
  if (term && !logStream) {
    logStream = sse("/api/v1/logs/stream", (line) => {
      const div = document.createElement("div");
      div.className = "termLine";
      div.textContent = line;
      term.appendChild(div);
      term.scrollTop = term.scrollHeight;
    });
  }
}

document.addEventListener("DOMContentLoaded", () => {
  // Prefer explicit scan button id if present; otherwise bind to any element with data-action="scan"
  const scanBtn = byId("scanBtn") || document.querySelector("[data-action='scan']");
  if (scanBtn) scanBtn.addEventListener("click", (e) => { e.preventDefault(); runScan(); });
});
