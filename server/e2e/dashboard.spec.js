const { test, expect } = require('@playwright/test');

async function registerAndOpen(page, username, password) {
  await page.goto('/login.html');
  await page.click('#tab-signup');
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('#btn-auth-submit');
  await page.waitForURL('**/dashboard.html');
  await expect(page.locator('#sidebar-user-display')).toContainText(username.toUpperCase());
}
async function loginAndOpen(page, username, password) {
  await page.goto('/login.html');
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('#btn-auth-submit');
  await page.waitForURL('**/dashboard.html');
  await expect(page.locator('#sidebar-user-display')).toContainText(username.toUpperCase());
}
// Add an account and wait for the encrypted upload to land (status 201 — even
// if it took a 409->merge->retry to get there).
async function addAccount(page, label, secret) {
  const uploaded = page.waitForResponse((r) => r.url().includes('/api/backups/upload') && r.status() === 201);
  await page.click('#btn-add-account');
  await page.fill('#new-account-label', label);
  await page.fill('#new-account-secret', secret);
  await page.click('#btn-add-account-submit');
  await uploaded;
}

// End-to-end smoke test of the zero-knowledge web dashboard against a real
// running server: register (keys derived in-browser) -> add an account -> the
// change is encrypted client-side and auto-saved to the cloud -> a reload
// re-fetches from the cloud and decrypts it. Exercises crypto.js + dashboard.js
// + the SQLite-backed API together.
test('register, add an account, and it survives a reload (cloud round-trip)', async ({ page }) => {
  const username = 'e2e' + String(Date.now()).slice(-9);
  const password = 'e2e-master-pass';

  await page.goto('/login.html');

  // --- Register (zero-knowledge: PBKDF2 runs in the browser) ---
  await page.click('#tab-signup');
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('#btn-auth-submit');

  // --- Auto-login redirects to the dashboard ---
  await page.waitForURL('**/dashboard.html');
  await expect(page.locator('#sidebar-user-display')).toContainText(username.toUpperCase());

  // --- Add an account; the change auto-saves (encrypted) to the cloud ---
  // Listen for the upload before triggering it (auto-save is debounced ~1.2s).
  const uploadResp = page.waitForResponse(
    (r) => r.url().includes('/api/backups/upload') && r.request().method() === 'POST'
  );
  await page.click('#btn-add-account');
  await page.fill('#new-account-label', 'GitHub: e2e');
  await page.fill('#new-account-secret', 'JBSWY3DPEHPK3PXP');
  await page.click('#btn-add-account-submit');

  await expect(page.locator('.account-card')).toHaveCount(1);
  await expect(page.locator('.account-card')).toContainText(/github/i);
  expect((await uploadResp).ok()).toBeTruthy();

  // a live 6-digit TOTP code renders (shown grouped as "123 456"; starts "------")
  await expect(page.locator('.account-code')).toHaveText(/^\d{3} \d{3}$/, { timeout: 15_000 });

  // --- Reload: the dashboard auto-fetches the latest vault and decrypts it ---
  await page.reload();
  await expect(page.locator('.account-card')).toHaveCount(1);
  await expect(page.locator('.account-card')).toContainText(/github/i);

  // --- Account panel surfaces usage ---
  await page.click('#nav-account');
  await expect(page.locator('#account-username')).toContainText(username);
  await expect(page.locator('#account-backups')).toContainText('1');
});

// The secret must never appear in plaintext over the wire (zero-knowledge).
test('uploaded backup is ciphertext only (no plaintext secret leaks)', async ({ page }) => {
  const username = 'e2e' + String(Date.now()).slice(-9);
  const password = 'e2e-master-pass';
  const secret = 'KRSXG5CTMVRXEZLU';

  await page.goto('/login.html');
  await page.click('#tab-signup');
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('#btn-auth-submit');
  await page.waitForURL('**/dashboard.html');

  // Capture the upload request body and assert it carries no plaintext secret.
  const uploadResp = page.waitForResponse(
    (r) => r.url().includes('/api/backups/upload') && r.request().method() === 'POST'
  );
  await page.click('#btn-add-account');
  await page.fill('#new-account-label', 'Bank');
  await page.fill('#new-account-secret', secret);
  await page.click('#btn-add-account-submit');

  const req = (await uploadResp).request();
  const body = req.postData() || '';
  expect(body).toContain('"encrypted"');
  expect(body).not.toContain(secret);
});

