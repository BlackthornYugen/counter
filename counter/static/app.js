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
  const btnReset = document.getElementById("btn-reset");

  const canWrite = (role === "admin" || role === "read_write");
  const canAdmin = (role === "admin");

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

  if (canAdmin) {
    permAdmin.className = "perm-badge allowed";
    permAdmin.textContent = "✓ Reset / Admin";
    btnReset.disabled = false;
  } else {
    permAdmin.className = "perm-badge";
    permAdmin.textContent = "✕ Reset Blocked";
    btnReset.disabled = true;
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
}

function connectRoom() {
  disconnectRoom();
  const token = activeTokens[currentRole];
  if (!token) {
    showToast(`No token available for role '${currentRole}'. Please register a counter first.`, "error");
    return;
  }

  updateStatusPill("connecting", "Connecting...");
  applyRolePermissions(currentRole);

  if (currentProtocol === "ws") {
    connectRoomWebSocket(token);
  } else {
    connectRoomSSE(token);
  }
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
    document.getElementById("counter-room-name").textContent = data.counter_name || "Counter Room";
    if (data.recent_events && Array.isArray(data.recent_events)) {
      document.getElementById("stream-feed").innerHTML = "";
      data.recent_events.forEach(ev => addEventToStream(ev));
    }
  } else if (data.type === "update") {
    updateCounterDisplay(data.value, data.delta);
    addEventToStream(data);
  } else if (data.type === "action_ack") {
    // Acknowledged
  } else if (data.type === "error") {
    showToast(`[${data.code || 'Error'}] ${data.message}`, "error");
  }
}

// --- Action Triggers (WS send or REST POST) ---

async function sendCounterAction(action, delta = 1, targetValue = 0) {
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
      value: targetValue
    }));
    return;
  }

  // Otherwise fallback/use REST endpoint
  try {
    let url = `/api/counters/${token}/${action}`;
    let body = action === "reset" ? { value: targetValue } : { delta: delta };
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });

    const data = await res.json();
    if (!res.ok) {
      showToast(data.detail || "Action failed", "error");
    }
  } catch (err) {
    showToast(`Network error: ${err.message}`, "error");
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
  // Populate default UUIDs
  document.getElementById("reg-ro-uuid").value = generateUUID();
  document.getElementById("reg-rw-uuid").value = generateUUID();
  document.getElementById("reg-admin-uuid").value = generateUUID();

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

  // UUID generator buttons
  document.getElementById("gen-ro-uuid").addEventListener("click", () => {
    document.getElementById("reg-ro-uuid").value = generateUUID();
  });
  document.getElementById("gen-rw-uuid").addEventListener("click", () => {
    document.getElementById("reg-rw-uuid").value = generateUUID();
  });
  document.getElementById("gen-admin-uuid").addEventListener("click", () => {
    document.getElementById("reg-admin-uuid").value = generateUUID();
  });

  // Register Form Submit
  document.getElementById("btn-register").addEventListener("click", async () => {
    const name = document.getElementById("reg-name").value.trim() || "Counter Room";
    const ro = document.getElementById("reg-ro-uuid").value.trim();
    const admin = document.getElementById("reg-admin-uuid").value.trim();
    const rw = currentMode === "3uuid" ? document.getElementById("reg-rw-uuid").value.trim() : null;

    if (!ro || !admin || (currentMode === "3uuid" && !rw)) {
      showToast("Please fill in all UUID fields.", "error");
      return;
    }

    try {
      const payload = {
        name: name,
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
        showToast(data.detail || "Registration failed.", "error");
        return;
      }

      showToast("Counter Room registered! UUID hashes saved to SQLite.", "success");
      activeCounterId = data.counter_id;
      activeTokens = data.roles;

      // Populate registration summary box
      document.getElementById("reg-result").style.display = "block";
      document.getElementById("res-counter-id").textContent = `ID: ${data.counter_id}`;
      const tbody = document.getElementById("token-table-body");
      tbody.innerHTML = "";

      for (const [role, token] of Object.entries(data.roles)) {
        const hash = data.token_hashes[role];
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td><span class="label-role-tag tag-${role === 'admin' ? 'admin' : (role === 'read_write' ? 'rw' : 'ro')}">${role}</span></td>
          <td class="code-cell" title="${token}">${token}</td>
          <td class="code-cell" title="${hash}">${hash.substring(0, 16)}...</td>
          <td>
            <button type="button" class="btn-secondary copy-btn" data-copy="${token}" style="padding:2px 8px; font-size:11px;">Copy</button>
          </td>
        `;
        tbody.appendChild(tr);
      }

      // Add copy listeners
      tbody.querySelectorAll(".copy-btn").forEach(btn => {
        btn.addEventListener("click", () => {
          navigator.clipboard.writeText(btn.getAttribute("data-copy"));
          showToast("Token copied to clipboard!", "info");
        });
      });

      // Connect live room automatically
      connectRoom();
      initSimulator();

    } catch (e) {
      showToast(`Error: ${e.message}`, "error");
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
    connectRoom();
  }

  document.getElementById("chip-admin").addEventListener("click", () => switchRole("admin"));
  document.getElementById("chip-rw").addEventListener("click", () => switchRole("read_write"));
  document.getElementById("chip-ro").addEventListener("click", () => switchRole("read_only"));

  // Counter Control Buttons
  document.getElementById("btn-inc-1").addEventListener("click", () => sendCounterAction("increment", 1));
  document.getElementById("btn-inc-10").addEventListener("click", () => sendCounterAction("increment", 10));
  document.getElementById("btn-dec-1").addEventListener("click", () => sendCounterAction("decrement", 1));
  document.getElementById("btn-dec-10").addEventListener("click", () => sendCounterAction("decrement", 10));
  document.getElementById("btn-reset").addEventListener("click", () => sendCounterAction("reset", 0, 0));

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

  // Trigger initial registration automatically on first load so user gets a fully active room right away
  document.getElementById("btn-register").click();
});
