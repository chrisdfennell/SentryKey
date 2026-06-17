/**
 * SentryKey Dashboard Controller (Multi-User Session Integrated).
 * Handles User Sessions, Automatic Local Decryption, live TOTP calculation, 
 * UI interactions, and sandboxed Cloud Backups.
 */
document.addEventListener("DOMContentLoaded", () => {
  
  // --- Session State Variables ---
  const sessionToken = localStorage.getItem("sentrykey_session_token") || "";
  const username = localStorage.getItem("sentrykey_username") || "";
  const encKeyB64 = sessionStorage.getItem("sentrykey_enc_key") || "";
  
  let encKeyBytes = null;
  let encryptedVaultString = ""; // Cache for fallback manual decryption
  let accounts = []; // In-memory accounts array
  let countdownTimerId = null;

  // Redirect to login if session information is missing
  if (!sessionToken || !username || !encKeyB64) {
    localStorage.removeItem("sentrykey_session_token");
    localStorage.removeItem("sentrykey_username");
    sessionStorage.removeItem("sentrykey_enc_key");
    window.location.href = "/login.html";
    return;
  }

  // Parse key bytes from session storage
  try {
    encKeyBytes = SentryCrypto.base64ToBytes(encKeyB64);
  } catch (err) {
    console.error("Failed to parse encryption key:", err);
    window.location.href = "/login.html";
    return;
  }

  // --- DOM Elements ---
  const elSidebarUser = document.getElementById("sidebar-user-display");
  const elBtnLogout = document.getElementById("btn-logout");
  
  const elBackupDropdown = document.getElementById("backup-files-dropdown");
  const elBtnRefreshBackups = document.getElementById("btn-refresh-backups");
  const elBtnLoadBackup = document.getElementById("btn-load-backup");
  const elStatusIndicator = document.getElementById("status-indicator");
  const elStatusText = document.getElementById("status-text");
  
  const elSearchInput = document.getElementById("search-accounts-input");
  const elBtnAddAccount = document.getElementById("btn-add-account");
  const elBtnSaveCloud = document.getElementById("btn-save-cloud");
  const elBtnExportBackup = document.getElementById("btn-export-backup");
  const elUploadFileInput = document.getElementById("upload-backup-file-input");
  
  const elDecryptionGate = document.getElementById("vault-decryption-gate");
  const elVaultDecryptForm = document.getElementById("vault-decrypt-form");
  const elVaultPassInput = document.getElementById("vault-passphrase-input");
  const elBtnCancelDecryption = document.getElementById("btn-cancel-decryption");
  
  const elAccountsGrid = document.getElementById("accounts-grid-container");
  const elEmptyState = document.getElementById("empty-dashboard-state");
  const elBtnCreateScratch = document.getElementById("btn-create-scratch");
  
  const elAddAccountModal = document.getElementById("add-account-modal");
  const elAddAccountForm = document.getElementById("add-account-form");
  const elNewAccountLabel = document.getElementById("new-account-label");
  const elNewAccountSecret = document.getElementById("new-account-secret");
  const elBtnAddAccountCancel = document.getElementById("btn-add-account-cancel");
  
  const elToast = document.getElementById("toast-notification");

  // Initialize UI displays
  elSidebarUser.textContent = username.toUpperCase();
  elStatusIndicator.className = "status-dot online";
  elStatusText.textContent = "Cloud Node Connected";
  
  // Show base dashboard actions now that session is valid
  elBtnAddAccount.style.display = "inline-flex";
  elBtnSaveCloud.style.display = "inline-flex";
  elBtnExportBackup.style.display = "inline-flex";

  // Fetch list of user's sandboxed backups on startup
  fetchBackupList();

  // ==========================================================================
  // TOAST NOTIFICATIONS
  // ==========================================================================
  
  function showToast(message, type = "info") {
    elToast.textContent = message;
    elToast.className = "toast show";
    if (type === "error") elToast.classList.add("error");
    if (type === "success") elToast.classList.add("success");
    
    setTimeout(() => {
      elToast.classList.remove("show");
    }, 3500);
  }

  // ==========================================================================
  // LOGOUT HANDLER
  // ==========================================================================

  elBtnLogout.addEventListener("click", async () => {
    try {
      showToast("Logging out...", "info");
      await fetch("/api/auth/logout", {
        method: "POST",
        headers: {
          "X-Session-Token": sessionToken
        }
      });
    } catch (e) {
      console.error("Logout request failed:", e);
    } finally {
      localStorage.removeItem("sentrykey_session_token");
      localStorage.removeItem("sentrykey_username");
      sessionStorage.removeItem("sentrykey_enc_key");
      window.location.href = "/login.html";
    }
  });

  // ==========================================================================
  // FETCH & DROPDOWN LIST FOR USER BACKUPS
  // ==========================================================================

  async function fetchBackupList() {
    try {
      const response = await fetch("/api/backups", {
        headers: {
          "X-Session-Token": sessionToken
        }
      });
      
      if (!response.ok) throw new Error("Failed to load backups");
      
      const data = await response.json();
      elBackupDropdown.innerHTML = "";
      
      if (data.backups.length === 0) {
        elBackupDropdown.innerHTML = '<option value="">-- No backups found on server --</option>';
        return;
      }
      
      data.backups.forEach((b, idx) => {
        const sizeKb = (b.sizeBytes / 1024).toFixed(2);
        const latestLabel = idx === 0 ? " (Latest)" : "";
        const option = document.createElement("option");
        option.value = b.filename;
        option.textContent = `${b.timestamp} - ${sizeKb} KB${latestLabel}`;
        elBackupDropdown.appendChild(option);
      });
    } catch (err) {
      console.error(err);
      elBackupDropdown.innerHTML = '<option value="">-- Error connecting to server --</option>';
      showToast("Error loading backup list.", "error");
    }
  }

  elBtnRefreshBackups.addEventListener("click", () => {
    fetchBackupList();
    showToast("Backup list refreshed.", "info");
  });

  // ==========================================================================
  // VAULT LOAD & AUTO-DECRYPTION TRIGGERS
  // ==========================================================================

  elBtnLoadBackup.addEventListener("click", async () => {
    const selectedFile = elBackupDropdown.value;
    if (!selectedFile) {
      showToast("No backup selected.", "error");
      return;
    }
    
    try {
      showToast("Fetching backup file...", "info");
      const response = await fetch(`/api/backups/file/${selectedFile}`, {
        headers: {
          "X-Session-Token": sessionToken
        }
      });
      
      if (!response.ok) throw new Error("Failed to fetch backup file");
      
      const content = await response.text();
      await decryptAndLoadBackup(content);
    } catch (err) {
      console.error(err);
      showToast("Failed to load backup from server.", "error");
    }
  });

  // Manual Local file upload
  elUploadFileInput.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    
    const reader = new FileReader();
    reader.onload = async (evt) => {
      const content = evt.target.result;
      await decryptAndLoadBackup(content);
    };
    reader.readAsText(file);
    elUploadFileInput.value = ""; // Reset file input
  });

  /**
   * Tries to automatically decrypt a SentryKey vault using the session key.
   * If it fails (e.g. because password differs), triggers fallback manual prompt.
   */
  async function decryptAndLoadBackup(content) {
    try {
      const parsed = JSON.parse(content);
      
      if (!parsed.encrypted) {
        // Plaintext backup handling
        accounts = parsed.accounts || [];
        hideDecryptionGate();
        renderAccountsGrid();
        startCountdownTicker();
        showToast(`Loaded plaintext backup with ${accounts.length} accounts.`, "success");
        return;
      }

      // Try automatic local decryption using session key
      try {
        const decrypted = await SentryCrypto.decryptWithKey(content, encKeyBytes);
        const vault = JSON.parse(decrypted);
        accounts = vault.accounts || [];
        
        hideDecryptionGate();
        renderAccountsGrid();
        startCountdownTicker();
        showToast(`Auto-decrypted backup file. Loaded ${accounts.length} accounts.`, "success");
      } catch (autoDecryptErr) {
        // Session key failed to decrypt. Fallback: prompt user for passphrase manually.
        console.warn("Auto-decryption failed, prompting manually:", autoDecryptErr);
        showDecryptionGate(content);
      }

    } catch (e) {
      console.error(e);
      showToast("Invalid SentryKey backup file format.", "error");
    }
  }

  // Fallback Manual Decrypt Form Submit
  elVaultDecryptForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const manualPass = elVaultPassInput.value;
    
    try {
      showToast("Decrypting manually...", "info");
      const plaintext = await SentryCrypto.decrypt(encryptedVaultString, manualPass);
      
      const parsedVault = JSON.parse(plaintext);
      accounts = parsedVault.accounts || [];
      
      hideDecryptionGate();
      showToast(`Vault decrypted manually. Loaded ${accounts.length} accounts.`, "success");
      
      renderAccountsGrid();
      startCountdownTicker();
    } catch (err) {
      console.error(err);
      showToast("Decryption failed: Incorrect password.", "error");
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

  elBtnCancelDecryption.addEventListener("click", () => {
    hideDecryptionGate();
    resetDashboardState();
  });

  elBtnCreateScratch.addEventListener("click", () => {
    accounts = [];
    renderAccountsGrid();
    startCountdownTicker();
    showToast("Created empty vault scratchpad.", "success");
  });

  function resetDashboardState() {
    stopCountdownTicker();
    accounts = [];
    encryptedVaultString = "";
    elAccountsGrid.innerHTML = "";
    elAccountsGrid.appendChild(elEmptyState);
    elEmptyState.style.display = "block";
  }

  // ==========================================================================
  // RENDER ACCOUNTS & TOTP CODES
  // ==========================================================================

  function getIssuerIcon(label) {
    const clean = label.toLowerCase();
    const icons = {
      github: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>`,
      google: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12.24 10.285V14.4h6.887c-.648 2.41-2.519 4.113-5.136 4.113-3.41 0-6.177-2.767-6.177-6.177 0-3.41 2.767-6.177 6.177-6.177 1.632 0 3.07.633 4.17 1.665L21.35 4.85C19.14 2.8 15.96 1.5 12.24 1.5 6.3 1.5 1.5 6.3 1.5 12.24s4.8 10.74 10.74 10.74c6.14 0 10.74-4.3 10.74-10.74 0-.675-.06-1.3-.175-1.96H12.24z"/></svg>`,
      slack: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523 2.528 2.528 0 0 1-2.522-2.523 2.528 2.528 0 0 1 2.522-2.52h2.52v2.52zm1.261 0a2.528 2.528 0 0 1 2.52-2.52h5.043a2.528 2.528 0 0 1 2.522 2.52v5.042a2.528 2.528 0 0 1-2.522 2.52H8.823a2.528 2.528 0 0 1-2.52-2.52v-5.042zM8.823 5.043a2.528 2.528 0 0 1 2.52-2.522 2.528 2.528 0 0 1 2.522 2.522v2.52h-2.522a2.528 2.528 0 0 1-2.52-2.52zm0 1.261a2.528 2.528 0 0 1 2.52 2.52v5.043a2.528 2.528 0 0 1-2.522 2.522H3.781a2.528 2.528 0 0 1-2.522-2.522 2.528 2.528 0 0 1 2.522-2.52h5.042zm10.135 3.86a2.528 2.528 0 0 1 2.522-2.52 2.528 2.528 0 0 1 2.52 2.52 2.528 2.528 0 0 1-2.52 2.52h-2.522v-2.52zm-1.262 0a2.528 2.528 0 0 1-2.52 2.52h-5.043a2.528 2.528 0 0 1-2.522-2.52V3.782a2.528 2.528 0 0 1 2.522-2.52h5.043a2.52 2.52v5.042zm-3.781 10.134a2.528 2.528 0 0 1-2.522 2.522 2.528 2.528 0 0 1-2.52-2.522v-2.52h2.52a2.528 2.528 0 0 1 2.52 2.52zm0-1.261a2.528 2.528 0 0 1-2.52 2.52h-5.043a2.528 2.528 0 0 1-2.522-2.52v-5.043a2.528 2.528 0 0 1 2.522-2.52h5.043a2.52 2.52v5.043z"/></svg>`,
      discord: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.504 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994.021-.041.001-.09-.041-.106a13.094 13.094 0 0 1-1.873-.894.077.077 0 0 1-.008-.128c.126-.093.252-.19.372-.287a.075.075 0 0 1 .077-.011c3.92 1.793 8.18 1.793 12.061 0a.073.073 0 0 1 .078.009c.12.099.246.195.373.289a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.894.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.156-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.156 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.156-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.156 2.418z"/></svg>`,
      microsoft: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M11.4 24H0V12.6h11.4V24zM24 24H12.6V12.6H24V24zM11.4 11.4H0V0h11.4v11.4zM24 11.4H12.6V0H24v11.4z"/></svg>`,
      spotify: `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12s5.37 12 12 12 12-5.37 12-12S18.63 0 12 0zm5.49 17.3c-.2.31-.61.41-.92.21-2.5-1.53-5.65-1.87-9.36-1.03-.35.08-.7-.14-.78-.49-.08-.35.14-.7.49-.78 4.07-.93 7.56-.54 10.37 1.18.31.2.41.61.2 1.92zm1.46-3.26c-.25.39-.77.52-1.16.27-2.86-1.76-7.23-2.27-10.62-1.24-.44.13-.9-.12-1.03-.56-.13-.44.12-.9.56-1.03 4.88-1.48 9.7-1 12.98 1.02.4.24.52.77.27 1.54zm.13-3.37C15.22 8.4 8.82 8.18 5.1 9.31c-.57.17-1.18-.15-1.35-.72-.17-.57.15-1.18.72-1.35 4.28-1.3 11.35-1.04 15.82 1.62.51.3 68.85 1.37.38 1.88a1.1 1.1 0 0 1-1.38-.38z"/></svg>`
    };

    for (const [key, value] of Object.entries(icons)) {
      if (clean.includes(key)) return value;
    }
    return `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`;
  }

  function renderAccountsGrid() {
    elAccountsGrid.innerHTML = "";
    
    const query = elSearchInput.value.trim().toLowerCase();
    const filtered = accounts.filter(acc => 
      acc.label.toLowerCase().includes(query)
    );
    
    if (filtered.length === 0) {
      if (accounts.length === 0) {
        elAccountsGrid.appendChild(elEmptyState);
        elEmptyState.style.display = "block";
      } else {
        const noResults = document.createElement("div");
        noResults.className = "empty-vault glass";
        noResults.innerHTML = `
          <div class="empty-vault-icon">🔍</div>
          <h3>No matching accounts</h3>
          <p>No results found for "${elSearchInput.value}". Try checking the spelling.</p>
        `;
        elAccountsGrid.appendChild(noResults);
      }
      return;
    }

    elEmptyState.style.display = "none";

    filtered.forEach((acc, index) => {
      const card = document.createElement("div");
      card.className = "account-card glass";
      card.id = `account-card-${index}`;
      
      const parts = acc.label.split(":");
      let issuer = parts[0].trim();
      let accountName = parts.length > 1 ? parts.slice(1).join(":").trim() : "";
      
      if (issuer.includes("(") && !accountName) {
        const match = issuer.match(/(.*?)\s*\((.*?)\)/);
        if (match) {
          issuer = match[1];
          accountName = match[2];
        }
      }
      
      if (!accountName) {
        accountName = issuer;
        issuer = "SentryKey";
      }

      card.innerHTML = `
        <div class="card-actions">
          <button class="card-action-btn delete-btn" data-index="${index}" title="Remove Account">🗑️</button>
        </div>
        <div class="account-header">
          <div class="account-info">
            <div class="account-label">${accountName}</div>
            <div class="account-issuer">${issuer.toUpperCase()}</div>
          </div>
          <div class="account-icon">
            ${getIssuerIcon(acc.label)}
          </div>
        </div>
        <div class="account-code-container">
          <div class="account-code" id="code-val-${index}" data-secret="${acc.secret}">------</div>
          <div class="timer-container">
            <svg class="timer-ring" width="44" height="44">
              <circle class="timer-ring-back" cx="22" cy="22" r="18" />
              <circle class="timer-ring-front" id="ring-val-${index}" cx="22" cy="22" r="18" />
            </svg>
            <div class="timer-text" id="time-val-${index}">--</div>
          </div>
        </div>
      `;
      
      elAccountsGrid.appendChild(card);
    });

    // Code copy to clipboard
    document.querySelectorAll(".account-code").forEach(codeEl => {
      codeEl.addEventListener("click", () => {
        const rawCode = codeEl.textContent.replace(/\s+/g, "");
        if (rawCode === "------") return;
        
        navigator.clipboard.writeText(rawCode).then(() => {
          showToast("Code copied to clipboard!", "success");
        }).catch(err => {
          console.error("Copy failed:", err);
          showToast("Failed to copy code.", "error");
        });
      });
    });

    // Account deletion
    document.querySelectorAll(".delete-btn").forEach(btn => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        const idx = parseInt(btn.getAttribute("data-index"), 10);
        const name = accounts[idx].label;
        if (confirm(`Are you sure you want to remove "${name}"?`)) {
          accounts.splice(idx, 1);
          renderAccountsGrid();
          showToast("Account removed.", "info");
        }
      });
    });
  }

  async function tickCodes() {
    const now = Math.floor(Date.now() / 1000);
    const remaining = 30 - (now % 30);
    const radius = 18;
    const circumference = 2 * Math.PI * radius;
    const dashoffset = circumference * (remaining / 30);

    const codeEls = document.querySelectorAll(".account-code");
    
    for (let i = 0; i < codeEls.length; i++) {
      const el = codeEls[i];
      const idx = el.id.replace("code-val-", "");
      const secret = el.getAttribute("data-secret");
      
      const code = await SentryOTP.getTOTPCode(secret, now);
      el.textContent = `${code.slice(0,3)} ${code.slice(3,6)}`;
      
      const ring = document.getElementById(`ring-val-${idx}`);
      const text = document.getElementById(`time-val-${idx}`);
      
      if (ring && text) {
        ring.style.strokeDasharray = `${circumference} ${circumference}`;
        ring.style.strokeDashoffset = dashoffset;
        text.textContent = remaining;

        if (remaining <= 5) {
          ring.classList.add("warning");
          text.classList.add("warning");
          el.classList.add("warning");
        } else {
          ring.classList.remove("warning");
          text.classList.remove("warning");
          el.classList.remove("warning");
        }
      }
    }
  }

  function startCountdownTicker() {
    stopCountdownTicker();
    tickCodes();
    countdownTimerId = setInterval(tickCodes, 1000);
  }

  function stopCountdownTicker() {
    if (countdownTimerId) {
      clearInterval(countdownTimerId);
      countdownTimerId = null;
    }
  }

  elSearchInput.addEventListener("input", renderAccountsGrid);

  // ==========================================================================
  // ADD ACCOUNT FLOW
  // ==========================================================================

  elBtnAddAccount.addEventListener("click", () => {
    elAddAccountModal.classList.add("open");
  });

  elBtnAddAccountCancel.addEventListener("click", () => {
    elAddAccountModal.classList.remove("open");
    elNewAccountLabel.value = "";
    elNewAccountSecret.value = "";
  });

  elAddAccountForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const label = elNewAccountLabel.value.trim();
    const secret = elNewAccountSecret.value.trim().toUpperCase().replace(/\s+/g, "");

    if (!label || !secret) {
      showToast("Label and Secret are required.", "error");
      return;
    }

    const decoded = SentryOTP.decodeBase32(secret);
    if (decoded.length === 0) {
      showToast("Invalid secret: Not a valid Base32 key.", "error");
      return;
    }

    accounts.push({ label, secret });
    elAddAccountModal.classList.remove("open");
    elNewAccountLabel.value = "";
    elNewAccountSecret.value = "";
    
    renderAccountsGrid();
    showToast(`Added account "${label}"`, "success");
  });

  // ==========================================================================
  // SYNC VAULT TO CLOUD (AUTO-ENCRYPTED) & EXPORT
  // ==========================================================================

  elBtnExportBackup.addEventListener("click", async () => {
    if (accounts.length === 0) {
      showToast("Vault is empty. Add accounts before downloading.", "error");
      return;
    }

    try {
      showToast("Generating encrypted backup file...", "info");
      const vaultData = { accounts };
      
      // Encrypt with the session key derived from your login credentials
      const encrypted = await SentryCrypto.encryptWithKey(JSON.stringify(vaultData), encKeyBytes);
      
      const blob = new Blob([encrypted], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${username}-sentrykey-vault.skbackup`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      
      showToast("Encrypted backup download started.", "success");
    } catch (e) {
      console.error(e);
      showToast("Failed to generate backup.", "error");
    }
  });

  elBtnSaveCloud.addEventListener("click", async () => {
    if (accounts.length === 0) {
      showToast("Vault is empty. Add accounts before syncing.", "error");
      return;
    }

    try {
      showToast("Encrypting and syncing vault...", "info");
      
      const vaultData = { accounts };
      const encryptedPayload = await SentryCrypto.encryptWithKey(JSON.stringify(vaultData), encKeyBytes);
      
      const response = await fetch("/api/backups/upload", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Session-Token": sessionToken
        },
        body: encryptedPayload
      });
      
      if (!response.ok) {
        const errData = await response.json();
        throw new Error(errData.error || "Failed to upload backup.");
      }
      
      showToast("Vault securely synced to your cloud account.", "success");
      fetchBackupList();
    } catch (e) {
      console.error(e);
      showToast(e.message || "Failed to sync to server.", "error");
    }
  });

});