// The headline reliability guarantee: two devices editing concurrently MERGE
// instead of silently clobbering each other.
test('two devices merge instead of clobbering (conflict resolution)', async ({ browser }) => {
  const username = 'merge' + String(Date.now()).slice(-9);
  const password = 'e2e-master-pass';

  // Device A registers and adds an account.
  const ctxA = await browser.newContext();
  const pageA = await ctxA.newPage();
  await registerAndOpen(pageA, username, password);
  await addAccount(pageA, 'AlphaAcct', 'JBSWY3DPEHPK3PXP');

  // Device B logs in as the same user and loads the current vault (sees Alpha).
  const ctxB = await browser.newContext();
  const pageB = await ctxB.newPage();
  await loginAndOpen(pageB, username, password);
  await expect(pageB.locator('.account-card')).toHaveCount(1);

  // A adds Beta (bumps the server revision). Then B — still on its old revision —
  // adds Gamma: the server returns 409 and B must MERGE, not overwrite.
  await addAccount(pageA, 'BetaAcct', 'KRSXG5CTMVRXEZLU');
  await addAccount(pageB, 'GammaAcct', 'MFRGGZDFMZTWQ2LK');

  // B ends up with all three accounts — nobody's secret was lost.
  await expect(pageB.locator('.account-card')).toHaveCount(3);
  const text = (await pageB.locator('#accounts-grid-container').innerText()).toLowerCase();
  expect(text).toContain('alpha');
  expect(text).toContain('beta');
  expect(text).toContain('gamma');

  await ctxA.close();
  await ctxB.close();
});

// Zero-knowledge recovery via the downloadable Emergency Kit: set up recovery,
// download the kit, then upload it on a fresh session to restore the vault under
// a new master password — no SMS/email provider involved.
test('recover the vault by uploading the Emergency Kit', async ({ page }) => {
  const username = 'rec' + String(Date.now()).slice(-9);
  const password = 'original-master-pass';
  const newPassword = 'brand-new-master-pass';

  await registerAndOpen(page, username, password);
  await addAccount(page, 'RecoverMe', 'JBSWY3DPEHPK3PXP'); // a vault to recover

  // Set up recovery and download the Emergency Kit.
  await page.click('#nav-recovery');
  await expect(page.locator('#recovery-key-value')).not.toHaveText('');
  const downloadPromise = page.waitForEvent('download');
  await page.click('#btn-download-kit'); // also auto-ticks the confirm box
  const kitPath = await (await downloadPromise).path();
  const setupResp = page.waitForResponse((r) => r.url().includes('/api/recovery/setup') && r.status() === 200);
  await page.click('#btn-recovery-enable');
  await setupResp;

  // Fresh session: log out, then recover by uploading the kit.
  await page.click('#btn-logout');
  await page.waitForURL('**/login.html');
  await page.click('#link-forgot');
  await page.setInputFiles('#rec-kit-file', kitPath);
  await expect(page.locator('#rec-username')).toHaveValue(username);     // auto-filled
  await expect(page.locator('#rec-key')).not.toHaveValue('');            // auto-filled
  await page.fill('#rec-new-pass', newPassword);
  await page.fill('#rec-new-pass2', newPassword);
  await page.click('#btn-rec-submit');

  // Lands on the dashboard with the vault restored.
  await page.waitForURL('**/dashboard.html', { timeout: 30_000 });
  await expect(page.locator('.account-card')).toContainText(/recoverme/i);

  // The NEW password works on a fresh login (and the vault is intact).
  await page.click('#btn-logout');
  await page.waitForURL('**/login.html');
  await loginAndOpen(page, username, newPassword);
  await expect(page.locator('.account-card')).toContainText(/recoverme/i);
});
