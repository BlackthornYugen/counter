// Quantum Counter Client Application

let currentMode = "3uuid"; // "3uuid" or "2uuid"
let currentProtocol = "ws"; // "ws" or "sse"
let currentRole = "admin"; // "admin", "read_write", "read_only"
let activeTokens = {};
let activeCounterId = null;
let currentCounterValue = 0;

// Sockets and EventSources
let roomSocket = null;
let roomEventSource = null;

// Multi-Client Simulator handles
let simSocket = null;
let simEventSource = null;

// --- Utility Functions ---

function generateUUID() {
  if (window.crypto && window.crypto.randomUUID) {
    return window.crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

function showToast(message, type = "info") {
  const container = document.getElementById("toast-container");
  const toast = document.createElement("div");
  toast.className = `toast toast-${type}`;
  
  let icon = "ℹ️";
  if (type === "success") icon = "✓";
  if (type === "error") icon = "⚠️";

  toast.innerHTML = `<span>${icon}</span> <span>${message}</span>`;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(10px)";
    setTimeout(() => toast.remove(), 250);
  }, 3500);
}

function updateStatusPill(status, text) {
  const pill = document.getElementById("status-pill");
  const label = document.getElementById("status-text");
  pill.className = `status-pill ${status}`;
  label.textContent = text;
}

function updateCounterDisplay(newVal, delta = null) {
  const display = document.getElementById("counter-value-display");
  const oldVal = currentCounterValue;
  currentCounterValue = newVal;
  display.textContent = newVal;

  // Visual bump
  if (delta !== null && delta !== undefined) {
    if (delta > 0) {
      display.classList.add("bump-up");
      setTimeout(() => display.classList.remove("bump-up"), 250);
      spawnFloatingDelta(`+${delta}`, "pos");
    } else if (delta < 0) {
      display.classList.add("bump-down");
      setTimeout(() => display.classList.remove("bump-down"), 250);
      spawnFloatingDelta(`${delta}`, "neg");
    }
  }

  // Update simulator values if open
  const simWsVal = document.getElementById("sim-ws-val");
  const simSseVal = document.getElementById("sim-sse-val");
  if (simWsVal) simWsVal.textContent = newVal;
  if (simSseVal) simSseVal.textContent = newVal;
}

function spawnFloatingDelta(text, type) {
  const container = document.getElementById("delta-container");
  const badge = document.createElement("div");
  badge.className = `floating-delta ${type}`;
  badge.textContent = text;
  container.appendChild(badge);
  setTimeout(() => badge.remove(), 800);
}

