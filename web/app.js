// JSKW Counter Client Application

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

const UUIDV4_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const UUID_GENERIC_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isValidUuid(token) {
  if (!token) return false;
  const cleaned = token.trim().replace(/^[\/#]+/, "").trim();
  return UUID_GENERIC_REGEX.test(cleaned);
}

// Parses up to 3 UUIDs from an anchor / string, assuming any non-uuidv4 char is a delimiter.
function normalizeHash(hash) {
  if (!hash) return "";
  try {
    return decodeURIComponent(hash);
  } catch (e) {
    return hash;
  }
}

// Parses up to 3 UUIDs from an anchor / string, assuming any non-uuidv4 char is a delimiter.
// Compresses multiple sequences of delimiters.
// If the first candidate isn't a valid UUID (e.g. vanity name), it safely ignores it without error.
function parseAnchorUuids(rawInput) {
  if (!rawInput) return [];
  let inputStr = rawInput;
  if (inputStr.includes("://")) {
    const hashIdx = inputStr.indexOf("#");
    if (hashIdx !== -1) {
      inputStr = inputStr.substring(hashIdx);
    }
  }

  let decoded = "";
  try {
    decoded = decodeURIComponent(inputStr);
  } catch (e) {
    decoded = inputStr;
  }
  try {
    if (decoded.includes("%20")) {
      decoded = decodeURIComponent(decoded);
    }
  } catch (e) {}

  const trimmed = decoded.replace(/^#+/, "").trim();
  if (!trimmed) return [];

  if (trimmed.toLowerCase() === "privacy" || trimmed.toLowerCase().startsWith("privacy")) {
    window.location.href = "/privacy";
    return [];
  }

  // Any character not valid in a UUIDv4 ([0-9a-fA-F-]) or sequences of multiple hyphens is a delimiter.
  const rawParts = trimmed.split(/[^0-9a-fA-F-]+|--+/);
  const uuids = [];

  for (const rawPart of rawParts) {
    const candidate = rawPart.replace(/^-+|-+$/g, "").trim();
    if (!candidate) continue;
    if (UUID_GENERIC_REGEX.test(candidate)) {
      uuids.push(candidate.toLowerCase());
      if (uuids.length === 3) {
        break; // Parse up to 3 of them and ignore any extras
      }
    }
  }

  return uuids;
}

let currentCounterLabel = null;

// Parses the counter label from the anchor (first non-UUID section if not a role keyword).
// Decodes URL-encoded characters (like %20 to space) first.
function parseAnchorLabel(rawInput) {
  if (!rawInput) return null;
  let inputStr = rawInput;
  if (inputStr.includes("://")) {
    const hashIdx = inputStr.indexOf("#");
    if (hashIdx !== -1) {
      inputStr = inputStr.substring(hashIdx);
    }
  }

  let decoded = "";
  try {
    decoded = decodeURIComponent(inputStr);
  } catch (e) {
    decoded = inputStr;
  }
  try {
    if (decoded.includes("%20")) {
      decoded = decodeURIComponent(decoded);
    }
  } catch (e) {}

  const trimmed = decoded.replace(/^#+/, "").trim();
  if (!trimmed) return null;

  if (trimmed.toLowerCase() === "privacy" || trimmed.toLowerCase().startsWith("privacy")) {
    return null;
  }

  // Split by '#'
  const sections = trimmed.split("#").map(s => s.trim()).filter(Boolean);
  if (sections.length === 0) return null;

  const firstSection = sections[0];

  // If the first section is a valid UUID, then it's not a label
  if (isValidUuid(firstSection)) {
    return null;
  }

  // Check if a UUID exists inside the first section (e.g. if delimited with hyphens, spaces, colons)
  const uuidMatch = firstSection.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  let candidateLabel = firstSection;
  if (uuidMatch) {
    if (firstSection.indexOf(uuidMatch[0]) === 0) {
      return null;
    }
    candidateLabel = firstSection.substring(0, firstSection.indexOf(uuidMatch[0])).trim().replace(/[-–—\s/!:]+$/, "").trim();
  }

  // Strip trailing role keywords if preceded by delimiter or boundary (e.g. "Counter 00fb10a2 read" -> "Counter 00fb10a2")
  candidateLabel = candidateLabel.replace(/(?:^|[-–—\s/!:#])(read_only|read_write|read|write|admin|ro|rw)$/i, "").trim().replace(/[-–—\s/!:]+$/, "").trim();

  if (!candidateLabel) return null;

  // If the label is a role keyword ("read", "write", "admin", "read_only", "read_write", "ro", "rw"), it's not a label
  const lower = candidateLabel.toLowerCase();
  if (lower === "read" || lower === "write" || lower === "admin" || lower === "read_only" || lower === "read_write" || lower === "ro" || lower === "rw") {
    return null;
  }

  return candidateLabel;
}

// Builds shareable hash containing the label as the first section and all lower-level UUIDs up to the given role.
// Encodes spaces in the label to "%20" so shared links are valid and robust.
// Admin: #<Label>#read#<uuid>#write#<uuid>#admin#<uuid>
// Read-Write: #<Label>#read#<uuid>#write#<uuid>
// Read-Only: #<Label>#read#<uuid>
function buildShareHash(role, tokens, customLabel = null) {
  if (!tokens) return "";
  const readToken = tokens.read_only || tokens.read;
  const writeToken = tokens.read_write || tokens.write;
  const adminToken = tokens.admin;

  const parts = [];

  // First section of shared links is the counter label (e.g. "Counter 00fb10a2")
  const rawLabel = customLabel || currentCounterLabel || (activeCounterId ? `Counter ${activeCounterId.substring(0, 8)}` : null);
  if (rawLabel) {
    let cleanLabel = rawLabel;
    try {
      cleanLabel = decodeURIComponent(rawLabel);
    } catch (e) {}
    parts.push(encodeURIComponent(cleanLabel));
  }

  if (role === "admin") {
    if (readToken) parts.push(`read#${readToken}`);
    if (writeToken) parts.push(`write#${writeToken}`);
    if (adminToken) parts.push(`admin#${adminToken}`);
  } else if (role === "read_write") {
    if (readToken) parts.push(`read#${readToken}`);
    if (writeToken) parts.push(`write#${writeToken}`);
  } else if (role === "read_only") {
    if (readToken) parts.push(`read#${readToken}`);
  }

  if (parts.length === 0 || (parts.length === 1 && rawLabel)) {
    const encodedLabel = parts.length === 1 ? parts[0] : "";
    const fallbackToken = tokens[role] || adminToken || writeToken || readToken;
    if (fallbackToken) {
      if (encodedLabel) return `#${encodedLabel}#${fallbackToken}`;
      return `#${fallbackToken}`;
    }
    return encodedLabel ? `#${encodedLabel}` : "";
  }

  return `#${parts.join("#")}`;
}

function updateShareLinks(role, tokens) {
  const shareHash = buildShareHash(role, tokens);
  const activeBar = document.getElementById("active-link-bar");
  const activeAnchor = document.getElementById("active-hashtag-anchor");
  if (activeBar && activeAnchor) {
    if (shareHash) {
      activeBar.style.display = "flex";
      activeAnchor.href = shareHash;
      activeAnchor.textContent = shareHash;
      activeAnchor.title = `Current room share link (${role})`;
    } else {
      activeBar.style.display = "none";
    }
  }
}

function renderRoleCards(tokens, counterId = null) {
  const regResult = document.getElementById("reg-result");
  const roleCardsContainer = document.getElementById("reg-role-cards");
  const counterIdEl = document.getElementById("res-counter-id");
  const resBoxTitle = document.getElementById("res-box-title");

  if (!tokens || Object.keys(tokens).length === 0) {
    if (regResult) regResult.style.display = "none";
    return;
  }

  if (regResult) {
    regResult.style.display = "block";
  }

  if (resBoxTitle) {
    resBoxTitle.textContent = "✓ Room Access & Share QR Codes";
  }

  if (counterIdEl && counterId) {
    counterIdEl.textContent = `ID: ${counterId}`;
  }

  const detailsHashes = document.getElementById("details-token-hashes");
  const tokenTableBody = document.getElementById("token-table-body");
  if (detailsHashes) {
    detailsHashes.style.display = (tokenTableBody && tokenTableBody.children.length > 0) ? "block" : "none";
  }

  if (!roleCardsContainer) return;
  roleCardsContainer.innerHTML = "";

  const DUMMY_QR_URL = "http://counter.jskw.dev";
  const possibleRoles = ["admin", "read_write", "read_only"];
  for (const role of possibleRoles) {
    const token = tokens[role];
    if (!token) continue;
    const icon = role === "admin" ? "👑" : (role === "read_write" ? "✍️" : "👁️");
    const title = role === "admin" ? "Admin" : (role === "read_write" ? "Read-Write" : "Read-Only");
    const desc = role === "admin" ? "Full Control" : (role === "read_write" ? "Write & Read" : "View Only");
    const shareHash = buildShareHash(role, tokens, currentCounterLabel);
    const shareUrl = `${window.location.origin}${window.location.pathname}${shareHash}`;

    // Admin and read-write are hidden by default; read-only is visible by default
    let isHidden = (role === "admin" || role === "read_write");

    const card = document.createElement("div");
    card.className = `role-link-card card-role-${role}`;

    // QR code colours per role (dark, high-contrast role tints on clean white background)
    const qrColors = {
      admin: { dark: "#be123c", light: "#ffffff" },
      read_write: { dark: "#6d28d9", light: "#ffffff" },
      read_only: { dark: "#0e7490", light: "#ffffff" },
    };
    const colors = qrColors[role];

    card.innerHTML = `
      <div class="role-card-top">
        <div style="display:flex; align-items:center; gap:8px;">
          <span class="role-card-badge">${icon} ${title}</span>
          <span class="label-role-tag tag-${role === 'admin' ? 'admin' : (role === 'read_write' ? 'rw' : 'ro')}">${desc}</span>
        </div>
        <button type="button" class="btn-role-toggle" id="toggle-${role}" title="${isHidden ? 'Reveal QR Code' : 'Hide QR Code'}">
          ${isHidden ? '👁️ Reveal' : '🔒 Hide'}
        </button>
      </div>
      <div class="role-card-qr-wrap">
        <div class="role-card-qr ${isHidden ? 'blurred' : ''}" id="qr-${role}" title="${isHidden ? 'Click to reveal' : 'Click to copy ' + title + ' link'}" role="button" tabindex="0"></div>
      </div>
      <div class="role-card-copy-hint" id="hint-${role}">${isHidden ? '🔒 Hidden (Click to reveal)' : '📋 Click QR to copy link'}</div>
    `;
    roleCardsContainer.appendChild(card);

    const qrContainer = card.querySelector(`#qr-${role}`);
    const toggleBtn = card.querySelector(`#toggle-${role}`);
    const hintEl = card.querySelector(`#hint-${role}`);

    function drawQr(forUrl) {
      qrContainer.innerHTML = "";
      new QRCode(qrContainer, {
        text: forUrl,
        width: 180,
        height: 180,
        colorDark: colors.dark,
        colorLight: colors.light,
        correctLevel: QRCode.CorrectLevel.L,
      });
    }

    // Initial draw: dummy URL if hidden, real URL if visible
    drawQr(isHidden ? DUMMY_QR_URL : shareUrl);

    function setHidden(hidden, notify = true) {
      isHidden = hidden;
      if (isHidden) {
        drawQr(DUMMY_QR_URL);
        qrContainer.classList.add("blurred");
        qrContainer.title = "Click to reveal";
        toggleBtn.innerHTML = "👁️ Reveal";
        toggleBtn.title = "Reveal QR Code";
        hintEl.textContent = "🔒 Hidden (Click to reveal)";
        if (notify) showToast(`${title} QR code hidden.`, "info");
      } else {
        drawQr(shareUrl);
        qrContainer.classList.remove("blurred");
        qrContainer.title = `Click to copy ${title} link`;
        toggleBtn.innerHTML = "🔒 Hide";
        toggleBtn.title = "Hide QR Code";
        hintEl.textContent = "📋 Click QR to copy link";
        if (notify) showToast(`${title} QR code revealed.`, "info");
      }
    }

    toggleBtn.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      setHidden(!isHidden, true);
    });

    const qrClickHandler = (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (isHidden) {
        setHidden(false, true);
      } else {
        navigator.clipboard.writeText(shareUrl);
        showToast(`${title} link copied to clipboard!`, "success");
      }
    };

    qrContainer.addEventListener("click", qrClickHandler);
    qrContainer.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") qrClickHandler(e);
    });
  }
}

let lastHandledHash = null;

function moveRegistrationCardToEnd(moveToEnd) {
  const cardReg = document.getElementById("card-registration");
  const dashboardGrid = document.querySelector(".dashboard-grid");
  const appContainer = document.querySelector(".app-container");
  if (!cardReg || !dashboardGrid || !appContainer) return;

  if (moveToEnd) {
    if (cardReg.parentElement !== appContainer || cardReg !== appContainer.lastElementChild) {
      appContainer.appendChild(cardReg);
      cardReg.classList.add("moved-to-end");
      dashboardGrid.classList.add("single-column");
    }
  } else {
    if (cardReg.parentElement !== dashboardGrid || cardReg !== dashboardGrid.firstElementChild) {
      dashboardGrid.prepend(cardReg);
      cardReg.classList.remove("moved-to-end");
      dashboardGrid.classList.remove("single-column");
    }
  }
}

async function handleLoadedUuids(uuids, updateHash = true, rawInput = null) {
  if (!uuids || uuids.length === 0) {
    moveRegistrationCardToEnd(false);
    return;
  }

  const parsedLabel = parseAnchorLabel(rawInput || window.location.hash);
  if (parsedLabel) {
    currentCounterLabel = parsedLabel;
  }

  updateStatusPill("connecting", "Verifying UUID(s)...");

  // Check each UUID against the server
  const queryPromises = uuids.map(async (u) => {
    try {
      const res = await fetch(`/api/counters/${u}`);
      if (res.ok) {
        const data = await res.json();
        return { uuid: u, ok: true, data };
      }
    } catch (e) {}
    return { uuid: u, ok: false };
  });

  const results = await Promise.all(queryPromises);
  const anyOk = results.some(r => r.ok);

  if (anyOk) {
    // Existing room found!
    // Process results in order.
    // If it's admin#admin#read: the first admin is clobbered by the second admin,
    // and resolvedRoles["read_write"] will remain undefined (no write to load).
    const resolvedRoles = {};
    let counterId = null;
    let counterValue = 0;

    for (const res of results) {
      if (res.ok && res.data) {
        const role = res.data.access; // "admin", "read_write", or "read_only"
        resolvedRoles[role] = res.uuid; // Later uuid with the same role clobbers earlier one
        counterId = res.data.counter_id;
        counterValue = res.data.value;
      }
    }

    // Fill in register box
    if (resolvedRoles.read_only) {
      document.getElementById("reg-ro-uuid").value = resolvedRoles.read_only;
    }
    if (resolvedRoles.read_write) {
      document.getElementById("reg-rw-uuid").value = resolvedRoles.read_write;
      currentMode = "3uuid";
      document.getElementById("btn-mode-3uuid").classList.add("active");
      document.getElementById("btn-mode-2uuid").classList.remove("active");
      document.getElementById("group-rw-uuid").style.display = "block";
    } else {
      document.getElementById("reg-rw-uuid").value = "";
      if (resolvedRoles.read_only && resolvedRoles.admin) {
        currentMode = "2uuid";
        document.getElementById("btn-mode-2uuid").classList.add("active");
        document.getElementById("btn-mode-3uuid").classList.remove("active");
        document.getElementById("group-rw-uuid").style.display = "none";
      }
    }
    if (resolvedRoles.admin) {
      document.getElementById("reg-admin-uuid").value = resolvedRoles.admin;
    }

    // Determine highest privilege role to connect as: admin > read_write > read_only
    const connectRole = resolvedRoles.admin ? "admin" : (resolvedRoles.read_write ? "read_write" : "read_only");
    const connectToken = resolvedRoles[connectRole];

    // Fill in view client box
    document.getElementById("input-connect-uuid").value = connectToken;

    activeCounterId = counterId;
    currentRole = connectRole;
    activeTokens = { ...resolvedRoles };

    if (!currentCounterLabel && counterId) {
      currentCounterLabel = `Counter ${counterId.substring(0, 8)}`;
    }
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = currentCounterLabel;
    updateCounterDisplay(counterValue);
    applyRolePermissions(currentRole);

    // Update quick role switch container
    document.getElementById("role-switcher-container").style.display = "block";
    document.getElementById("chip-admin").style.display = activeTokens.admin ? "inline-flex" : "none";
    document.getElementById("chip-rw").style.display = activeTokens.read_write ? "inline-flex" : "none";
    document.getElementById("chip-ro").style.display = activeTokens.read_only ? "inline-flex" : "none";

    document.querySelectorAll(".chip-role").forEach(c => c.classList.remove("active"));
    const activeChip = document.querySelector(`.chip-role[data-role="${currentRole}"]`);
    if (activeChip) activeChip.classList.add("active");

    moveRegistrationCardToEnd(true);

    // Update share links & anchor
    const shareHash = buildShareHash(currentRole, activeTokens, currentCounterLabel);
    updateShareLinks(currentRole, activeTokens);
    renderRoleCards(activeTokens, activeCounterId);

    if (updateHash && normalizeHash(window.location.hash) !== normalizeHash(shareHash)) {
      lastHandledHash = shareHash;
      window.location.hash = shareHash;
    } else {
      lastHandledHash = window.location.hash;
    }

    try { localStorage.setItem("counter_last_token", connectToken); } catch(e) {}

    disconnectRoom();
    if (currentProtocol === "ws") {
      connectRoomWebSocket(connectToken);
    } else {
      connectRoomSSE(connectToken);
    }
  } else {
    // None of the UUIDs exist in DB yet (unregistered / ready to register).
    // Usually the 3 uuids should be a read/write/admin token.
    const resolvedRoles = {};
    if (uuids.length === 3) {
      resolvedRoles.read_only = uuids[0];
      resolvedRoles.read_write = uuids[1];
      resolvedRoles.admin = uuids[2];
      currentMode = "3uuid";
      document.getElementById("btn-mode-3uuid").classList.add("active");
      document.getElementById("btn-mode-2uuid").classList.remove("active");
      document.getElementById("group-rw-uuid").style.display = "block";
    } else if (uuids.length === 2) {
      resolvedRoles.read_only = uuids[0];
      resolvedRoles.admin = uuids[1];
      currentMode = "2uuid";
      document.getElementById("btn-mode-2uuid").classList.add("active");
      document.getElementById("btn-mode-3uuid").classList.remove("active");
      document.getElementById("group-rw-uuid").style.display = "none";
    } else if (uuids.length === 1) {
      resolvedRoles.admin = uuids[0];
    }

    // Fill in register box
    if (resolvedRoles.read_only) document.getElementById("reg-ro-uuid").value = resolvedRoles.read_only;
    if (resolvedRoles.read_write) document.getElementById("reg-rw-uuid").value = resolvedRoles.read_write;
    if (resolvedRoles.admin) document.getElementById("reg-admin-uuid").value = resolvedRoles.admin;

    // Fill in view client box
    const connectUuid = resolvedRoles.admin || resolvedRoles.read_write || resolvedRoles.read_only || uuids[0];
    document.getElementById("input-connect-uuid").value = connectUuid;

    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = currentCounterLabel || "Unregistered Counter";

    updateStatusPill("disconnected", "Unregistered Room");
    moveRegistrationCardToEnd(false);
  }
}

async function connectWithToken(token, updateHash = true, customLabel = null) {
  if (!token) {
    showToast("Please enter a room access UUID.", "error");
    return;
  }
  const uuids = parseAnchorUuids(token);
  const cleaned = uuids.length > 0 ? uuids[0] : token.trim().replace(/^[\/#]+/, "").trim();
  if (!isValidUuid(cleaned)) {
    showToast("Provided token must be formatted as a valid UUID.", "error");
    moveRegistrationCardToEnd(false);
    return;
  }

  updateStatusPill("connecting", "Verifying UUID...");

  try {
    const res = await fetch(`/api/counters/${cleaned}`);
    const data = await res.json();
    if (!res.ok) {
      updateStatusPill("disconnected", "Invalid UUID");
      showToast(data.detail || "Invalid access UUID or room does not exist.", "error");
      moveRegistrationCardToEnd(false);
      return;
    }

    moveRegistrationCardToEnd(true);
    activeCounterId = data.counter_id;
    currentRole = data.access;
    activeTokens[currentRole] = cleaned;

    if (customLabel) {
      currentCounterLabel = customLabel;
    } else if (!currentCounterLabel && data.counter_id) {
      currentCounterLabel = `Counter ${data.counter_id.substring(0, 8)}`;
    }

    // Fill in register box for this role
    if (currentRole === "admin") {
      document.getElementById("reg-admin-uuid").value = cleaned;
    } else if (currentRole === "read_write") {
      document.getElementById("reg-rw-uuid").value = cleaned;
    } else if (currentRole === "read_only") {
      document.getElementById("reg-ro-uuid").value = cleaned;
    }

    document.getElementById("input-connect-uuid").value = cleaned;
    const statusEl = document.getElementById("counter-room-status");
    if (statusEl) statusEl.textContent = currentCounterLabel;
    updateCounterDisplay(data.value);
    applyRolePermissions(currentRole);

    // Update active role chip
    document.querySelectorAll(".chip-role").forEach(c => c.classList.remove("active"));
    const activeChip = document.querySelector(`.chip-role[data-role="${currentRole}"]`);
    if (activeChip) activeChip.classList.add("active");

    // Display active room hashtag link with lower level uuids and label
    const shareHash = buildShareHash(currentRole, activeTokens, currentCounterLabel);
    updateShareLinks(currentRole, activeTokens);
    renderRoleCards(activeTokens, activeCounterId);

    // Update URL hashtag
    if (updateHash && shareHash && normalizeHash(window.location.hash) !== normalizeHash(shareHash)) {
      lastHandledHash = shareHash;
      window.location.hash = shareHash;
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
    if (statusEl) statusEl.textContent = currentCounterLabel || `Counter ${data.counter_id.substring(0, 8)}`;
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
    currentCounterLabel = null;
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
    currentCounterLabel = null;
    if (window.location.hash) {
      history.replaceState(null, "", window.location.pathname);
    }
    moveRegistrationCardToEnd(false);
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

      showToast("Counter room registered successfully.", "success");
      activeCounterId = data.counter_id;
      activeTokens = { ...data.roles };
      currentCounterLabel = currentCounterLabel || `Counter ${data.counter_id.substring(0, 8)}`;
      const statusEl = document.getElementById("counter-room-status");
      if (statusEl) statusEl.textContent = currentCounterLabel;

      // Populate role cards with QR codes
      renderRoleCards(activeTokens, activeCounterId);
      const resTitle = document.getElementById("res-box-title");
      if (resTitle) resTitle.textContent = "✓ Counter Registered";

      const detailsHashes = document.getElementById("details-token-hashes");
      if (detailsHashes) detailsHashes.style.display = "block";

      // Populate hash table
      const tbody = document.getElementById("token-table-body");
      tbody.innerHTML = "";
      for (const [role, token] of Object.entries(data.roles)) {
        const hash = data.token_hashes[role];
        const shareHash = buildShareHash(role, data.roles, currentCounterLabel);
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td><span class="label-role-tag tag-${role === 'admin' ? 'admin' : (role === 'read_write' ? 'rw' : 'ro')}">${role}</span></td>
          <td class="code-cell" title="${shareHash}"><a href="${shareHash}" style="color:var(--text-main); text-decoration:none;">${shareHash}</a></td>
          <td class="code-cell" title="${hash}">${hash.substring(0, 16)}...</td>
          <td>
            <button type="button" class="btn-secondary copy-btn" data-copy="${shareHash}" style="padding:2px 8px; font-size:11px;">Copy</button>
          </td>
        `;
        tbody.appendChild(tr);
      }

      tbody.querySelectorAll(".copy-btn").forEach(btn => {
        btn.addEventListener("click", () => {
          navigator.clipboard.writeText(btn.getAttribute("data-copy"));
          showToast("Share link copied to clipboard!", "info");
        });
      });

      // Show role switcher chips since we have all keys in this session
      document.getElementById("role-switcher-container").style.display = "block";
      document.getElementById("chip-admin").style.display = activeTokens.admin ? "inline-flex" : "none";
      document.getElementById("chip-rw").style.display = activeTokens.read_write ? "inline-flex" : "none";
      document.getElementById("chip-ro").style.display = activeTokens.read_only ? "inline-flex" : "none";

      // Connect to live room using the admin token and update hashtag
      document.getElementById("input-connect-uuid").value = data.roles.admin;
      currentRole = "admin";
      const adminShareHash = buildShareHash("admin", activeTokens, currentCounterLabel);
      lastHandledHash = adminShareHash;
      window.location.hash = adminShareHash;
      connectWithToken(data.roles.admin, false, currentCounterLabel);
      updateShareLinks("admin", activeTokens);
      initSimulator();

    } catch (e) {
      showToast(`Error: ${e.message}`, "error");
    }
  });

  // Connect With Existing UUID Button
  document.getElementById("btn-connect-uuid").addEventListener("click", () => {
    const val = document.getElementById("input-connect-uuid").value.trim();
    const uuids = parseAnchorUuids(val);
    if (uuids.length > 0) {
      const parsedLabel = parseAnchorLabel(val);
      if (parsedLabel) currentCounterLabel = parsedLabel;
      handleLoadedUuids(uuids, true, val);
    } else {
      showToast("Provided token must be formatted as a valid UUID.", "error");
    }
  });

  document.getElementById("input-connect-uuid").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      const val = document.getElementById("input-connect-uuid").value.trim();
      const uuids = parseAnchorUuids(val);
      if (uuids.length > 0) {
        const parsedLabel = parseAnchorLabel(val);
        if (parsedLabel) currentCounterLabel = parsedLabel;
        handleLoadedUuids(uuids, true, val);
      } else {
        showToast("Provided token must be formatted as a valid UUID.", "error");
      }
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
    if (!activeTokens[role]) return;
    currentRole = role;
    document.querySelectorAll(".chip-role").forEach(c => c.classList.remove("active"));
    const activeChip = document.querySelector(`.chip-role[data-role="${role}"]`);
    if (activeChip) activeChip.classList.add("active");
    const token = activeTokens[role];
    if (token) {
      const shareHash = buildShareHash(role, activeTokens, currentCounterLabel);
      lastHandledHash = shareHash;
      window.location.hash = shareHash;
      document.getElementById("input-connect-uuid").value = token;
      connectWithToken(token, false, currentCounterLabel);
      updateShareLinks(role, activeTokens);
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

  // Active link bar copy button
  const btnCopyActive = document.getElementById("btn-copy-active-link");
  if (btnCopyActive) {
    btnCopyActive.addEventListener("click", () => {
      const shareHash = buildShareHash(currentRole, activeTokens, currentCounterLabel);
      if (shareHash) {
        const fullUrl = `${window.location.origin}${window.location.pathname}${shareHash}`;
        navigator.clipboard.writeText(fullUrl);
        showToast("Room share link with hashtag copied to clipboard!", "info");
      }
    });
  }

  // Listen to browser hash changes (e.g. user clicks back/forward or a role card #link)
  window.addEventListener("hashchange", () => {
    const currentHash = window.location.hash;
    if (normalizeHash(currentHash) === normalizeHash(lastHandledHash)) return;
    const uuids = parseAnchorUuids(currentHash);
    if (uuids.length > 0) {
      lastHandledHash = currentHash;
      const parsedLabel = parseAnchorLabel(currentHash);
      if (parsedLabel) currentCounterLabel = parsedLabel;
      handleLoadedUuids(uuids, false, currentHash);
    } else {
      moveRegistrationCardToEnd(false);
    }
  });

  // Initial State: inputs start blank, controls start disabled
  applyRolePermissions("none");
  const initialStatusEl = document.getElementById("counter-room-status");
  if (initialStatusEl) initialStatusEl.textContent = "No Room Connected";
  updateStatusPill("disconnected", "Not Connected");

  // Check if URL has hashtag on initial load
  const initialHash = window.location.hash;
  const initialUuids = parseAnchorUuids(initialHash);
  if (initialUuids.length > 0) {
    lastHandledHash = initialHash;
    const parsedLabel = parseAnchorLabel(initialHash);
    if (parsedLabel) currentCounterLabel = parsedLabel;
    handleLoadedUuids(initialUuids, false, initialHash);
  } else {
    // Check URL query parameters for ?token=<uuid> or ?uuid=<uuid>
    const urlParams = new URLSearchParams(window.location.search);
    const tokenParam = urlParams.get("token") || urlParams.get("uuid");
    if (tokenParam) {
      const paramUuids = parseAnchorUuids(tokenParam);
      if (paramUuids.length > 0) {
        const parsedLabel = parseAnchorLabel(tokenParam);
        if (parsedLabel) currentCounterLabel = parsedLabel;
        handleLoadedUuids(paramUuids, true, tokenParam);
      }
    }
  }
});


