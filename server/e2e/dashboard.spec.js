const { test, expect } = require('@playwright/test');

const INVITE = 'e2e-invite';

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
  await page.locator('#invite-code').waitFor({ state: 'visible' });
  await page.fill('#invite-code', INVITE);
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

  // --- Account panel surfaces the plan + usage ---
  await page.click('#nav-account');
  await expect(page.locator('#account-plan-badge')).toHaveText('Free');
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
  await page.locator('#invite-code').waitFor({ state: 'visible' });
  await page.fill('#invite-code', INVITE);
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
