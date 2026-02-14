    // ---- State ----
    const state = {
      activeTab: "dashboard",
      deviceFilter: "all",
      search: "",
      selectedDeviceId: null,
      selectedNetworkId: null,
      lastScanAt: null,
      lastChangeCount: 0,
      history: [],
      notifications: []
    };

    let scanInFlight = false;

    let devices = [];

    // expose for embedded widgets (discovery map, etc)
    window.devices = devices;

    // Local UI caches
    const firstSeenMap = new Map();
    let cachedNetworkInfo = null;

    function formatSeen(ms){
      if(!ms) return "—";
      const now = Date.now();
      const delta = now - ms;
      if(delta < 120000) return "Now";
      if(delta < 3600000) return `${Math.round(delta / 60000)} min ago`;
      if(delta < 86400000) return `${Math.round(delta / 3600000)}h ago`;
      if(delta < 172800000) return "Yesterday";
      return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" });
    }

    function inferType(raw){
      const host = String(raw?.hostname || "").toLowerCase();
      const os = String(raw?.os || "").toLowerCase();
      const vendor = String(raw?.vendor || "").toLowerCase();
      if(os.includes("android") || os.includes("ios")) return "Phone";
      if(os.includes("tizen") || os.includes("webos") || os.includes("tv")) return "TV";
      if(os.includes("windows") || os.includes("linux") || os.includes("mac")) return "PC";
      if(host.includes("printer") || os.includes("printer")) return "Printer";
      if(vendor.includes("hikvision") || vendor.includes("tuya")) return "IoT";
      return "IoT";
    }

    function mergeDevice(raw){
      const id = raw?.id || raw?.mac || raw?.ip;
      if(!id) return null;
      if(!firstSeenMap.has(id)){
        firstSeenMap.set(id, raw?.lastSeen || Date.now());
      }
      const online = !!raw?.online;
      const trust = raw?.trust || "Unknown";
      const status = raw?.status || (trust === "Blocked" ? "Blocked" : (online ? "Online" : "Offline"));

      return {
        id,
        name: raw?.name || raw?.hostname || raw?.ip || "Device",
        owner: raw?.owner || "",
        type: raw?.type || inferType(raw),
        status,
        ip: raw?.ip || "—",
        mac: raw?.mac || "—",
        vendor: raw?.vendor || "—",
        firstSeen: formatSeen(firstSeenMap.get(id)),
        lastSeen: formatSeen(raw?.lastSeen),
        trust,
        os: raw?.os || "—",
        via: raw?.via || "LAN",
        signal: raw?.signal || "—",
        iface: raw?.iface || "—",
        activityToday: raw?.activityToday || "—",
        traffic: raw?.traffic || "—",
        note: raw?.note || "",
        room: raw?.room || ""
      };
    }

    const { fetchJson, token: NN_TOKEN } = window.NetNinjaApi;

    function applyDevicesFromBackend(rawList){
      if(!Array.isArray(rawList)) return;
      const next = rawList.map(mergeDevice).filter(Boolean);
      devices = next;
      window.devices = devices;
    }

    function upsertDeviceFromBackend(raw){
      const next = mergeDevice(raw);
      if(!next) return;
      const idx = devices.findIndex(d => d.id === next.id);
      if(idx >= 0){
        devices[idx] = next;
      } else {
        devices.unshift(next);
      }
      window.devices = devices;
    }

    async function updateDeviceMeta(id, patch){
      const updated = await fetchJson(`/api/v1/devices/${encodeURIComponent(id)}/meta`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(patch)
      });
      upsertDeviceFromBackend(updated);
      renderDashboard();
      renderDevices();
      renderNetworks();
      window.nnUpdateDiscoveryMap?.();
      return updated;
    }

    async function runDeviceAction(id, action){
      const updated = await fetchJson(`/api/v1/devices/${encodeURIComponent(id)}/actions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action })
      });
      upsertDeviceFromBackend(updated);
      renderDashboard();
      renderDevices();
      renderNetworks();
      window.nnUpdateDiscoveryMap?.();
      return updated;
    }

    function updateNetworkUi(info){
      if(!info || typeof info !== "object") return;
      cachedNetworkInfo = info;

      const name = info.name || "Network";
      const ip = info.ip || "—";
      const cidr = info.cidr || "—";
      const gateway = info.gateway || "—";
      const dns = info.dns || "—";

      $("#myNetText").textContent = `${name} (${cidr})`;
      $("#brandSub").textContent = `${name} • ${cidr}`;

      const n1 = networks.find(n => n.id === "n1");
      if(n1){
        n1.name = name;
        n1.details.ssid = name;
        n1.details.gateway = gateway;
        n1.details.yourIp = ip;
        n1.details.dns = dns;
        n1.details.security = n1.details.security || "—";
        n1.details.openPorts = "—";
        n1.devices = devices.length;
      }

      // keep scan target fields aligned
      window.applyScanData?.({
        ssid: name,
        subnet: cidr,
        gateway: gateway
      });

      renderNetworks();

      if(gateway && gateway !== "—"){
        fetchJson("/api/v1/actions/portscan", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ip: gateway, timeoutMs: 250 })
        }).then((res) => {
          const ports = Array.isArray(res.openPorts) ? res.openPorts.join(", ") : "—";
          if(n1){
            n1.details.openPorts = ports || "—";
            renderNetworks();
          }
        }).catch(() => {
          if(n1){
            n1.details.openPorts = "—";
          }
        });
      }
    }

    async function refreshNetworkInfo(){
      try{
        const info = await fetchJson("/api/v1/network/info");
        updateNetworkUi(info);
      } catch (_){}
    }

    async function refreshDiscoveryResults(){
      try{
        const data = await fetchJson("/api/v1/discovery/results");
        applyDevicesFromBackend(data);
        const n1 = networks.find(n => n.id === "n1");
        if(n1){ n1.devices = devices.length; }
        renderDashboard();
        renderDevices();
        renderNetworks();
        window.nnUpdateDiscoveryMap?.();
        return data;
      } catch (_) {
        return null;
      }
    }

    function formatPerm(value){
      if(value === true) return "Granted";
      if(value === false) return "Denied";
      return "Unknown";
    }

    async function refreshPermissionStatus(){
      try{
        const perms = await fetchJson("/api/v1/system/permissions");
        const nearbyWifi = perms?.nearbyWifi;
        const fine = perms?.fineLocation === true;
        const coarse = perms?.coarseLocation === true;
        const locationGranted = fine || coarse;

        $("#permNearbyWifi").textContent = formatPerm(nearbyWifi);
        $("#permLocation").textContent = formatPerm(locationGranted);
        $("#permWifiState").textContent = formatPerm(perms?.wifiState);
        $("#permNetworkState").textContent = formatPerm(perms?.networkState);

        const summaryOk = (nearbyWifi === true) || (nearbyWifi === null && locationGranted);
        const pill = $("#permSummaryPill");
        if(summaryOk){
          pill.textContent = "OK";
          pill.className = "pill ok";
        } else {
          pill.textContent = "Needs review";
          pill.className = "pill warn";
        }
      } catch (_) {}
    }

    async function sendPermissionAction(action){
      try{
        const res = await fetchJson("/api/v1/system/permissions/action", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ action })
        });
        if(res?.ok){
          showToast("Opening settings", "Use the system screen to grant permissions.");
        } else {
          showToast("Permission control unavailable", "This action is not supported on this device.");
        }
      } catch (_){
        showToast("Permission control unavailable", "Unable to reach the local service.");
      }
    }

    function setupPermissionControls(){
      const appBtn = $("#btnOpenAppSettings");
      const locBtn = $("#btnOpenLocationSettings");
      const wifiBtn = $("#btnOpenWifiSettings");
      const refreshBtn = $("#btnRefreshPermissions");
      appBtn?.addEventListener("click", () => sendPermissionAction("app_settings"));
      locBtn?.addEventListener("click", () => sendPermissionAction("location_settings"));
      wifiBtn?.addEventListener("click", () => sendPermissionAction("wifi_settings"));
      refreshBtn?.addEventListener("click", () => refreshPermissionStatus());
    }

    async function refreshDebugState(){
      const panel = $("#nnDebugPanel");
      if(!panel || !panel.classList.contains("visible")) return;
      try{
        const state = await fetchJson("/api/v1/system/state");
        const text = JSON.stringify(state, null, 2);
        $("#nnDebugText").textContent = text;
      } catch (_) {
        $("#nnDebugText").textContent = "Debug panel unavailable.";
      }
    }

    function setupDebugPanel(){
      const badge = $("#brandBadge");
      const panel = $("#nnDebugPanel");
      if(!badge || !panel) return;
      let tapCount = 0;
      let tapTimer = null;
      badge.addEventListener("click", () => {
        tapCount += 1;
        if(tapTimer) clearTimeout(tapTimer);
        tapTimer = setTimeout(() => { tapCount = 0; }, 1200);
        if(tapCount >= 5){
          tapCount = 0;
          panel.classList.toggle("visible");
          refreshDebugState();
        }
      });
    }


    const networks = [
      {
        id: "n1",
        name: "Network",
        type: "LAN",
        security: "—",
        signal: "—",
        devices: 0,
        status: "My network",
        details: {
          ssid: "Network",
          gateway: "—",
          yourIp: "—",
          dns: "—",
          security: "—",
          openPorts: "—"
        }
      }
    ];

    // ---- Helpers ----
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => Array.from(document.querySelectorAll(sel));

    function nowLabel(){
      const d = new Date();
      const hh = String(d.getHours()).padStart(2,"0");
      const mm = String(d.getMinutes()).padStart(2,"0");
      return `${hh}:${mm}`;
    }

    function showToast(title, body, actions = []){
      const toast = $("#toast");
      $("#toastTitle").textContent = title;
      $("#toastBody").textContent = body || "";
      const act = $("#toastActions");
      act.innerHTML = "";
      actions.forEach(a => {
        const b = document.createElement("button");
        b.className = "btn btn-ghost purple";
        b.textContent = a.label;
        b.addEventListener("click", () => { a.onClick?.(); hideToast(); });
        act.appendChild(b);
      });
      toast.classList.add("show");
      clearTimeout(showToast._t);
      showToast._t = setTimeout(hideToast, 3800);
    }
    function hideToast(){ $("#toast").classList.remove("show"); }

    function openOverlay(id){
      const el = document.getElementById(id);
      if(!el) return;
      el.classList.add("show");
      el.setAttribute("aria-hidden", "false");
    }
    function closeOverlay(id){
      const el = document.getElementById(id);
      if(!el) return;
      el.classList.remove("show");
      el.setAttribute("aria-hidden", "true");
    }

    function pushHistory(entry){
      state.history.unshift(entry);
      state.history = state.history.slice(0, 20);
      renderHistory();
    }
    function pushNotification(n){
      state.notifications.unshift(n);
      state.notifications = state.notifications.slice(0, 30);
      renderNotifications();
    }


    function ensureDeviceMapHost(){
      const tab = document.getElementById("tab-devices");
      if(!tab || tab.querySelector("#nnDeviceMapIframe")) return;

      const listWrap = document.createElement("div");
      listWrap.className = "nn-deviceListWrap";
      while(tab.firstChild){ listWrap.appendChild(tab.firstChild); }

      const mapHost = document.createElement("div");
      mapHost.className = "nn-deviceMapHost";
      mapHost.innerHTML = `
        <div class="nn-deviceMapToolbar" aria-label="Devices 3D map controls">
          <button class="btn btn-primary" type="button" id="nnDeviceMapBtn">3D Map</button>
          <button class="btn btn-ghost" type="button" id="nnDeviceListBtn">Table</button>
        </div>
        <iframe id="nnDeviceMapIframe" src="new_assets/ninja_nodes.html" title="Devices 3D Map" loading="eager"></iframe>
      `;

      tab.appendChild(mapHost);
      tab.appendChild(listWrap);

      document.getElementById("nnDeviceMapBtn")?.addEventListener("click", ()=> setDeviceMapMode(true));
      document.getElementById("nnDeviceListBtn")?.addEventListener("click", ()=> setDeviceMapMode(false));
      document.getElementById("nnDeviceMapIframe")?.addEventListener("load", ()=> setTimeout(()=> window.nnUpdateDiscoveryMap?.(), 250));
    }

    function setDeviceMapMode(enabled){
      state.deviceMapMode = Boolean(enabled);
      const tab = document.getElementById("tab-devices");
      tab?.classList.toggle("map-mode", state.deviceMapMode);
      document.getElementById("nnDeviceMapBtn")?.classList.toggle("active", state.deviceMapMode);
      document.getElementById("nnDeviceListBtn")?.classList.toggle("active", !state.deviceMapMode);
      const app = document.getElementById("app");
      app?.classList.toggle("media-tab-active", state.activeTab === "networks" || (state.activeTab === "devices" && state.deviceMapMode));
      if(state.deviceMapMode){ window.nnUpdateDiscoveryMap?.(); }
    }

    // ---- Navigation ----
    function setTab(tab){
      state.activeTab = tab;
      $$(".tabbtn").forEach(b => b.classList.toggle("active", b.dataset.tab === tab));
      $$(".tab-page").forEach(p => p.classList.toggle("active", p.id === `tab-${tab}`));

      if(tab === "devices"){
        ensureDeviceMapHost();
        renderDevices();
        setDeviceMapMode(true);
      }
      if(tab === "networks"){ window.nnUpdateDiscoveryMap?.(); }
      if(tab === "dashboard"){ renderDashboard(); }
      if(tab === "gateway"){ renderG5ar(); }

      const app = document.getElementById("app");
      app?.classList.toggle("media-tab-active", tab === "networks" || (tab === "devices" && state.deviceMapMode));
    }

    $$(".tabbtn").forEach(btn => btn.addEventListener("click", () => setTab(btn.dataset.tab)));

    window.addEventListener("message", (event) => {
      const data = event && event.data;
      if(!data || data.source !== "netninja-cam" || data.type !== "switch-tab") return;
      const target = String(data.tab || "dashboard");
      const allowed = new Set(["dashboard", "devices", "networks", "tools", "gateway", "openclaw", "cameras"]);
      setTab(allowed.has(target) ? target : "dashboard");
    });

    // ---- Dashboard ----
    function renderDashboard(){
      const onlineCount = devices.filter(d => d.status === "Online").length;
      $("#onlineCount").textContent = String(onlineCount);

      const unknown = devices.filter(d => d.trust === "Unknown").slice(0, 5);
      if(unknown.length === 0){
        $("#unknownList").textContent = "No new devices.";
        $("#unknownPill").textContent = "All clear";
        $("#unknownPill").className = "pill ok";
      } else {
        $("#unknownPill").textContent = `${unknown.length} to review`;
        $("#unknownPill").className = "pill warn";
        $("#unknownList").innerHTML = unknown.map(d => `• ${escapeHtml(d.name)} <span style="color:rgba(255,255,255,0.65)">(${escapeHtml(d.trust)})</span>`).join("<br>");
      }

      const last = state.lastScanAt ? `Last scan: ${state.lastScanAt}` : "Last scan: never";
      $("#changePill").textContent = last;
      $("#changeText").textContent = state.lastScanAt
        ? "Scan results updated. Review unknown devices."
        : "Run a scan to see changes and new devices.";

      // naive "changed" indicator
      $("#onlineDeltaPill").textContent = state.lastScanAt ? `Changed: ${state.lastChangeCount}` : "Changed: 0";
    }

    // ---- Devices ----
    function statusClass(s){
      if(s === "Online") return "online";
      if(s === "Paused") return "offline";
      if(s === "Offline") return "offline";
      if(s === "Blocked") return "blocked";
      return "offline";
    }
    function trustPill(trust){
      if(trust === "Trusted") return `<span class="pill ok">Trusted</span>`;
      if(trust === "Blocked") return `<span class="pill bad">Blocked</span>`;
      return `<span class="pill warn">Unknown</span>`;
    }

    function deviceMatchesFilter(d){
      const f = state.deviceFilter;
      if(f === "all") return true;
      if(f === "online") return d.status === "Online";
      if(f === "offline") return d.status === "Offline";
      if(f === "trusted") return d.trust === "Trusted";
      if(f === "unknown") return d.trust === "Unknown";
      if(f === "blocked") return d.trust === "Blocked" || d.status === "Blocked";
      return true;
    }

    function deviceMatchesSearch(d){
      const q = state.search.trim().toLowerCase();
      if(!q) return true;
      const hay = [
        d.name, d.owner, d.type, d.status, d.ip, d.mac, d.vendor, d.trust, d.os
      ].join(" ").toLowerCase();
      return hay.includes(q);
    }

    function renderDevices(){
      const tbody = $("#deviceTbody");
      const rows = devices
        .filter(deviceMatchesFilter)
        .filter(deviceMatchesSearch);

      tbody.innerHTML = rows.map(d => {
        const isSel = d.id === state.selectedDeviceId;
        return `
          <tr data-id="${d.id}" class="${isSel ? "selected" : ""}">
            <td title="Double‑click to open">${escapeHtml(d.name)}</td>
            <td>${escapeHtml(d.owner || "—")}</td>
            <td>${typeIcon(d.type)} ${escapeHtml(d.type)}</td>
            <td><span class="status ${statusClass(d.status)}"><span class="dot"></span>${escapeHtml(d.status)}</span></td>
            <td>${escapeHtml(d.ip)}</td>
            <td>${escapeHtml(d.mac)}</td>
            <td>${escapeHtml(d.vendor || "—")}</td>
            <td>${escapeHtml(d.firstSeen || "—")}</td>
            <td>${escapeHtml(d.lastSeen || "—")}</td>
            <td>${escapeHtml(d.trust)}</td>
            <td><button class="kebab" data-menu="${d.id}" title="Actions">⋮</button></td>
          </tr>
        `;
      }).join("");

      // attach row listeners
      $$("#deviceTbody tr").forEach(tr => {
        tr.addEventListener("click", () => selectDevice(tr.dataset.id, false));
        tr.addEventListener("dblclick", () => selectDevice(tr.dataset.id, true));
      });

      // kebab menus (simple sheet per device)
      $$("#deviceTbody .kebab").forEach(b => {
        b.addEventListener("click", (e) => {
          e.stopPropagation();
          const id = b.dataset.menu;
          openDeviceActions(id);
        });
      });

      // refresh side panel
      renderSidePanel();
    }

    function selectDevice(id, openDetails){
      state.selectedDeviceId = id;
      renderDevices(); // re-render selection highlight
      if(openDetails){
        openDeviceDetails(id);
      }
    }

    function renderSidePanel(){
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d){
        $("#spTitle").textContent = "Select a device";
        $("#spSub").textContent = "Pick a row to view details.";
        $("#spStatus").textContent = "—";
        $("#spSummaryKv").innerHTML = "";
        $("#spNetworkKv").innerHTML = "";
        $("#spActivityKv").innerHTML = "";
        $("#spPause").disabled = true;
        $("#spBlock").disabled = true;
        $("#spTrust").disabled = true;
        $("#spPortScan").disabled = true;
        return;
      }

      $("#spTitle").textContent = `${d.name} – ${d.type}`;
      $("#spSub").textContent = `${d.owner || "No owner"} • ${d.vendor || "Unknown vendor"}`;
      $("#spStatus").textContent = d.status.toUpperCase();
      $("#spStatus").className = "pill " + (d.status === "Online" ? "ok" : (d.status === "Blocked" ? "bad" : ""));
      $("#spPause").disabled = false;
      $("#spBlock").disabled = false;
      $("#spTrust").disabled = false;
      $("#spPortScan").disabled = false;

      $("#spSummaryKv").innerHTML = kvLines([
        ["Status", d.status],
        ["Trust", d.trust],
        ["Type", d.type],
        ["Owner", d.owner || "—"]
      ]);

      $("#spNetworkKv").innerHTML = kvLines([
        ["IP", d.ip],
        ["MAC", d.mac],
        ["Vendor", d.vendor || "—"],
        ["OS (detected)", d.os || "—"],
        ["Connected via", d.via || "—"],
        ["Signal", d.signal || "—"],
        ["Interface", d.iface || "—"]
      ]);

      $("#spActivityKv").innerHTML = kvLines([
        ["Last seen", d.lastSeen || "—"],
        ["Online time today", d.activityToday || "—"],
        ["Traffic", d.traffic || "—"]
      ]);

      // keep form fields in sync
      $("#spOwner").value = d.owner || "";
      $("#spRoom").value = d.room || "";
      $("#spNote").value = d.note || "";
      $("#spTrust").textContent = d.trust === "Trusted" ? "Mark as unknown" : "Mark as trusted";
    }

    function openDeviceDetails(id){
      const d = devices.find(x => x.id === id);
      if(!d) return;

      $("#devSheetTitle").textContent = "Device details";
      const body = $("#devSheetBody");

      body.innerHTML = `
        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Summary</div>
            <div class="pill ${d.status === "Online" ? "ok" : (d.status === "Blocked" ? "bad" : "")}">${escapeHtml(d.status)}</div>
          </div>
          <div class="muted" style="margin-top:4px;"><strong style="color:rgba(255,255,255,0.95);">${escapeHtml(d.name)}</strong> – ${escapeHtml(d.type)}</div>
          <div class="kv" style="margin-top:10px;">
            ${kvLines([
              ["Trust state", d.trust],
              ["Owner", d.owner || "—"],
              ["Vendor", d.vendor || "—"]
            ])}
          </div>
          <div class="card-actions">
            <button class="btn btn-ghost" id="mPause">Set status: Paused</button>
            <button class="btn btn-danger" id="mBlock">Set status: Blocked</button>
            <button class="btn btn-ghost purple" id="mTrust">${d.trust === "Trusted" ? "Mark as unknown" : "Mark as trusted"}</button>
            <button class="btn btn-ghost" id="mPort">Run port scan</button>
          </div>
        </div>

        <div style="height:12px;"></div>

        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Network info</div>
            <div class="pill">Details</div>
          </div>
          <div class="kv">
            ${kvLines([
              ["IP", d.ip],
              ["MAC", d.mac],
              ["OS (detected)", d.os || "—"],
              ["Connected via", d.via || "—"],
              ["Signal strength", d.signal || "—"]
            ])}
          </div>
        </div>

        <div style="height:12px;"></div>

        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Activity</div>
            <div class="pill">Today</div>
          </div>
          <div class="kv">
            ${kvLines([
              ["Last seen", d.lastSeen || "—"],
              ["Total online time today", d.activityToday || "—"],
              ["Traffic", d.traffic || "—"]
            ])}
          </div>
        </div>

        <div style="height:12px;"></div>

        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Notes & labels</div>
            <div class="pill">Optional</div>
          </div>
          <div class="field">
            <label for="mNote">Add a note…</label>
            <textarea id="mNote" placeholder="Add a note…">${escapeHtml(d.note || "")}</textarea>
          </div>
          <div class="field">
            <label for="mRoom">Room</label>
            <select id="mRoom">
              ${selectOptions(["", "Office", "Living Room", "Bedroom", "Kitchen", "Garage"], d.room || "")}
            </select>
          </div>
          <div class="field">
            <label for="mOwner">Owner</label>
            <select id="mOwner">
              ${selectOptions(["", "Me", "Guest", "Child", "Work"], d.owner || "")}
            </select>
          </div>
          <button class="btn btn-ghost purple" id="mSave">Save</button>
        </div>
      `;

      // Wire actions
      $("#mPause").addEventListener("click", () => pauseDevice(d.id));
      $("#mBlock").addEventListener("click", () => blockDevice(d.id));
      $("#mTrust").addEventListener("click", () => toggleTrust(d.id));
      $("#mPort").addEventListener("click", () => openToolDialog("portScan", d.id));

      $("#mSave").addEventListener("click", async () => {
        const patch = {
          note: $("#mNote").value,
          room: $("#mRoom").value || "",
          owner: $("#mOwner").value || ""
        };
        try{
          await updateDeviceMeta(d.id, patch);
          pushHistory({ when: nowLabel(), title: `Saved notes for ${d.name}`, detail: "Notes / labels updated", undo: null });
          showToast("Saved", "Notes and labels updated.");
        } catch (_){
          showToast("Save failed", "Unable to update device notes.");
        }
      });

      openOverlay("deviceOverlay");
    }

    function openDeviceActions(id){
      const d = devices.find(x => x.id === id);
      if(!d) return;

      $("#devSheetTitle").textContent = "Device actions";
      const body = $("#devSheetBody");
      body.innerHTML = `
        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">${escapeHtml(d.name)}</div>
            <div class="pill ${d.status === "Online" ? "ok" : (d.status === "Blocked" ? "bad" : "")}">${escapeHtml(d.status)}</div>
          </div>
          <div class="muted">${escapeHtml(d.type)} • ${escapeHtml(d.ip)} • ${escapeHtml(d.trust)}</div>
          <div class="card-actions" style="margin-top: 12px;">
            <button class="btn btn-ghost" id="aView">View details</button>
            <button class="btn btn-ghost" id="aPause">Set status: Paused</button>
            <button class="btn btn-danger" id="aBlock">Set status: Blocked</button>
            <button class="btn btn-ghost purple" id="aTrust">${d.trust === "Trusted" ? "Mark as unknown" : "Mark as trusted"}</button>
            <button class="btn btn-ghost" id="aRename">Rename device</button>
            <button class="btn btn-ghost purple" id="aOwner">Assign owner / room</button>
          </div>
        </div>
      `;

      $("#aView").addEventListener("click", () => { closeOverlay("deviceOverlay"); openDeviceDetails(d.id); });
      $("#aPause").addEventListener("click", () => pauseDevice(d.id));
      $("#aBlock").addEventListener("click", () => blockDevice(d.id));
      $("#aTrust").addEventListener("click", () => toggleTrust(d.id));
      $("#aRename").addEventListener("click", () => {
        const next = prompt("Rename device:", d.name);
        if(next && next.trim()){
          const prev = d.name;
          const nextName = next.trim();
          updateDeviceMeta(d.id, { name: nextName }).then(() => {
            pushHistory({
              when: nowLabel(),
              title: `Renamed device`,
              detail: `${prev} → ${nextName}`,
              undo: () => {
                updateDeviceMeta(d.id, { name: prev }).then(() => {
                  showToast("Undone", "Rename reverted.");
                }).catch(() => showToast("Undo failed", "Unable to revert rename."));
              }
            });
            showToast("Renamed", nextName);
          }).catch(() => {
            showToast("Rename failed", "Unable to rename device.");
          });
        }
      });
      $("#aOwner").addEventListener("click", () => { closeOverlay("deviceOverlay"); openDeviceDetails(d.id); });

      openOverlay("deviceOverlay");
    }

    async function blockDevice(id){
      const d = devices.find(x => x.id === id);
      if(!d) return;
      const prev = { status: d.status, trust: d.trust };
      try{
        await runDeviceAction(d.id, "block");
        pushHistory({
          when: nowLabel(),
          title: `Blocked ${d.name}`,
          detail: "Device status set to Blocked.",
          undo: () => {
            updateDeviceMeta(d.id, { status: prev.status, trust: prev.trust }).then(() => {
              showToast("Unblocked", `${d.name} restored.`);
            }).catch(() => showToast("Undo failed", "Unable to restore device."));
          }
        });
        pushNotification({
          when: nowLabel(),
          title: `Device blocked: ${d.name}`,
          detail: "Tap Undo in Recent actions to revert."
        });
        showToast("Status updated", `${d.name} marked as Blocked.`, [{ label: "Undo", onClick: () => undoLast() }]);
      } catch (_){
        showToast("Block failed", "Unable to block device.");
      }
    }

    async function pauseDevice(id){
      const d = devices.find(x => x.id === id);
      if(!d) return;
      const prev = d.status;
      try{
        await runDeviceAction(d.id, "pause");
        pushHistory({
          when: nowLabel(),
          title: `Paused ${d.name}`,
          detail: `${d.ip} status set to Paused`,
          undo: () => {
            updateDeviceMeta(d.id, { status: prev }).then(() => {
              showToast("Status updated", `${d.name} restored.`);
            }).catch(() => showToast("Undo failed", "Unable to restore device."));
          }
        });
        showToast("Status updated", `${d.name} marked as Paused.`);
      } catch (_){
        showToast("Pause failed", "Unable to pause device.");
      }
    }

    async function toggleTrust(id){
      const d = devices.find(x => x.id === id);
      if(!d) return;
      const prev = d.trust;
      const next = (d.trust === "Trusted") ? "Unknown" : "Trusted";
      const action = (next === "Trusted") ? "trust" : "untrust";
      try{
        await runDeviceAction(d.id, action);
        pushHistory({
          when: nowLabel(),
          title: `Trust changed: ${d.name}`,
          detail: `${prev} → ${next}`,
          undo: () => {
            updateDeviceMeta(d.id, { trust: prev }).then(() => {
              showToast("Undone", "Trust reverted.");
            }).catch(() => showToast("Undo failed", "Unable to revert trust."));
          }
        });
        showToast("Updated", `${d.name} is now ${next}.`);
      } catch (_){
        showToast("Update failed", "Unable to change trust.");
      }
    }

    // sidepanel actions
    $("#spPause").addEventListener("click", () => {
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d) return;
      pauseDevice(d.id);
    });
    $("#spBlock").addEventListener("click", () => {
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d) return;
      blockDevice(d.id);
    });
    $("#spTrust").addEventListener("click", () => {
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d) return;
      toggleTrust(d.id);
    });
    $("#spPortScan").addEventListener("click", () => {
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d) return;
      openToolDialog("portScan", d.id);
    });
    $("#spSaveNotes").addEventListener("click", () => {
      const d = devices.find(x => x.id === state.selectedDeviceId);
      if(!d) return;
      updateDeviceMeta(d.id, {
        note: $("#spNote").value,
        room: $("#spRoom").value || "",
        owner: $("#spOwner").value || ""
      }).then(() => {
        pushHistory({ when: nowLabel(), title: `Saved notes for ${d.name}`, detail: "Notes / labels updated", undo: null });
        showToast("Saved", "Notes and labels updated.");
      }).catch(() => {
        showToast("Save failed", "Unable to update device notes.");
      });
    });

    // sidepanel section tabs
    $$(".tab2").forEach(b => b.addEventListener("click", () => {
      $$(".tab2").forEach(x => x.classList.remove("active"));
      b.classList.add("active");
      const t = b.dataset.spTab;
      $("#spSectionSummary").style.display = (t === "summary") ? "block" : "none";
      $("#spSectionNetwork").style.display = (t === "network") ? "block" : "none";
      $("#spSectionActivity").style.display = (t === "activity") ? "block" : "none";
      $("#spSectionNotes").style.display = (t === "notes") ? "block" : "none";
    }));

    // filters
    $("#deviceSearch").addEventListener("input", (e) => {
      state.search = e.target.value || "";
      renderDevices();
    });
    $$("#trustChips .chip").forEach(ch => ch.addEventListener("click", () => {
      $$("#trustChips .chip").forEach(x => x.classList.remove("active"));
      ch.classList.add("active");
      state.deviceFilter = ch.dataset.filter;
      renderDevices();
    }));

    // export / rescan
    $("#btnRescanDevices").addEventListener("click", () => {
      runScan();
    });
    $("#btnExportDevices").addEventListener("click", () => {
      fetchJson("/api/v1/export/devices").then((data) => {
        const payload = JSON.stringify(data, null, 2);
        downloadText(payload, "devices.json", "application/json");
        pushHistory({ when: nowLabel(), title: "Exported device list", detail: "devices.json downloaded", undo: null });
        showToast("Exported", "Downloaded devices.json");
      }).catch(() => {
        showToast("Export failed", "Backend unavailable.");
      });
    });

    // ---- Networks ----
    function renderNetworks(){
      const tbody = $("#netTbody");
      if(!tbody) return;
      tbody.innerHTML = networks.map(n => {
        const sel = n.id === state.selectedNetworkId;
        return `
          <tr data-id="${n.id}" class="${sel ? "selected" : ""}">
            <td>${escapeHtml(n.name)}</td>
            <td>${escapeHtml(n.type)}</td>
            <td>${escapeHtml(n.security)}</td>
            <td>${escapeHtml(String(n.signal))}</td>
            <td>${escapeHtml(String(n.devices))}</td>
            <td>${escapeHtml(n.status)}</td>
            <td><button class="kebab" data-net="${n.id}" title="Actions">⋮</button></td>
          </tr>
        `;
      }).join("");

      $$("#netTbody tr").forEach(tr => {
        tr.addEventListener("click", () => selectNetwork(tr.dataset.id));
      });
      $$("#netTbody .kebab").forEach(b => {
        b.addEventListener("click", (e) => {
          e.stopPropagation();
          openNetworkActions(b.dataset.net);
        });
      });

      renderNetworkDetails();
    }

    function selectNetwork(id){
      state.selectedNetworkId = id;
      renderNetworks();
    }

    function renderNetworkDetails(){
      const n = networks.find(x => x.id === state.selectedNetworkId);
      const kv = $("#netDetailsKv");
      const pill = $("#netDetailsPill");
      const actions = $("#netDetailsActions");
      if(!kv || !pill || !actions) return;
      actions.style.display = n ? "flex" : "none";

      if(!n){
        pill.textContent = "Select a network";
        kv.innerHTML = "";
        return;
      }

      pill.textContent = n.status;
      kv.innerHTML = kvLines([
        ["SSID / CIDR", n.details.ssid],
        ["Gateway IP", n.details.gateway],
        ["Your IP", n.details.yourIp],
        ["DNS", n.details.dns],
        ["Security", n.details.security],
        ["Open ports on gateway", n.details.openPorts]
      ]);
    }

    function openNetworkActions(id){
      const n = networks.find(x => x.id === id);
      if(!n) return;
      $("#toolTitle").textContent = "Network actions";
      $("#toolBody").innerHTML = `
        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">${escapeHtml(n.name)}</div>
            <div class="pill">${escapeHtml(n.status)}</div>
          </div>
          <div class="muted">${escapeHtml(n.type)} • ${escapeHtml(n.security)}</div>
          <div class="card-actions" style="margin-top: 12px;">
            <button class="btn btn-ghost" id="naViewDevices">View devices on this network</button>
            <button class="btn btn-ghost purple" id="naScan">Scan this network</button>
            <button class="btn btn-ghost" id="naSecurity">Run security check</button>
          </div>
        </div>
      `;
      $("#naViewDevices").addEventListener("click", () => { closeOverlay("toolOverlay"); setTab("devices"); });
      $("#naScan").addEventListener("click", () => { closeOverlay("toolOverlay"); runScan(); });
      $("#naSecurity").addEventListener("click", () => { closeOverlay("toolOverlay"); openToolDialog("security"); });
      openOverlay("toolOverlay");
    }

    $("#btnNetworksSecurity")?.addEventListener("click", () => openToolDialog("security"));

    $("#btnNetViewDevices")?.addEventListener("click", () => setTab("devices"));
    $("#btnNetScan")?.addEventListener("click", () => {
      const n = networks.find(x => x.id === state.selectedNetworkId);
      if(!n) return showToast("Select a network", "Pick a network row first.");
      runScan();
    });
    $("#btnNetSec")?.addEventListener("click", () => openToolDialog("security"));

    // ---- Tools dialog ----
    function openToolDialog(kind, deviceId){
      const d = deviceId ? devices.find(x => x.id === deviceId) : null;
      const titleMap = {
        fullScan: "Run full scan",
        portScan: "Port scan",
        security: "Run security check",
        reports: "Reports",
        schedule: "Create schedule"
      };
      $("#toolTitle").textContent = titleMap[kind] || "Tool";

      if(kind === "fullScan"){
        const onPrimary = () => {
          closeOverlay("toolOverlay");
          runScan();
          pushHistory({ when: nowLabel(), title: "Full scan started", detail: "Full device scan.", undo: null });
        };
        $("#toolBody").innerHTML = toolTemplate(
          "Scan all addresses on your network for any device.",
          [
            { label: "Scope", type: "select", id: "fsScope", options: ["Local subnet", "All known subnets"], value: "Local subnet" },
            { label: "Speed", type: "select", id: "fsSpeed", options: ["Fast", "Balanced", "Thorough"], value: "Balanced" }
          ],
          "Run full scan",
          onPrimary
        );
        wireToolPrimary(onPrimary);
      } else if(kind === "portScan"){
        const onPrimary = async () => {
          const devName = $("#psDevice")?.value || "device";
          const profile = $("#psProfile")?.value || "Quick scan";
          const device = devices.find(x => x.name === devName) || devices[0];
          if(!device?.ip){
            showToast("Port scan failed", "Device IP unavailable.");
            return;
          }
          try{
            const res = await fetchJson("/api/v1/actions/portscan", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ ip: device.ip, timeoutMs: profile === "Full scan" ? 600 : 250 })
            });
            const ports = Array.isArray(res.openPorts) ? res.openPorts.join(", ") : "None";
            closeOverlay("toolOverlay");
            showToast("Port scan complete", `${devName} • Open: ${ports || "None"}`);
            pushHistory({ when: nowLabel(), title: `Port scan: ${devName}`, detail: `Open ports: ${ports || "None"}`, undo: null });
          } catch (_){
            showToast("Port scan failed", "Backend unavailable.");
          }
        };
        $("#toolBody").innerHTML = toolTemplate(
          "Check which ports are open on a device.",
          [
            { label: "Device", type: "select", id: "psDevice", options: devices.map(x => x.name), value: d ? d.name : devices[0]?.name },
            { label: "Profile", type: "select", id: "psProfile", options: ["Quick scan", "Full scan", "Custom"], value: "Quick scan" }
          ],
          "Run port scan",
          onPrimary
        );
        wireToolPrimary(onPrimary);
      } else if(kind === "security"){
        const onPrimary = async () => {
          try{
            const res = await fetchJson("/api/v1/actions/security", { method: "POST" });
            closeOverlay("toolOverlay");
            showToast("Security check complete", `Unknown: ${res.unknownDevices ?? 0}, Blocked: ${res.blockedDevices ?? 0}`);
            pushHistory({ when: nowLabel(), title: "Security check complete", detail: `Unknown: ${res.unknownDevices ?? 0}, Blocked: ${res.blockedDevices ?? 0}`, undo: null });
          } catch (_){
            showToast("Security check failed", "Backend unavailable.");
          }
        };
        $("#toolBody").innerHTML = toolTemplate(
          "Look for weak security on your network.",
          [
            { label: "Include router checks", type: "select", id: "scRouter", options: ["Off", "On"], value: "Off" },
            { label: "Depth", type: "select", id: "scDepth", options: ["Quick", "Balanced", "Thorough"], value: "Quick" }
          ],
          "Run security check",
          onPrimary
        );
        wireToolPrimary(onPrimary);
      } else if(kind === "reports"){
        const onPrimary = async () => {
          const fmt = $("#rpFmt")?.value || "CSV";
          const include = $("#rpInc")?.value || "Devices";
          try{
            const data = await fetchJson("/api/v1/export/devices");
            const normalized = Array.isArray(data) ? data.map(mergeDevice).filter(Boolean) : [];
            closeOverlay("toolOverlay");
            const content = fmt === "CSV"
              ? makeDevicesCsv(normalized, include)
              : fmt === "HTML"
                ? makeDevicesHtml(normalized, include)
                : fmt === "TXT"
                  ? makeDevicesTxt(normalized, include)
                  : JSON.stringify({ devices: data }, null, 2);
            const mime = fmt === "CSV" ? "text/csv" : (fmt === "HTML" ? "text/html" : "text/plain");
            const name = fmt === "CSV" ? "report.csv" : (fmt === "HTML" ? "report.html" : "report.txt");
            downloadText(content, name, mime);
            pushHistory({ when: nowLabel(), title: "Report generated", detail: name + " downloaded", undo: null });
            showToast("Report generated", name + " downloaded");
          } catch (_){
            showToast("Report failed", "Backend unavailable.");
          }
        };
        $("#toolBody").innerHTML = toolTemplate(
          "Export devices, activity, and scan results.",
          [
            { label: "Format", type: "select", id: "rpFmt", options: ["HTML", "CSV", "TXT"], value: "CSV" },
            { label: "Include", type: "select", id: "rpInc", options: ["Devices", "Devices + activity", "Everything"], value: "Devices" }
          ],
          "Generate report",
          onPrimary
        );
        wireToolPrimary(onPrimary);
      } else if(kind === "schedule"){
        const onPrimary = async () => {
          const freq = $("#schFreq")?.value || "Weekly";
          const act = $("#schAct")?.value || "Scan my network";
          const subnet = cachedNetworkInfo?.cidr || "";
          try{
            if(act === "Auto‑block unknown devices"){
              await fetchJson("/api/v1/rules", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ match: "new_device", action: "block" })
              });
              closeOverlay("toolOverlay");
              pushHistory({ when: nowLabel(), title: "Rule created", detail: "Auto-block new devices enabled", undo: null });
              showToast("Rule enabled", "Auto-block new devices enabled.");
            } else {
              if(!subnet){
                showToast("Schedule failed", "Network info unavailable.");
                return;
              }
              await fetchJson("/api/v1/schedules", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ subnet, freq: freq.toLowerCase() })
              });
              closeOverlay("toolOverlay");
              pushHistory({ when: nowLabel(), title: "Schedule created", detail: `${act} • ${freq}`, undo: null });
              showToast("Scheduled", `${act} • ${freq}`);
            }
          } catch (_){
            showToast("Schedule failed", "Backend unavailable.");
          }
        };
        $("#toolBody").innerHTML = toolTemplate(
          "Schedule scans or auto‑block unknown devices.",
          [
            { label: "Schedule", type: "select", id: "schFreq", options: ["Daily", "Weekly", "Monthly"], value: "Weekly" },
            { label: "Action", type: "select", id: "schAct", options: ["Scan my network", "Auto‑block unknown devices"], value: "Scan my network" }
          ],
          "Create schedule",
          onPrimary
        );
        wireToolPrimary(onPrimary);
      }

      openOverlay("toolOverlay");
    }

    function wireToolPrimary(onPrimary){
      const btn = $("#toolPrimaryBtn");
      if(!btn) return;
      btn.addEventListener("click", () => onPrimary?.());
    }

    function toolTemplate(desc, fields, primaryLabel, onPrimary){
      const fieldsHtml = fields.map(f => {
        const id = escapeHtml(f.id);
        if(f.type === "select"){
          return `
            <div class="field">
              <label for="${id}">${escapeHtml(f.label)}</label>
              <select id="${id}">
                ${f.options.map(o => `<option ${o === f.value ? "selected" : ""}>${escapeHtml(o)}</option>`).join("")}
              </select>
            </div>
          `;
        }
        return "";
      }).join("");

      // Basic "Advanced options" expander
      return `
        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Overview</div>
            <div class="pill">Simple</div>
          </div>
          <div class="muted">${escapeHtml(desc)}</div>
        </div>

        <div style="height:12px;"></div>

        <div class="card" style="cursor:default;">
          <div class="card-head">
            <div class="card-title">Options</div>
            <div class="pill">2–3 controls</div>
          </div>
          ${fieldsHtml}

          <details style="margin-top:10px;">
            <summary style="cursor:pointer; color: rgba(255,255,255,0.78);">Advanced options</summary>
            <div class="muted" style="margin-top:8px;">Advanced options apply when supported by the backend.</div>
          </details>

          <div class="card-actions" style="margin-top: 12px;">
            <button class="btn btn-primary" id="toolPrimaryBtn">${escapeHtml(primaryLabel)}</button>
          </div>
        </div>
      `;
    }

    // wire tool dialog openers
    $$("[data-open-dialog]").forEach(b => {
      b.addEventListener("click", (e) => {
        e.stopPropagation();
        openToolDialog(b.dataset.openDialog);
      });
    });

    // ---- Global buttons ----
    async function runScan(){
      if(scanInFlight){
        showToast("Scan already running", "Please wait for the current scan to finish.");
        return;
      }

      scanInFlight = true;
      const prevIds = new Set(devices.map(d => d.id));

      state.lastScanAt = nowLabel();
      $("#changePill").textContent = `Last scan: ${state.lastScanAt}`;
      $("#changeText").textContent = "Scan requested. Live progress will update below.";
      showToast("Scan started", "Scan queued. Live progress will update below.");
      pushHistory({ when: nowLabel(), title: "Scan started", detail: "Network scan.", undo: null });

      try{
        await refreshNetworkInfo();
        const subnet = cachedNetworkInfo?.cidr || "";
        const payload = { subnet };
        await fetchJson("/api/v1/discovery/scan", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
          timeoutMs: 30_000
        });

        await refreshDiscoveryResults();
        state.lastChangeCount = devices.filter(d => !prevIds.has(d.id)).length;
        renderDashboard();
        renderDevices();
        renderNetworks();

        window.applyScanData?.({
          progress: 5,
          phase: "SCANNING",
          networks: 1,
          devices: devices.length,
          rssiDbm: NaN,
          ssid: cachedNetworkInfo?.name || "--",
          bssid: "--",
          subnet: cachedNetworkInfo?.cidr || "--",
          gateway: cachedNetworkInfo?.gateway || "--",
          linkUp: true
        });

        const unknown = devices.find(d => d.trust === "Unknown");
        if(unknown){
          pushNotification({
            when: nowLabel(),
            title: `New device detected: ${unknown.name}`,
            detail: "Tap Review to inspect and trust/block."
          });
        }

        window.nnUpdateDiscoveryMap?.();
      } catch (err){
        showToast("Scan failed", "Backend unavailable or scan blocked.");
      } finally {
        scanInFlight = false;
      }
    }

    window.nnRunScan = runScan;

    async function stopScan(){
      scanInFlight = false;
      try{
        await fetchJson("/api/v1/discovery/stop", { method: "POST" });
        window.applyScanData?.({ progress: 0, phase: "IDLE", networks: 0, devices: 0, rssiDbm: NaN, linkUp: true });
        showToast("Scan stopped", "Discovery scan canceled.");
      } catch (_){
        showToast("Stop failed", "Unable to stop scan.");
      } finally {
        refreshDiscoveryResults();
      }
    }

    window.nnStopScan = stopScan;

    $("#scanNowBtn").addEventListener("click", () => runScan());
    $("#btnScanMyNetwork").addEventListener("click", (e) => { e.stopPropagation(); runScan(); });
    $("#btnNetworksScanMy").addEventListener("click", () => runScan());

    $("#btnViewOnlineDevices").addEventListener("click", (e) => {
      e.stopPropagation();
      setTab("devices");
      setFilter("online");
    });

    $("#btnReviewUnknown").addEventListener("click", (e) => {
      e.stopPropagation();
      setTab("devices");
      setFilter("unknown");
    });

    $("#btnPauseInternet").addEventListener("click", (e) => {
      e.stopPropagation();
      setTab("devices");
      showToast("Pick a device", "Select a device to set its status.");
    });

    $("#btnBlockNew").addEventListener("click", (e) => {
      e.stopPropagation();
      fetchJson("/api/v1/rules", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ match: "new_device", action: "block" })
      }).then(() => {
        pushHistory({ when: nowLabel(), title: "Auto-block new devices", detail: "Auto-block unknown devices enabled.", undo: null });
        showToast("Enabled", "Auto-block unknown devices enabled.");
      }).catch(() => {
        showToast("Enable failed", "Unable to create auto-block rule.");
      });
    });

    $("#btnShareWifi").addEventListener("click", (e) => {
      e.stopPropagation();
      const text = cachedNetworkInfo?.name ? `${cachedNetworkInfo.name} • ${cachedNetworkInfo.cidr || ""}`.trim() : "Home Wi‑Fi (SSID) • WPA2";
      navigator.clipboard?.writeText(text).then(() => {
        showToast("Copied", "Wi‑Fi details copied to clipboard.");
        pushHistory({ when: nowLabel(), title: "Shared Wi‑Fi details", detail: "Copied to clipboard", undo: null });
      }).catch(() => {
        showToast("Share", "Clipboard not available here.");
      });
    });

    function wireCardButton(id, handler){
      const el = document.getElementById(id);
      if(!el) return;
      el.addEventListener("click", handler);
      el.addEventListener("keydown", (e) => {
        if(e.key === "Enter" || e.key === " "){
          e.preventDefault();
          handler();
        }
      });
    }

    // clickable cards
    wireCardButton("cardMyNetwork", () => setTab("networks"));
    wireCardButton("cardWhosOnline", () => { setTab("devices"); setFilter("online"); });
    wireCardButton("cardUnknown", () => { setTab("devices"); setFilter("unknown"); });
    wireCardButton("cardQuickActions", () => setTab("tools"));
    wireCardButton("cardStatus", () => openOverlay("historyOverlay"));

    function setFilter(filter){
      state.deviceFilter = filter;
      $$("#trustChips .chip").forEach(x => x.classList.toggle("active", x.dataset.filter === filter));
      renderDevices();
    }

    // ---- History & Notifications ----
    function renderHistory(){
      const list = $("#historyList");
      if(state.history.length === 0){
        list.innerHTML = `<div class="muted">No actions yet. Someone has to click something first.</div>`;
        return;
      }
      list.innerHTML = state.history.map((h, idx) => {
        const undo = h.undo ? `<button class="btn btn-ghost purple" data-undo="${idx}">Undo</button>` : "";
        return `
          <div class="list-item">
            <div class="line1"><span>${escapeHtml(h.title)}</span><span style="color:rgba(255,255,255,0.62);">${escapeHtml(h.when)}</span></div>
            <div class="line2">${escapeHtml(h.detail || "")}</div>
            ${undo ? `<div class="action">${undo}</div>` : ``}
          </div>
        `;
      }).join("");

      $$("[data-undo]").forEach(b => {
        b.addEventListener("click", () => {
          const i = Number(b.dataset.undo);
          const item = state.history[i];
          if(item?.undo){
            item.undo();
            // keep it in history but mark as undone
            state.history[i] = { ...item, title: "Undone: " + item.title, undo: null };
            renderHistory();
          }
        });
      });
    }

    function undoLast(){
      const item = state.history.find(h => typeof h.undo === "function");
      if(item?.undo){
        item.undo();
        item.undo = null;
        item.title = "Undone: " + item.title;
        renderHistory();
      } else {
        showToast("Nothing to undo", "No reversible action found.");
      }
    }

    function renderNotifications(){
      const list = $("#notifList");
      if(state.notifications.length === 0){
        list.innerHTML = `<div class="muted">No notifications. Enjoy the quiet while it lasts.</div>`;
        return;
      }
      list.innerHTML = state.notifications.map((n, idx) => {
        const isNewDevice = n.title.startsWith("New device detected:");
        const action = isNewDevice ? `<button class="btn btn-ghost purple" data-review="${idx}">Review</button>` : "";
        return `
          <div class="list-item">
            <div class="line1"><span>${escapeHtml(n.title)}</span><span style="color:rgba(255,255,255,0.62);">${escapeHtml(n.when)}</span></div>
            <div class="line2">${escapeHtml(n.detail || "")}</div>
            ${action ? `<div class="action">${action}</div>` : ``}
          </div>
        `;
      }).join("");

      $$("[data-review]").forEach(b => {
        b.addEventListener("click", () => {
          closeOverlay("notifOverlay");
          setTab("devices");
          setFilter("unknown");
        });
      });
    }

    // ---- Overlays wiring ----
    $("#historyBtn").addEventListener("click", () => openOverlay("historyOverlay"));
    $("#notifBtn").addEventListener("click", () => openOverlay("notifOverlay"));
    $("#settingsBtn").addEventListener("click", () => openOverlay("settingsOverlay"));

    // close buttons
    $$("[data-close]").forEach(b => b.addEventListener("click", () => closeOverlay(b.dataset.close)));

    // overlay click-out
    $$(".overlay").forEach(ov => {
      ov.addEventListener("click", (e) => {
        if(e.target === ov) closeOverlay(ov.id);
      });
    });

    // ---- Utility renderers ----
    function kvLines(pairs){
      return pairs.map(([k,v]) => `
        <div class="line"><span class="k">${escapeHtml(k)}</span><span class="v">${escapeHtml(String(v ?? "—"))}</span></div>
      `).join("");
    }

    function escapeHtml(s){
      return String(s ?? "").replace(/[&<>"']/g, (c) => ({
        "&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"
      }[c]));
    }

    function typeIcon(type){
      const svgs = {
        Phone:'<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>',
        PC:'<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>',
        TV:'<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"/><polyline points="17 2 12 7 7 2"/></svg>',
        Printer:'<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/></svg>',
        IoT:'<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>'
      };
      return svgs[type] || '<svg class="type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>';
    }

    function selectOptions(options, value){
      return options.map(o => {
        const label = o === "" ? "None" : o;
        return `<option value="${escapeHtml(o)}" ${o === value ? "selected" : ""}>${escapeHtml(label)}</option>`;
      }).join("");
    }

    function downloadText(text, filename, mime){
      const blob = new Blob([text], { type: mime || "text/plain" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename || "download.txt";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
    }

    function reportColumns(include){
      if(include === "Devices + activity"){
        return ["name","owner","type","status","ip","mac","vendor","lastSeen","activityToday","traffic","trust"];
      }
      if(include === "Everything"){
        return ["name","owner","type","status","ip","mac","vendor","os","lastSeen","activityToday","traffic","trust"];
      }
      return ["name","owner","type","status","ip","mac","vendor","lastSeen","trust"];
    }

    function makeDevicesCsv(list = devices, include = "Devices"){
      const headers = reportColumns(include);
      const lines = [headers.join(",")];
      list.forEach(d => {
        lines.push(headers.map(h => csvCell(d[h])).join(","));
      });
      return lines.join("\n");
    }
    function csvCell(v){
      const s = String(v ?? "");
      if(/[",\n]/.test(s)) return `"${s.replace(/"/g,'""')}"`;
      return s;
    }

    function makeDevicesHtml(list = devices, include = "Devices"){
      const headers = reportColumns(include);
      const headerRow = headers.map(h => `<th>${escapeHtml(h)}</th>`).join("");
      const rows = list.map(d => `
        <tr>
          ${headers.map(h => `<td>${escapeHtml(String(d[h] ?? ""))}</td>`).join("")}
        </tr>
      `).join("");
      return `<!doctype html>
<html><head><meta charset="utf-8"><title>Net Ninja Report</title></head>
<body><table border="1" cellspacing="0" cellpadding="4">
<thead><tr>${headerRow}</tr></thead>
<tbody>${rows}</tbody>
</table></body></html>`;
    }

    function makeDevicesTxt(list = devices, include = "Devices"){
      const headers = reportColumns(include);
      return list.map(d => {
        const parts = headers.map(h => `${h}: ${d[h] ?? ""}`);
        return parts.join(" | ");
      }).join("\n");
    }

    async function checkDbNotice(){
      try{
        const state = await fetchJson("/api/v1/system/state", { cache: "no-store", timeoutMs: 2500 });
        const notice = state?.dbNotice;
        if(!notice || !notice.atMs) return;

        const key = "nn_db_notice_seen_" + String(notice.atMs);
        try {
          if(localStorage.getItem(key)) return;
          localStorage.setItem(key, "1");
        } catch(_) {}

        const title = notice.level === "error" ? "Data reset" : "Database notice";
        showToast(title, String(notice.message || "Database notice."));
        pushNotification({ when: nowLabel(), title, detail: String(notice.message || "") });
      } catch (_) {}
    }


    // ---- Gateway (G5AR) ----
    let g5arScreenState = null;

    function setText(id, value){
      const el = document.getElementById(id);
      if(el) el.textContent = (value === null || value === undefined || value === "") ? "—" : String(value);
    }

    function setVisible(id, visible){
      const el = document.getElementById(id);
      if(el) el.style.display = visible ? "" : "none";
    }

    function renderG5ar(){
      const data = g5arScreenState || {};
      const caps = data.capabilities || {};
      setText("g5arReachability", data.reachable ? "Reachable" : "Unreachable");
      setText("g5arError", data.error || (data.loggedIn ? "Connected." : "Login required."));
      setText("g5arFirmware", data.gatewayInfo?.firmware);
      setText("g5arUiVersion", data.gatewayInfo?.uiVersion);
      setText("g5arSerial", data.gatewayInfo?.serial);
      setText("g5arUptime", data.gatewayInfo?.uptime);
      setText("g5arRsrp", data.cellTelemetry?.rsrp);
      setText("g5arRsrq", data.cellTelemetry?.rsrq);
      setText("g5arSinr", data.cellTelemetry?.sinr);
      setText("g5arBand", data.cellTelemetry?.band);
      setText("g5arIccid", data.simInfo?.iccid);
      setText("g5arImei", data.simInfo?.imei);

      const clients = Array.isArray(data.clients) ? data.clients : [];
      const clientsEl = document.getElementById("g5arClients");
      if(clientsEl){
        clientsEl.innerHTML = clients.length
          ? clients.map((c) => `${escapeHtml(c.name || "Client")} • ${escapeHtml(c.ip || "—")} • ${escapeHtml(c.mac || "—")} • ${escapeHtml(c.signal || "—")}`).join("<br>")
          : "No clients reported.";
      }

      const wifi = data.wifiConfig || {};
      const ssid24 = document.getElementById("g5arSsid24");
      const pass24 = document.getElementById("g5arPass24");
      const enabled24 = document.getElementById("g5arEnabled24");
      if(ssid24 && !ssid24.matches(":focus")) ssid24.value = wifi.ssid24 || "";
      if(pass24 && !pass24.matches(":focus")) pass24.value = wifi.pass24 || "";
      if(enabled24) enabled24.checked = !!wifi.enabled24;

      setVisible("g5arInfoCard", !!caps.canViewGatewayInfo);
      setVisible("g5arClientsCard", !!caps.canViewClients);
      setVisible("g5arCellCard", !!caps.canViewCellTelemetry);
      setVisible("g5arSimCard", !!caps.canViewSimInfo);
      setVisible("g5arWifiCard", !!caps.canViewWifiConfig);
      setVisible("g5arRebootBtn", !!caps.canReboot);
      setVisible("g5arSaveWifiBtn", !!caps.canSetWifiConfig);
    }

    async function refreshG5ar(){
      try {
        g5arScreenState = await fetchJson("/api/v1/g5ar/screen", { cache: "no-store", timeoutMs: 5000 });
        renderG5ar();
      } catch (e) {
        showToast("Gateway", `Failed to refresh: ${e.message}`);
      }
    }

    function setupG5arControls(){
      const loginBtn = document.getElementById("g5arLoginBtn");
      const refreshBtn = document.getElementById("g5arRefreshBtn");
      const saveWifiBtn = document.getElementById("g5arSaveWifiBtn");
      const rebootBtn = document.getElementById("g5arRebootBtn");
      if(loginBtn){
        loginBtn.addEventListener("click", async () => {
          const username = document.getElementById("g5arUser")?.value || "admin";
          const password = document.getElementById("g5arPassword")?.value || "";
          const remember = !!document.getElementById("g5arRemember")?.checked;
          if(!password){ showToast("Gateway", "Password is required."); return; }
          await fetchJson("/api/v1/g5ar/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username, password, remember })
          });
          showToast("Gateway", "Login succeeded.");
          refreshG5ar();
        });
      }
      if(refreshBtn){ refreshBtn.addEventListener("click", refreshG5ar); }
      if(saveWifiBtn){
        saveWifiBtn.addEventListener("click", async () => {
          await fetchJson("/api/v1/g5ar/wifi", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              ssid24: document.getElementById("g5arSsid24")?.value || null,
              pass24: document.getElementById("g5arPass24")?.value || null,
              enabled24: !!document.getElementById("g5arEnabled24")?.checked,
              raw: (g5arScreenState && g5arScreenState.wifiConfig && g5arScreenState.wifiConfig.raw) || {}
            })
          });
          showToast("Gateway", "Wi-Fi settings updated.");
          refreshG5ar();
        });
      }
      if(rebootBtn){
        rebootBtn.addEventListener("click", async () => {
          if(!confirm("Reboot the gateway now?")) return;
          await fetchJson("/api/v1/g5ar/reboot", { method: "POST" });
          showToast("Gateway", "Reboot command sent.");
        });
      }
    }

    // ---- Settings / initial state ----
    function init(){
      // initial notification
      pushNotification({ when: nowLabel(), title: "Welcome", detail: "Scan your network to start discovering devices." });

      // initial dashboard rendering
      renderDashboard();
      renderDevices();
      renderNetworks();
      renderHistory();
      renderNotifications();

      // default text while loading
      $("#myNetText").textContent = "Loading network…";
      $("#brandSub").textContent = "Loading network…";

      // Devices: sidepanel tabs default (already summary)
      renderSidePanel();

      // Basic "settings icon instead of a tab" is already top-right.

      // hydrate from backend if available
      refreshNetworkInfo();
      refreshDiscoveryResults();
      refreshPermissionStatus();
      checkDbNotice();
      setupG5arControls();
      refreshG5ar();
      setupPermissionControls();
      setupDebugPanel();
      refreshDebugState();

      // live scan progress + periodic refresh
      window.__nnScanPollConfig = { url: "/api/v1/discovery/progress", intervalMs: 600 };
      if(window.startPolling && !window.__nnScanPollStarted){
        window.startPolling(window.__nnScanPollConfig.url, window.__nnScanPollConfig.intervalMs);
        window.__nnScanPollStarted = true;
      }

      // Visibility-aware 20-second polling — pauses when tab/screen is hidden
      let _dashPollTimer = null;

      function _dashPollTick() {
        refreshNetworkInfo();
        refreshDiscoveryResults();
        refreshPermissionStatus();
        refreshDebugState();
      }

      function _startDashPoll() {
        if (_dashPollTimer) return;
        _dashPollTick();                          // immediate refresh on becoming visible
        _dashPollTimer = setInterval(_dashPollTick, 20_000);
      }

      function _stopDashPoll() {
        if (_dashPollTimer) { clearInterval(_dashPollTimer); _dashPollTimer = null; }
      }

      document.addEventListener("visibilitychange", () => {
        if (document.hidden) _stopDashPoll(); else _startDashPoll();
      });

      if (!document.hidden) _startDashPoll();
    }

    init();
  

  // =========================
  // Net Ninja: Integrated UI wiring (prog.html + holo_map.html)
  // =========================
  (function(){
    "use strict";

    // ---------- Scan progress wiring (prog.html behavior, adapted to this dashboard) ----------
    const elPhase = document.getElementById("scanPhase");
    const elPct = document.getElementById("scanPct");
    const elFill = document.getElementById("scanFill");
    const elNetworks = document.getElementById("networksFound");
    const elDevices = document.getElementById("devicesFound");
    const elRssi = document.getElementById("rssiValue");
    const elSsid = document.getElementById("ssid");
    const elBssid = document.getElementById("bssid");
    const elSubnet = document.getElementById("subnet");
    const elGateway = document.getElementById("gateway");
    const elPill = document.getElementById("nnScanPill");
    const elLink = document.getElementById("nnLinkValue");

    const btnStart = document.getElementById("btnStart");
    const btnStop = document.getElementById("btnStop");

    let pollingTimer = null;
    let ws = null;


    function clampNumber(v, min, max){
      const n = Number(v);
      if(!Number.isFinite(n)) return min;
      return Math.max(min, Math.min(max, n));
    }

    function setPill(phase, pct){
      const p = clampNumber(pct, 0, 100);
      if(p >= 100){
        elPill.textContent = "Complete";
        elPill.className = "pill ok";
        return;
      }
      if(phase && phase !== "IDLE"){
        elPill.textContent = "Scanning";
        elPill.className = "pill warn";
      } else {
        elPill.textContent = "Idle";
        elPill.className = "pill";
      }
    }

    // Public API: call with real scan data payload.
    // { progress, phase, networks, devices, rssiDbm, ssid, bssid, subnet, gateway, linkUp }
    window.applyScanData = function applyScanData(data){
      if(!data || typeof data !== "object") return;

      const p = clampNumber(data.progress, 0, 100);
      elFill.style.width = p + "%";
      elPct.textContent = Math.round(p) + "%";

      if(typeof data.phase === "string" && data.phase.trim()){
        elPhase.textContent = data.phase.trim().toUpperCase();
      }

      if(Number.isFinite(data.networks)) elNetworks.textContent = String(Math.max(0, Math.floor(data.networks)));
      if(Number.isFinite(data.devices)) elDevices.textContent = String(Math.max(0, Math.floor(data.devices)));

      if(Number.isFinite(data.rssiDbm)){
        elRssi.textContent = Math.round(data.rssiDbm) + " dBm";
      } else if (Number.isNaN(Number(data.rssiDbm))){
        elRssi.textContent = "-- dBm";
      }

      if(typeof data.ssid === "string") elSsid.textContent = data.ssid || "--";
      if(typeof data.bssid === "string") elBssid.textContent = data.bssid || "--";
      if(typeof data.subnet === "string") elSubnet.textContent = data.subnet || "--";
      if(typeof data.gateway === "string") elGateway.textContent = data.gateway || "--";

      if(typeof data.linkUp === "boolean"){
        elLink.textContent = data.linkUp ? "UP" : "DOWN";
        elLink.style.color = data.linkUp ? "var(--neon-mint)" : "var(--neon-purple)";
      }

      if(p >= 100){
        elPhase.textContent = "COMPLETE";
      }
      setPill(elPhase.textContent, p);
    };

    function stopAll(){
      if(pollingTimer){ clearInterval(pollingTimer); pollingTimer = null; }
      if(ws){ try{ ws.close(); } catch(_){} ws = null; }
    }

    // Real wiring option A: Polling
    window.startPolling = function startPolling(url, intervalMs = 400){
      stopAll();
      pollingTimer = setInterval(async () => {
        try{
          const data = await fetchJson(url, { cache: "no-store" });
          window.applyScanData(data);
        } catch (_) {}
      }, Math.max(200, intervalMs));
    };

    if(window.__nnScanPollConfig && !window.__nnScanPollStarted){
      window.startPolling(window.__nnScanPollConfig.url, window.__nnScanPollConfig.intervalMs);
      window.__nnScanPollStarted = true;
    }

    // Real wiring option B: WebSocket
    window.connectWebSocket = function connectWebSocket(wsUrl){
      stopAll();
      try{
        ws = new WebSocket(wsUrl);
        ws.onopen = () => {
          // Authenticate over the open connection instead of via URL query param
          if (NN_TOKEN) {
            try { ws.send(JSON.stringify({ type: "auth", token: NN_TOKEN })); } catch (_) {}
          }
        };
        ws.onmessage = (ev) => {
          try{
            const data = JSON.parse(ev.data);
            window.applyScanData(data);
          } catch(_) {}
        };
        ws.onclose = () => { ws = null; };
        ws.onerror = () => { /* neon stoicism */ };
      } catch(_) { ws = null; }
    };

    btnStart?.addEventListener("click", () => {
      window.nnRunScan?.("my");
    });
    btnStop?.addEventListener("click", () => {
      stopAll();
      window.nnStopScan?.();
    });

    // Initial state
    window.applyScanData({ progress: 0, phase: "IDLE", networks: 0, devices: 0, rssiDbm: NaN, linkUp: true });


    // ---------- Discovery Map wiring (ninja_nodes.html via iframe) ----------
    // The 3D map now lives in new_assets/ninja_nodes.html, loaded via iframe.
    // Forward the device list into the iframe when it's ready.
    window.nnUpdateDiscoveryMap = function(){
      try {
        const list = Array.isArray(window.devices) ? window.devices :
                     (Array.isArray(window.__nnDevices) ? window.__nnDevices : []);
        ["nnMapIframe", "nnDeviceMapIframe"].forEach((id)=>{
          const frame = document.getElementById(id);
          if(frame && frame.contentWindow && typeof frame.contentWindow.nnUpdateDiscoveryMap === "function"){
            frame.contentWindow.nnUpdateDiscoveryMap(Array.isArray(list) ? list : []);
          }
        });
      } catch(e){ console.warn("[DiscoveryMap] forward error:", e); }
    };
    // Retry forwarding after iframe load
    document.getElementById("nnMapIframe")?.addEventListener("load", () => {
      setTimeout(() => window.nnUpdateDiscoveryMap?.(), 200);
    });
  })();
  
