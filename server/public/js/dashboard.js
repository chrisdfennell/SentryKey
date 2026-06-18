/**
 * SentryKey Dashboard Controller.
 * App-like flow: auto-loads your latest cloud vault on login, shows live TOTP
 * codes, and auto-saves (encrypted) on every change. Account management: copy,
 * rename, reorder, delete-with-undo, search. All crypto is client-side.
 */
document.addEventListener("DOMContentLoaded", () => {

  // --- Session ---
  const sessionToken = localStorage.getItem("sentrykey_session_token") || "";
  const username = localStorage.getItem("sentrykey_username") || "";
  const encKeyB64 = sessionStorage.getItem("sentrykey_enc_key") || "";

  let encKeyBytes = null;
  let accounts = [];                 // in-memory vault
  let countdownTimerId = null;
  let encryptedVaultString = "";     // cache for the manual-passphrase fallback
  let lastSavedJson = null;          // skip no-op uploads
  let autoSaveTimer = null;
  let pendingUndo = null;            // { account, index, timer }
  let currentRev = 0;                // server vault revision we last synced from

  if (!sessionToken || !username || !encKeyB64) {
    localStorage.removeItem("sentrykey_session_token");
    localStorage.removeItem("sentrykey_username");
    sessionStorage.removeItem("sentrykey_enc_key");
    window.location.href = "/login.html";
    return;
  }
  try {
    encKeyBytes = SentryCrypto.base64ToBytes(encKeyB64);
  } catch (err) {
    window.location.href = "/login.html";
    return;
  }

  // --- DOM ---
  const $ = (id) => document.getElementById(id);
  const elSidebarUser = $("sidebar-user-display");
  const elBtnLogout = $("btn-logout");
  const elBackupDropdown = $("backup-files-dropdown");
  const elBtnRefreshBackups = $("btn-refresh-backups");
  const elBtnLoadBackup = $("btn-load-backup");
  const elStatusIndicator = $("status-indicator");
  const elStatusText = $("status-text");
  const elSaveStatus = $("save-status");
  const elSearchInput = $("search-accounts-input");
  const elBtnAddAccount = $("btn-add-account");
  const elBtnSaveCloud = $("btn-save-cloud");
  const elBtnExportBackup = $("btn-export-backup");
  const elUploadFileInput = $("upload-backup-file-input");
  const elDecryptionGate = $("vault-decryption-gate");
  const elVaultDecryptForm = $("vault-decrypt-form");
  const elVaultPassInput = $("vault-passphrase-input");
  const elBtnCancelDecryption = $("btn-cancel-decryption");
  const elAccountsGrid = $("accounts-grid-container");
  const elEmptyState = $("empty-dashboard-state");
  const elBtnCreateScratch = $("btn-create-scratch");
  const elAddAccountModal = $("add-account-modal");
  const elAddAccountForm = $("add-account-form");
  const elNewAccountLabel = $("new-account-label");
  const elNewAccountSecret = $("new-account-secret");
  const elBtnAddAccountCancel = $("btn-add-account-cancel");
  const elToast = $("toast-notification");

  elSidebarUser.textContent = username.toUpperCase();
  elStatusIndicator.className = "status-dot online";
  elStatusText.textContent = "Cloud Node Connected";
  elBtnAddAccount.style.display = "inline-flex";
  elBtnExportBackup.style.display = "inline-flex";
  if (elBtnSaveCloud) elBtnSaveCloud.style.display = "inline-flex";

  // Kick off: list backups + auto-load the newest.
  initVault();

  // Reveal the Admin nav item in the sidebar if this account is a server admin.
  (async () => {
    try {
      const res = await fetch("/api/account", { headers: { "X-Session-Token": sessionToken } });
      if (!res.ok) return;
      if ((await res.json()).isAdmin) {
        const el = $("nav-admin");
        if (el) el.style.display = "";
      }
    } catch (_) { /* non-fatal */ }
  })();

  // ==========================================================================
  // STATUS / TOAST
  // ==========================================================================
  function showToast(message, type = "info") {
    elToast.innerHTML = message;
    elToast.className = "toast show";
    if (type === "error") elToast.classList.add("error");
    if (type === "success") elToast.classList.add("success");
    if (elToast._timer) clearTimeout(elToast._timer);
    elToast._timer = setTimeout(() => elToast.classList.remove("show"), 3500);
  }
  function setSaveStatus(text, kind) {
    if (!elSaveStatus) return;
    elSaveStatus.textContent = text;
    elSaveStatus.dataset.kind = kind || "";
  }

  // ==========================================================================
  // LOGOUT
  // ==========================================================================
  elBtnLogout.addEventListener("click", async () => {
    try {
      await fetch("/api/auth/logout", { method: "POST", headers: { "X-Session-Token": sessionToken } });
    } catch (_) {}
    localStorage.removeItem("sentrykey_session_token");
    localStorage.removeItem("sentrykey_username");
    sessionStorage.removeItem("sentrykey_enc_key");
    window.location.href = "/login.html";
  });

  // ==========================================================================
  // LOAD: list backups + auto-load latest
  // ==========================================================================
  async function initVault() {
    const backups = await fetchBackupList();
    if (backups.length > 0) {
      await loadBackup(backups[0].filename, /*silent*/ true);
    } else {
      // New account — start an empty vault ready to go.
      setVault([]);
      lastSavedJson = vaultJson(); // empty; nothing to upload yet
      showToast("Welcome! Add your first account to get started.", "info");
    }
  }

  async function fetchBackupList() {
    try {
      const res = await fetch("/api/backups", { headers: { "X-Session-Token": sessionToken } });
      if (!res.ok) throw new Error("list failed");
      const data = await res.json();
      const backups = data.backups || [];
      currentRev = data.rev || 0;
      elBackupDropdown.innerHTML = "";
      if (backups.length === 0) {
        elBackupDropdown.innerHTML = '<option value="">-- No backups on server yet --</option>';
      } else {
        backups.forEach((b, i) => {
          const opt = document.createElement("option");
          opt.value = b.filename;
          const kb = (b.sizeBytes / 1024).toFixed(1);
          opt.textContent = `${b.timestamp} — ${kb} KB${i === 0 ? " (latest)" : ""}`;
          elBackupDropdown.appendChild(opt);
        });
      }
      return backups;
    } catch (err) {
      elBackupDropdown.innerHTML = '<option value="">-- Error connecting to server --</option>';
      showToast("Couldn't reach the server.", "error");
      return [];
    }
  }

  async function loadBackup(filename, silent) {
    if (!filename) { showToast("No backup selected.", "error"); return; }
    try {
      if (!silent) showToast("Loading backup…", "info");
      const res = await fetch(`/api/backups/file/${filename}`, { headers: { "X-Session-Token": sessionToken } });
      if (!res.ok) throw new Error("fetch failed");
      await decryptAndLoad(await res.text(), silent);
    } catch (err) {
      showToast("Failed to load backup from server.", "error");
    }
  }

  elBtnRefreshBackups.addEventListener("click", async () => {
    await fetchBackupList();
    showToast("Backup list refreshed.", "info");
  });
  elBtnLoadBackup.addEventListener("click", () => loadBackup(elBackupDropdown.value, false));

  elUploadFileInput.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => decryptAndLoad(ev.target.result, false);
    reader.readAsText(file);
    elUploadFileInput.value = "";
  });

  async function decryptAndLoad(content, silent) {
    try {
      const parsed = JSON.parse(content);
      if (!parsed.encrypted) {
        setVault(parsed.accounts || []);
        lastSavedJson = vaultJson();
        if (!silent) showToast(`Loaded ${accounts.length} account(s).`, "success");
        return;
      }
      try {
        const decrypted = await SentryCrypto.decryptWithKey(content, encKeyBytes);
        const vault = JSON.parse(decrypted);
        setVault(vault.accounts || []);
        lastSavedJson = vaultJson();
        if (!silent) showToast(`Loaded ${accounts.length} account(s).`, "success");
      } catch (sessionErr) {
        // Not our session key (e.g. a manual passphrase backup) — ask for it.
        showDecryptionGate(content);
      }
    } catch (e) {
      showToast("Invalid SentryKey backup file.", "error");
    }
  }

  // Fallback: manual passphrase for externally-encrypted files.
  elVaultDecryptForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      const plaintext = await SentryCrypto.decrypt(encryptedVaultString, elVaultPassInput.value);
      const vault = JSON.parse(plaintext);
      setVault(vault.accounts || []);
      lastSavedJson = null; // imported foreign vault — let it auto-save under our key
      hideDecryptionGate();
      scheduleAutoSave();
      showToast(`Imported ${accounts.length} account(s).`, "success");
    } catch (err) {
      showToast("Wrong passphrase.", "error");
    }
  });
  function showDecryptionGate(content) {
    encryptedVaultString = content;
    elDecryptionGate.style.display = "block";
    elAccountsGrid.style.display = "none";
    elEmptyState.style.display = "none";
  }
  function hideDecryptionGate() {
    elDecryptionGate.style.display = "none";
    elAccountsGrid.style.display = "grid";
    elVaultPassInput.value = "";
    encryptedVaultString = "";
  }
  elBtnCancelDecryption.addEventListener("click", () => { hideDecryptionGate(); setVault([]); });
  elBtnCreateScratch.addEventListener("click", () => { setVault([]); showToast("Empty vault ready — add an account.", "info"); });

  // ==========================================================================
  // SAVE: auto-save (debounced) + manual
  // ==========================================================================
  function vaultJson() { return JSON.stringify({ app: "SentryKey", version: 1, accounts }); }

  async function saveToCloud(silent, depth) {
    depth = depth || 0;
    const json = vaultJson();
    if (json === lastSavedJson) { setSaveStatus("Saved", "ok"); return; }
    try {
      setSaveStatus("Saving…", "saving");
      const envelope = await SentryCrypto.encryptWithKey(json, encKeyBytes);
      const res = await fetch("/api/backups/upload", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Session-Token": sessionToken, "X-Base-Rev": String(currentRev) },
        body: envelope
      });
      if (res.status === 409) {
        // Another device uploaded since we loaded — merge its changes and retry.
        if (depth >= 2) { setSaveStatus("Sync conflict", "error"); showToast("Sync conflict — reload to get the latest.", "error"); return; }
        await reconcileAndRetry(silent, depth + 1);
        return;
      }
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || "upload failed");
      }
      const data = await res.json().catch(() => ({}));
      if (typeof data.rev === "number") currentRev = data.rev;
      lastSavedJson = json;
      setSaveStatus("Saved ✓", "ok");
      if (!silent) showToast("Synced to cloud.", "success");
      fetchBackupList();
    } catch (e) {
      setSaveStatus("Save failed", "error");
      if (!silent) showToast(e.message || "Sync failed.", "error");
    }
  }

  // On a 409, pull the latest vault, union-merge it with ours (so no account is
  // ever lost), then retry the save against the fresh revision.
  async function reconcileAndRetry(silent, depth) {
    setSaveStatus("Merging…", "saving");
    try {
      const backups = await fetchBackupList(); // refreshes currentRev
      if (backups.length) {
        const res = await fetch(`/api/backups/file/${backups[0].filename}`, { headers: { "X-Session-Token": sessionToken } });
        const remotePlain = await SentryCrypto.decryptWithKey(await res.text(), encKeyBytes);
        const remoteAccounts = (JSON.parse(remotePlain).accounts) || [];
        accounts = mergeAccounts(accounts, remoteAccounts);
        renderAccountsGrid();
      }
      lastSavedJson = null; // force the retry to upload the merged vault
      if (!silent) showToast("Merged changes from another device.", "info");
      await saveToCloud(silent, depth);
    } catch (e) {
      setSaveStatus("Sync conflict", "error");
      showToast("Couldn't merge — reload to see the latest.", "error");
    }
  }

  // Union of two account lists by (label, secret) — never drops a 2FA secret.
  function mergeAccounts(a, b) {
    const seen = new Set(), out = [];
    for (const acc of [].concat(a, b)) {
      const key = (acc.label || "") + " " + (acc.secret || "");
      if (!seen.has(key)) { seen.add(key); out.push(acc); }
    }
    return out;
  }

  function scheduleAutoSave() {
    setSaveStatus("Saving…", "saving");
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => saveToCloud(true), 1200);
  }

  if (elBtnSaveCloud) elBtnSaveCloud.addEventListener("click", () => saveToCloud(false));

  // ==========================================================================
  // VAULT MUTATION
  // ==========================================================================
  function setVault(arr) {
    accounts = Array.isArray(arr) ? arr : [];
    renderAccountsGrid();
    startCountdownTicker();
  }
  function commit() { renderAccountsGrid(); scheduleAutoSave(); }

  function addAccount(label, secret) {
    accounts.push({ label, secret });
    commit();
  }
  function renameAccount(idx) {
    const cur = accounts[idx];
    const name = window.prompt("Rename account", cur.label);
    if (name == null) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    accounts[idx].label = trimmed;
    commit();
  }
  function moveAccount(idx, dir) {
    const j = idx + dir;
    if (j < 0 || j >= accounts.length) return;
    const tmp = accounts[idx]; accounts[idx] = accounts[j]; accounts[j] = tmp;
    commit();
  }
  function deleteAccount(idx) {
    const removed = accounts[idx];
    accounts.splice(idx, 1);
    commit();
    // Undo window
    if (pendingUndo && pendingUndo.timer) clearTimeout(pendingUndo.timer);
    pendingUndo = {
      account: removed,
      index: idx,
      timer: setTimeout(() => { pendingUndo = null; }, 6000)
    };
    showUndoToast(`Removed “${removed.label}”`);
  }
  function showUndoToast(message) {
    elToast.innerHTML = `${message} &nbsp; <a href="#" id="undo-link" style="color:var(--orange); font-weight:700;">Undo</a>`;
    elToast.className = "toast show";
    if (elToast._timer) clearTimeout(elToast._timer);
    elToast._timer = setTimeout(() => elToast.classList.remove("show"), 6000);
    const link = document.getElementById("undo-link");
    if (link) link.addEventListener("click", (e) => {
      e.preventDefault();
      if (!pendingUndo) return;
      accounts.splice(Math.min(pendingUndo.index, accounts.length), 0, pendingUndo.account);
      pendingUndo = null;
      elToast.classList.remove("show");
      commit();
    });
  }

  // ==========================================================================
  // RENDER
  // ==========================================================================
  function getIssuerIcon(label) {
    const clean = label.toLowerCase();
    const icons = {
      github: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>',
      google: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12.24 10.285V14.4h6.887c-.648 2.41-2.519 4.113-5.136 4.113-3.41 0-6.177-2.767-6.177-6.177s2.767-6.177 6.177-6.177c1.632 0 3.07.633 4.17 1.665L21.35 4.85C19.14 2.8 15.96 1.5 12.24 1.5 6.3 1.5 1.5 6.3 1.5 12.24s4.8 10.74 10.74 10.74c6.14 0 10.74-4.3 10.74-10.74 0-.675-.06-1.3-.175-1.96z"/></svg>',
      discord: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M20.317 4.37a19.79 19.79 0 0 0-4.885-1.515.07.07 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.6 12.6 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.74 19.74 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.08.08 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.1 14.1 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.1 13.1 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.009c.12.099.246.198.373.292a.077.077 0 0 1-.006.127 12.3 12.3 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.84 19.84 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.06.06 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419s.955-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419s.955-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/></svg>'
    };
    for (const [k, v] of Object.entries(icons)) if (clean.includes(k)) return v;
    return '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>';
  }

  function renderAccountsGrid() {
    elAccountsGrid.innerHTML = "";
    const query = elSearchInput.value.trim().toLowerCase();
    const showOrder = query === "";

    const matches = accounts
      .map((acc, idx) => ({ acc, idx }))
      .filter(({ acc }) => acc.label.toLowerCase().includes(query));

    if (matches.length === 0) {
      if (accounts.length === 0) {
        elAccountsGrid.appendChild(elEmptyState);
        elEmptyState.style.display = "block";
      } else {
        const none = document.createElement("div");
        none.className = "empty-vault glass";
        none.innerHTML = `<div class="empty-vault-icon">🔍</div><h3>No matching accounts</h3><p>No results for "${elSearchInput.value}".</p>`;
        elAccountsGrid.appendChild(none);
      }
      return;
    }
    elEmptyState.style.display = "none";

    matches.forEach(({ acc, idx }) => {
      const parts = acc.label.split(":");
      let issuer = parts[0].trim();
      let name = parts.length > 1 ? parts.slice(1).join(":").trim() : "";
      if (issuer.includes("(") && !name) {
        const m = issuer.match(/(.*?)\s*\((.*?)\)/);
        if (m) { issuer = m[1].trim(); name = m[2].trim(); }
      }
      if (!name) { name = issuer; issuer = "SentryKey"; }

      const card = document.createElement("div");
      card.className = "account-card glass";
      card.innerHTML = `
        <div class="card-actions">
          ${showOrder ? `<button class="card-action-btn" data-act="up" data-i="${idx}" title="Move up">▲</button>
          <button class="card-action-btn" data-act="down" data-i="${idx}" title="Move down">▼</button>` : ""}
          <button class="card-action-btn" data-act="rename" data-i="${idx}" title="Rename">✏️</button>
          <button class="card-action-btn delete-btn" data-act="delete" data-i="${idx}" title="Remove">🗑️</button>
        </div>
        <div class="account-header">
          <div class="account-info">
            <div class="account-label">${name}</div>
            <div class="account-issuer">${issuer.toUpperCase()}</div>
          </div>
          <div class="account-icon">${getIssuerIcon(acc.label)}</div>
        </div>
        <div class="account-code-container">
          <div class="account-code" data-secret="${acc.secret}" title="Click to copy">------</div>
          <div class="timer-container">
            <svg class="timer-ring" width="44" height="44">
              <circle class="timer-ring-back" cx="22" cy="22" r="18"></circle>
              <circle class="timer-ring-front" data-ring cx="22" cy="22" r="18"></circle>
            </svg>
            <div class="timer-text" data-time>--</div>
          </div>
        </div>`;
      elAccountsGrid.appendChild(card);
    });

    // Wire actions
    elAccountsGrid.querySelectorAll(".card-action-btn").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        const i = parseInt(btn.getAttribute("data-i"), 10);
        switch (btn.getAttribute("data-act")) {
          case "up": moveAccount(i, -1); break;
          case "down": moveAccount(i, 1); break;
          case "rename": renameAccount(i); break;
          case "delete": deleteAccount(i); break;
        }
      });
    });
    elAccountsGrid.querySelectorAll(".account-code").forEach((codeEl) => {
      codeEl.addEventListener("click", () => {
        const raw = codeEl.textContent.replace(/\s+/g, "");
        if (raw === "------") return;
        navigator.clipboard.writeText(raw)
          .then(() => showToast("Code copied!", "success"))
          .catch(() => showToast("Copy failed.", "error"));
      });
    });
  }

  // ==========================================================================
  // LIVE TOTP TICK
  // ==========================================================================
  async function tickCodes() {
    const now = Math.floor(Date.now() / 1000);
    const remaining = 30 - (now % 30);
    const radius = 18;
    const circ = 2 * Math.PI * radius;
    const dash = circ * (remaining / 30);

    const codeEls = elAccountsGrid.querySelectorAll(".account-code");
    for (const el of codeEls) {
      const secret = el.getAttribute("data-secret");
      const code = await SentryOTP.getTOTPCode(secret, now);
      el.textContent = `${code.slice(0, 3)} ${code.slice(3, 6)}`;
      const card = el.closest(".account-card");
      const ring = card.querySelector("[data-ring]");
      const text = card.querySelector("[data-time]");
      if (ring && text) {
        ring.style.strokeDasharray = `${circ} ${circ}`;
        ring.style.strokeDashoffset = dash;
        text.textContent = remaining;
        const warn = remaining <= 5;
        ring.classList.toggle("warning", warn);
        text.classList.toggle("warning", warn);
        el.classList.toggle("warning", warn);
      }
    }
  }
  function startCountdownTicker() {
    stopCountdownTicker();
    tickCodes();
    countdownTimerId = setInterval(tickCodes, 1000);
  }
  function stopCountdownTicker() {
    if (countdownTimerId) { clearInterval(countdownTimerId); countdownTimerId = null; }
  }

  elSearchInput.addEventListener("input", renderAccountsGrid);

  // ==========================================================================
  // ADD ACCOUNT
  // ==========================================================================
  elBtnAddAccount.addEventListener("click", () => elAddAccountModal.classList.add("open"));
  elBtnAddAccountCancel.addEventListener("click", () => {
    elAddAccountModal.classList.remove("open");
    elNewAccountLabel.value = ""; elNewAccountSecret.value = "";
  });
  elAddAccountForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const label = elNewAccountLabel.value.trim();
    const secret = elNewAccountSecret.value.trim().toUpperCase().replace(/\s+/g, "");
    if (!label || !secret) { showToast("Label and secret are required.", "error"); return; }
    if (SentryOTP.decodeBase32(secret).length === 0) { showToast("Invalid Base32 secret.", "error"); return; }
    addAccount(label, secret);
    elAddAccountModal.classList.remove("open");
    elNewAccountLabel.value = ""; elNewAccountSecret.value = "";
    showToast(`Added “${label}”.`, "success");
  });

  // ==========================================================================
  // EXPORT (download encrypted file)
  // ==========================================================================
  elBtnExportBackup.addEventListener("click", async () => {
    if (accounts.length === 0) { showToast("Vault is empty.", "error"); return; }
    try {
      const envelope = await SentryCrypto.encryptWithKey(vaultJson(), encKeyBytes);
      const blob = new Blob([envelope], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = `${username}-sentrykey-vault.skbackup`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showToast("Encrypted backup downloaded.", "success");
    } catch (e) {
      showToast("Export failed.", "error");
    }
  });

  // ==========================================================================
  // ACCOUNT RECOVERY SETUP
  // ==========================================================================
  // Account modal — plan + usage from /api/account.
  const elNavAccount = $("nav-account");
  const elAccountModal = $("account-modal");
  if (elNavAccount && elAccountModal) {
    $("btn-account-close").addEventListener("click", () => elAccountModal.classList.remove("open"));
    elNavAccount.addEventListener("click", async (e) => {
      e.preventDefault();
      elAccountModal.classList.add("open");
      try {
        const res = await fetch("/api/account", { headers: { "X-Session-Token": sessionToken } });
        if (!res.ok) throw new Error("fetch failed");
        const a = await res.json();
        $("account-username").textContent = a.username || username;
        $("account-plan-badge").textContent = a.planLabel || "Free";
        $("account-since").textContent = a.createdAt ? new Date(a.createdAt).toLocaleDateString() : "—";
        $("account-backups").textContent = `${a.backups?.count ?? 0}${a.backups?.max ? " / " + a.backups.max : ""}`;
        $("account-storage").textContent = fmtBytes(a.backups?.bytes ?? 0);
      } catch (_) {
        $("account-plan-badge").textContent = "Free";
      }
    });
  }
  function fmtBytes(n) {
    if (!n) return "0 KB";
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
    return (n / 1048576).toFixed(2) + " MB";
  }

  const elNavRecovery = $("nav-recovery");
  const elRecoveryModal = $("recovery-modal");
  const elRecoveryKeyBox = $("recovery-keybox");
  const elRecoveryKeyValue = $("recovery-key-value");
  const elBtnCopyRecovery = $("btn-copy-recovery");
  const elRecoveryEmail = $("recovery-email");
  const elRecoveryPhone = $("recovery-phone");
  const elRecoveryConfirm = $("recovery-confirm");
  const elBtnRecoveryEnable = $("btn-recovery-enable");
  const elBtnRecoveryCancel = $("btn-recovery-cancel");
  let currentRecoveryKey = "";

  if (elNavRecovery) {
    elNavRecovery.addEventListener("click", (e) => {
      e.preventDefault();
      currentRecoveryKey = SentryCrypto.generateRecoveryKey();
      elRecoveryKeyValue.textContent = currentRecoveryKey;
      elRecoveryKeyBox.style.display = "block";
      elRecoveryConfirm.checked = false;
      elBtnRecoveryEnable.disabled = true;
      elRecoveryModal.classList.add("open");
    });
    elBtnRecoveryCancel.addEventListener("click", () => elRecoveryModal.classList.remove("open"));
    elRecoveryConfirm.addEventListener("change", () => { elBtnRecoveryEnable.disabled = !elRecoveryConfirm.checked; });
    elBtnCopyRecovery.addEventListener("click", () => {
      navigator.clipboard.writeText(currentRecoveryKey).then(() => showToast("Recovery key copied.", "success"));
    });
    const elBtnDownloadKit = $("btn-download-kit");
    if (elBtnDownloadKit) elBtnDownloadKit.addEventListener("click", () => {
      const kit = [
        "SentryKey — Emergency Kit",
        "==========================",
        "",
        "Keep this somewhere safe (a password manager, a printed copy, a secure drive).",
        "It is the ONLY way to recover your vault if you forget your master password —",
        "SentryKey is zero-knowledge, so nobody, not even the server, can reset it for you.",
        "",
        "Account:       " + username,
        "Server:        " + location.origin,
        "Recovery key:  " + currentRecoveryKey,
        "Created:       " + new Date().toLocaleString(),
        "",
        "To recover:",
        "  1. Go to " + location.origin + "/login.html",
        "  2. Click \"Forgot your master password?\"",
        "  3. Enter your username, this recovery key, and a new master password.",
        ""
      ].join("\n");
      const blob = new Blob([kit], { type: "text/plain" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = "SentryKey-Emergency-Kit.txt";
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showToast("Emergency Kit downloaded — store it safely.", "success");
      // Downloading counts as saving it — unlock the Enable button.
      elRecoveryConfirm.checked = true;
      elBtnRecoveryEnable.disabled = false;
    });
    elBtnRecoveryEnable.addEventListener("click", async () => {
      try {
        elBtnRecoveryEnable.disabled = true;
        const salt = SentryCrypto.randomSalt();
        const rec = await SentryCrypto.deriveRecovery(currentRecoveryKey, salt);
        const blob = await SentryCrypto.wrapBytes(encKeyBytes, rec.wrapKey);
        const res = await fetch("/api/recovery/setup", {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Session-Token": sessionToken },
          body: JSON.stringify({
            salt: SentryCrypto.bytesToBase64(salt),
            blob,
            authKey: rec.authKey,
            email: elRecoveryEmail.value.trim(),
            phone: elRecoveryPhone.value.trim()
          })
        });
        if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || "setup failed"); }
        showToast("Recovery enabled — keep your recovery key safe!", "success");
        elRecoveryModal.classList.remove("open");
      } catch (err) {
        showToast(err.message || "Couldn't enable recovery.", "error");
        elBtnRecoveryEnable.disabled = false;
      }
    });
  }
});