function addEventToStream(event) {
  const feed = document.getElementById("stream-feed");
  const row = document.createElement("div");
  const action = event.action || event.type || "update";
  row.className = `event-row action-${action}`;

  const deltaStr = event.delta !== null && event.delta !== undefined 
    ? (event.delta > 0 ? `+${event.delta}` : `${event.delta}`) 
    : "";

  const timeStr = event.timestamp ? new Date(event.timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();

  row.innerHTML = `
    <div class="event-left">
      <span class="event-tag" style="color:var(--accent-cyan);">${action}</span>
      <span style="font-family:var(--font-mono); font-weight:600;">${deltaStr}</span>
      <span style="color:var(--text-muted);">value: <strong style="color:#fff;">${event.value !== undefined ? event.value : ""}</strong></span>
      <span class="label-role-tag tag-${event.role === 'admin' ? 'admin' : (event.role === 'read_write' ? 'rw' : 'ro')}">${event.role || 'system'}</span>
    </div>
    <div class="event-time">${timeStr}</div>
  `;

  feed.prepend(row);
  // Keep maximum 100 rows
  while (feed.children.length > 100) {
    feed.removeChild(feed.lastChild);
  }
}

// --- Permissions & UI Updates ---

function applyRolePermissions(role) {
  const roleDisplay = document.getElementById("active-role-display");
  const permWrite = document.getElementById("perm-write");
  const permAdmin = document.getElementById("perm-admin");

  const btnInc1 = document.getElementById("btn-inc-1");
  const btnInc10 = document.getElementById("btn-inc-10");
  const btnDec1 = document.getElementById("btn-dec-1");
  const btnDec10 = document.getElementById("btn-dec-10");
  const btnDelete = document.getElementById("btn-delete");

  if (role === "none" || !role) {
    roleDisplay.textContent = "None (Disconnected)";
    permWrite.className = "perm-badge";
    permWrite.textContent = "✕ Inc / Dec Blocked";
    permAdmin.className = "perm-badge";
    permAdmin.textContent = "✕ Delete Blocked";
    btnInc1.disabled = true;
    btnInc10.disabled = true;
    btnDec1.disabled = true;
    btnDec10.disabled = true;
    btnDelete.disabled = true;
    return;
  }

  const canWrite = (role === "admin" || role === "read_write");
  const canDelete = (role === "admin");

  roleDisplay.textContent = role === "admin" ? "Admin (Full Access)" : (role === "read_write" ? "Read-Write" : "Read-Only");

  if (canWrite) {
    permWrite.className = "perm-badge allowed";
    permWrite.textContent = "✓ Inc / Dec";
    btnInc1.disabled = false;
    btnInc10.disabled = false;
    btnDec1.disabled = false;
    btnDec10.disabled = false;
  } else {
    permWrite.className = "perm-badge";
    permWrite.textContent = "✕ Inc / Dec Blocked";
    btnInc1.disabled = true;
    btnInc10.disabled = true;
    btnDec1.disabled = true;
    btnDec10.disabled = true;
  }

  if (canDelete) {
    permAdmin.className = "perm-badge allowed";
    permAdmin.textContent = "✓ Delete Room";
    btnDelete.disabled = false;
  } else {
    permAdmin.className = "perm-badge";
    permAdmin.textContent = "✕ Delete Blocked";
    btnDelete.disabled = true;
  }
}

// --- Live Room Connection (WS / SSE) ---

function disconnectRoom() {
  if (roomSocket) {
    roomSocket.close();
    roomSocket = null;
  }
  if (roomEventSource) {
    roomEventSource.close();
    roomEventSource = null;
  }
  updateStatusPill("disconnected", "Disconnected");
  const activeBar = document.getElementById("active-link-bar");
  if (activeBar) activeBar.style.display = "none";
}

async function connectWithToken(token, updateHash = true) {
  if (!token) {
    showToast("Please enter a room access UUID.", "error");
    return;
  }
  const cleaned = token.trim().replace(/^[\/#]+/, "").trim();
  const UUIDV4_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  if (!UUIDV4_REGEX.test(cleaned)) {
    showToast("Provided token must be formatted as a valid UUIDv4.", "error");
    return;
  }

  updateStatusPill("connecting", "Verifying UUID...");

  try {
    const res = await fetch(`/api/counters/${cleaned}`);
    const data = await res.json();
    if (!res.ok) {
      updateStatusPill("disconnected", "Invalid UUID");
      showToast(data.detail || "Invalid access UUID or room does not exist.", "error");
      return;
    }

    activeCounterId = data.counter_id;
    currentRole = data.access;
    activeTokens[currentRole] = cleaned;

    document.getElementById("input-connect-uuid").value = cleaned;
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = `Counter ${data.counter_id.substring(0, 8)}`;
    updateCounterDisplay(data.value);
    applyRolePermissions(currentRole);

    // Update active role chip
    document.querySelectorAll(".chip-role").forEach(c => c.classList.remove("active"));
    const activeChip = document.querySelector(`.chip-role[data-role="${currentRole}"]`);
    if (activeChip) activeChip.classList.add("active");

    // Display active room hashtag link
    const activeBar = document.getElementById("active-link-bar");
    const activeAnchor = document.getElementById("active-hashtag-anchor");
    if (activeBar && activeAnchor) {
      activeBar.style.display = "flex";
      activeAnchor.href = `#${cleaned}`;
      activeAnchor.textContent = `#${cleaned}`;
    }

    // Update URL hashtag
    if (updateHash && window.location.hash !== `#${cleaned}`) {
      window.location.hash = `#${cleaned}`;
    }

    // Save to localStorage
    try { localStorage.setItem("counter_last_token", cleaned); } catch(e) {}

    disconnectRoom();
    if (currentProtocol === "ws") {
      connectRoomWebSocket(cleaned);
    } else {
      connectRoomSSE(cleaned);
    }

  } catch (err) {
    updateStatusPill("disconnected", "Network Error");
    showToast(`Network error: ${err.message}`, "error");
  }
}

function connectRoom() {
  const token = activeTokens[currentRole] || document.getElementById("input-connect-uuid").value.trim();
  if (!token) {
    showToast("Please enter an access UUID or register a counter first.", "error");
    return;
  }
  connectWithToken(token, true);
}

function connectRoomWebSocket(token) {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const wsUrl = `${protocol}//${window.location.host}/ws/${token}`;
  
  roomSocket = new WebSocket(wsUrl);

  roomSocket.onopen = () => {
    updateStatusPill("connected", "Connected (WebSocket)");
    showToast(`Connected via WebSocket as '${currentRole}'`, "success");
  };

  roomSocket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      handleIncomingMessage(data);
    } catch (e) {
      console.error("WS Parse error:", e);
    }
  };

  roomSocket.onerror = (e) => {
    console.error("WS Error:", e);
    updateStatusPill("disconnected", "WS Error");
  };

  roomSocket.onclose = () => {
    updateStatusPill("disconnected", "Disconnected");
  };
}

function connectRoomSSE(token) {
  const sseUrl = `/sse/${token}`;
  roomEventSource = new EventSource(sseUrl);

  roomEventSource.onopen = () => {
    updateStatusPill("connected", "Connected (SSE)");
    showToast(`Connected via Server-Sent Events as '${currentRole}'`, "success");
  };

  roomEventSource.addEventListener("connected", (event) => {
    const data = JSON.parse(event.data);
    handleIncomingMessage(data);
  });

  roomEventSource.addEventListener("update", (event) => {
    const data = JSON.parse(event.data);
    handleIncomingMessage(data);
  });

  roomEventSource.onerror = (err) => {
    console.error("SSE Error:", err);
    updateStatusPill("disconnected", "SSE Disconnected");
  };
}

function handleIncomingMessage(data) {
  if (data.type === "connected") {
    updateCounterDisplay(data.value);
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = `Counter ${data.counter_id.substring(0, 8)}`;
    if (data.recent_events && Array.isArray(data.recent_events)) {
      document.getElementById("stream-feed").innerHTML = "";
      data.recent_events.forEach(ev => addEventToStream(ev));
    }
  } else if (data.type === "update") {
    updateCounterDisplay(data.value, data.delta);
    addEventToStream(data);
  } else if (data.type === "deleted") {
    showToast(`Counter room was deleted: ${data.message || ''}`, "error");
    disconnectRoom();
    applyRolePermissions("none");
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = "Room Deleted";
    updateCounterDisplay(0);
    const activeBar = document.getElementById("active-link-bar");
    if (activeBar) activeBar.style.display = "none";
    if (window.location.hash) {
      history.replaceState(null, "", window.location.pathname);
    }
  } else if (data.type === "action_ack") {
    // Acknowledged
  } else if (data.type === "error") {
    showToast(`[${data.code || 'Error'}] ${data.message}`, "error");
  }
}

// --- Action Triggers (WS send or REST POST) ---

async function sendCounterAction(action, delta = 1) {
  const token = activeTokens[currentRole];
  if (!token) {
    showToast("No active token. Please register or select a counter.", "error");
    return;
  }

  // If using WebSocket and connected, send over WS
  if (currentProtocol === "ws" && roomSocket && roomSocket.readyState === WebSocket.OPEN) {
    roomSocket.send(JSON.stringify({
      action: action,
      delta: delta,
    }));
    return;
  }

  // Otherwise fallback/use REST endpoint
  try {
    const url = `/api/counters/${token}/${action}`;
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ delta: delta }),
    });

    const data = await res.json();
    if (!res.ok) {
      showToast(data.detail || "Action failed", "error");
    }
  } catch (err) {
    showToast(`Network error: ${err.message}`, "error");
  }
}

async function deleteCounterRoom() {
  const token = activeTokens[currentRole] || document.getElementById("input-connect-uuid").value.trim();
  if (!token) {
    showToast("No active room to delete.", "error");
    return;
  }
  if (currentRole !== "admin") {
    showToast("Only 'admin' role can delete the counter room.", "error");
    return;
  }

  const confirmed = window.confirm("Are you sure you want to permanently delete this counter room? This cannot be undone.");
  if (!confirmed) return;

  try {
    const res = await fetch(`/api/counters/${token}`, {
      method: "DELETE",
    });
    const data = await res.json();
    if (!res.ok) {
      showToast(data.detail || "Failed to delete room.", "error");
      return;
    }

    showToast("Counter room deleted successfully.", "success");
    disconnectRoom();
    applyRolePermissions("none");
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = "Room Deleted";
    updateCounterDisplay(0);
    const activeBar = document.getElementById("active-link-bar");
    if (activeBar) activeBar.style.display = "none";
    const regResult = document.getElementById("reg-result");
    if (regResult) regResult.style.display = "none";
    activeTokens = {};
    activeCounterId = null;
    if (window.location.hash) {
      history.replaceState(null, "", window.location.pathname);
    }
  } catch (err) {
    showToast(`Error deleting room: ${err.message}`, "error");
  }
}

// --- Multi-Client Cross-Protocol Simulator ---

function initSimulator() {
  const adminToken = activeTokens["admin"];
  const roToken = activeTokens["read_only"];
  if (!adminToken || !roToken) return;

  // 1. Sim Client 1: Admin via WebSocket
  if (simSocket) simSocket.close();
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  simSocket = new WebSocket(`${protocol}//${window.location.host}/ws/${adminToken}`);

  simSocket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data.value !== undefined) {
        document.getElementById("sim-ws-val").textContent = data.value;
      }
    } catch(e) {}
  };

  // 2. Sim Client 2: Read-Only via SSE
  if (simEventSource) simEventSource.close();
  simEventSource = new EventSource(`/sse/${roToken}`);
  simEventSource.addEventListener("connected", (event) => {
    const data = JSON.parse(event.data);
    document.getElementById("sim-sse-val").textContent = data.value;
  });
  simEventSource.addEventListener("update", (event) => {
    const data = JSON.parse(event.data);
    document.getElementById("sim-sse-val").textContent = data.value;
  });
}

// --- Event Listeners and Initialization ---

document.addEventListener("DOMContentLoaded", () => {
  // Inputs start empty on load; users can click the generator buttons when needed

  // "Generate All" button
  const btnGenAll = document.getElementById("btn-gen-all");
  if (btnGenAll) {
    btnGenAll.addEventListener("click", () => {
      document.getElementById("reg-ro-uuid").value = generateUUID();
      if (currentMode === "3uuid") {
        document.getElementById("reg-rw-uuid").value = generateUUID();
      }
      document.getElementById("reg-admin-uuid").value = generateUUID();
      showToast("Generated new UUIDv4 tokens for all fields.", "info");
    });
  }

  // Mode toggles (3 UUIDs vs 2 UUIDs)
  document.getElementById("btn-mode-3uuid").addEventListener("click", () => {
    currentMode = "3uuid";
    document.getElementById("btn-mode-3uuid").classList.add("active");
    document.getElementById("btn-mode-2uuid").classList.remove("active");
    document.getElementById("group-rw-uuid").style.display = "block";
    document.getElementById("chip-rw").style.display = "flex";
  });

  document.getElementById("btn-mode-2uuid").addEventListener("click", () => {
    currentMode = "2uuid";
    document.getElementById("btn-mode-2uuid").classList.add("active");
    document.getElementById("btn-mode-3uuid").classList.remove("active");
    document.getElementById("group-rw-uuid").style.display = "none";
    document.getElementById("chip-rw").style.display = "none";
    if (currentRole === "read_write") {
      switchRole("admin");
    }
  });

  // Individual UUID generator buttons
  document.getElementById("gen-ro-uuid").addEventListener("click", () => {
    document.getElementById("reg-ro-uuid").value = generateUUID();
  });
  document.getElementById("gen-rw-uuid").addEventListener("click", () => {
    document.getElementById("reg-rw-uuid").value = generateUUID();
  });
  document.getElementById("gen-admin-uuid").addEventListener("click", () => {
    document.getElementById("reg-admin-uuid").value = generateUUID();
  });

  const UUIDV4_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  // Register Form Submit
  document.getElementById("btn-register").addEventListener("click", async () => {
    const ro = document.getElementById("reg-ro-uuid").value.trim();
    const admin = document.getElementById("reg-admin-uuid").value.trim();
    const rw = currentMode === "3uuid" ? document.getElementById("reg-rw-uuid").value.trim() : null;

    if (!ro || !admin || (currentMode === "3uuid" && !rw)) {
      showToast("Please fill in all UUID fields (or click 🎲 to generate).", "error");
      return;
    }

    if (!UUIDV4_REGEX.test(ro)) {
      showToast("Read-Only UUID must be formatted as a valid UUIDv4.", "error");
      return;
    }
    if (rw && !UUIDV4_REGEX.test(rw)) {
      showToast("Read-Write UUID must be formatted as a valid UUIDv4.", "error");
      return;
    }
    if (!UUIDV4_REGEX.test(admin)) {
      showToast("Admin UUID must be formatted as a valid UUIDv4.", "error");
      return;
    }

    try {
      const payload = {
        read_only_uuid: ro,
        admin_uuid: admin,
      };
      if (rw) payload.read_write_uuid = rw;

      const res = await fetch("/api/counters/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      const data = await res.json();
      if (!res.ok) {
        if (res.status === 409) {
          showToast(data.detail || "UUID already registered. Connect to the room using this UUID.", "error");
          document.getElementById("input-connect-uuid").value = admin;
        } else {
          showToast(data.detail || "Registration failed.", "error");
        }
        return;
      }

      showToast("Counter Room registered! UUID hashes saved to SQLite.", "success");
      activeCounterId = data.counter_id;
      activeTokens = data.roles;

      // Populate registration summary box
      document.getElementById("reg-result").style.display = "block";
      document.getElementById("res-counter-id").textContent = `ID: ${data.counter_id}`;
      
      // Populate role link cards linking to #<uuid>
      const roleCardsContainer = document.getElementById("reg-role-cards");
      if (roleCardsContainer) {
        roleCardsContainer.innerHTML = "";
        const roleList = currentMode === "3uuid" ? ["admin", "read_write", "read_only"] : ["admin", "read_only"];
        for (const role of roleList) {
          const token = data.roles[role];
          if (!token) continue;
          const icon = role === "admin" ? "👑" : (role === "read_write" ? "✍️" : "👁️");
          const title = role === "admin" ? "Admin" : (role === "read_write" ? "Read-Write" : "Read-Only");
          const desc = role === "admin" ? "Full Control" : (role === "read_write" ? "Write & Read" : "View Only");
          const shareUrl = `${window.location.origin}${window.location.pathname}#${token}`;

          const card = document.createElement("a");
          card.href = `#${token}`;
          card.className = `role-link-card card-role-${role}`;
          card.setAttribute("title", `Connect as ${title} using #${token}`);
          card.innerHTML = `
            <div class="role-card-top">
              <span class="role-card-badge">${icon} ${title}</span>
              <span class="label-role-tag tag-${role === 'admin' ? 'admin' : (role === 'read_write' ? 'rw' : 'ro')}">${desc}</span>
            </div>
            <div class="role-card-token">
              #${token}
            </div>
            <div class="role-card-actions">
              <span class="role-btn-action">⚡ Connect #${title}</span>
              <button type="button" class="role-btn-action btn-copy-card-link" data-link="${shareUrl}" title="Copy shareable link with hashtag">🔗 Copy Link</button>
            </div>
          `;
          roleCardsContainer.appendChild(card);
        }

        roleCardsContainer.querySelectorAll(".btn-copy-card-link").forEach(btn => {
          btn.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            navigator.clipboard.writeText(btn.getAttribute("data-link"));
            showToast("Shareable link with hashtag copied to clipboard!", "info");
          });
        });
      }

      // Populate hash table
      const tbody = document.getElementById("token-table-body");
      tbody.innerHTML = "";
      for (const [role, token] of Object.entries(data.roles)) {
        const hash = data.token_hashes[role];
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td><span class="label-role-tag tag-${role === 'admin' ? 'admin' : (role === 'read_write' ? 'rw' : 'ro')}">${role}</span></td>
          <td class="code-cell" title="${token}"><a href="#${token}" style="color:var(--text-main); text-decoration:none;">#${token}</a></td>
          <td class="code-cell" title="${hash}">${hash.substring(0, 16)}...</td>
          <td>
            <button type="button" class="btn-secondary copy-btn" data-copy="${token}" style="padding:2px 8px; font-size:11px;">Copy</button>
          </td>
        `;
        tbody.appendChild(tr);
      }

      tbody.querySelectorAll(".copy-btn").forEach(btn => {
        btn.addEventListener("click", () => {
          navigator.clipboard.writeText(btn.getAttribute("data-copy"));
          showToast("Token copied to clipboard!", "info");
        });
      });

      // Show role switcher chips since we have all keys in this session
      document.getElementById("role-switcher-container").style.display = "block";

      // Connect to live room using the admin token and update hashtag
      document.getElementById("input-connect-uuid").value = data.roles.admin;
      connectWithToken(data.roles.admin, true);
      initSimulator();

    } catch (e) {
      showToast(`Error: ${e.message}`, "error");
    }
  });

  // Connect With Existing UUID Button
  document.getElementById("btn-connect-uuid").addEventListener("click", () => {
    const val = document.getElementById("input-connect-uuid").value.trim();
    connectWithToken(val, true);
  });

  document.getElementById("input-connect-uuid").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      const val = document.getElementById("input-connect-uuid").value.trim();
      connectWithToken(val, true);
    }
  });

  // Protocol Switcher
  document.getElementById("btn-proto-ws").addEventListener("click", () => {
    currentProtocol = "ws";
    document.getElementById("btn-proto-ws").classList.add("active");
    document.getElementById("btn-proto-sse").classList.remove("active");
    connectRoom();
  });

  document.getElementById("btn-proto-sse").addEventListener("click", () => {
    currentProtocol = "sse";
    document.getElementById("btn-proto-sse").classList.add("active");
    document.getElementById("btn-proto-ws").classList.remove("active");
    connectRoom();
  });

  // Role Switcher Chips
  function switchRole(role) {
    currentRole = role;
    document.querySelectorAll(".chip-role").forEach(c => c.classList.remove("active"));
    const activeChip = document.querySelector(`.chip-role[data-role="${role}"]`);
    if (activeChip) activeChip.classList.add("active");
    const token = activeTokens[role];
    if (token) {
      window.location.hash = `#${token}`;
      connectWithToken(token, false);
    }
  }

  document.getElementById("chip-admin").addEventListener("click", () => switchRole("admin"));
  document.getElementById("chip-rw").addEventListener("click", () => switchRole("read_write"));
  document.getElementById("chip-ro").addEventListener("click", () => switchRole("read_only"));

  // Counter Control Buttons
  document.getElementById("btn-inc-1").addEventListener("click", () => sendCounterAction("increment", 1));
  document.getElementById("btn-inc-10").addEventListener("click", () => sendCounterAction("increment", 10));
  document.getElementById("btn-dec-1").addEventListener("click", () => sendCounterAction("decrement", 1));
  document.getElementById("btn-dec-10").addEventListener("click", () => sendCounterAction("decrement", 10));
  document.getElementById("btn-delete").addEventListener("click", () => deleteCounterRoom());

  // Simulator Buttons
  document.getElementById("sim-btn-inc").addEventListener("click", () => {
    if (simSocket && simSocket.readyState === WebSocket.OPEN) {
      simSocket.send(JSON.stringify({ action: "increment", delta: 1 }));
    }
  });
  document.getElementById("sim-btn-dec").addEventListener("click", () => {
    if (simSocket && simSocket.readyState === WebSocket.OPEN) {
      simSocket.send(JSON.stringify({ action: "decrement", delta: 1 }));
    }
  });

  // Clear stream feed
  document.getElementById("btn-clear-stream").addEventListener("click", () => {
    document.getElementById("stream-feed").innerHTML = "";
    showToast("Event stream feed cleared", "info");
  });

  // Hashtag extraction helper
  function getHashToken() {
    if (window.location.hash && window.location.hash.length > 1) {
      const raw = decodeURIComponent(window.location.hash.substring(1)).trim();
      return raw.replace(/^[\/#]+/, "").trim();
    }
    return null;
  }

  // Active link bar copy button
  const btnCopyActive = document.getElementById("btn-copy-active-link");
  if (btnCopyActive) {
    btnCopyActive.addEventListener("click", () => {
      const anchor = document.getElementById("active-hashtag-anchor");
      if (anchor && anchor.getAttribute("href") && anchor.getAttribute("href") !== "#") {
        const fullUrl = `${window.location.origin}${window.location.pathname}${anchor.getAttribute("href")}`;
        navigator.clipboard.writeText(fullUrl);
        showToast("Room share link with hashtag copied to clipboard!", "info");
      }
    });
  }

  // Listen to browser hash changes (e.g. user clicks back/forward or a role card #link)
  window.addEventListener("hashchange", () => {
    const hashToken = getHashToken();
    if (hashToken) {
      const activeToken = activeTokens[currentRole];
      if (hashToken !== activeToken) {
        document.getElementById("input-connect-uuid").value = hashToken;
        connectWithToken(hashToken, false);
      }
    }
  });

  // Initial State: inputs start blank, controls start disabled
  applyRolePermissions("none");
  const initialStatusEl = document.getElementById("counter-room-status");
  if (initialStatusEl) initialStatusEl.textContent = "No Room Connected";
  updateStatusPill("disconnected", "Not Connected");

  // Check if URL has hashtag #<uuid> on initial load
  const initialHash = getHashToken();
  if (initialHash) {
    document.getElementById("input-connect-uuid").value = initialHash;
    connectWithToken(initialHash, false);
  } else {
    // Check URL query parameters for ?token=<uuid> or ?uuid=<uuid>
    const urlParams = new URLSearchParams(window.location.search);
    const tokenParam = urlParams.get("token") || urlParams.get("uuid");
    if (tokenParam) {
      document.getElementById("input-connect-uuid").value = tokenParam;
      connectWithToken(tokenParam, true);
    }
  }
});
