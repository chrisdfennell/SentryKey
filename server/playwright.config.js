const path = require('path');
const os = require('os');
const { defineConfig } = require('@playwright/test');

// Each run gets fresh temp data dirs so there are no leftover users/vaults, and
// a known invite passphrase so registration is deterministic regardless of .env.
const stamp = String(Date.now());
const PORT = 31100;
const DB_DIR = path.join(os.tmpdir(), 'sk-e2e-db-' + stamp);
const BACKUPS_DIR = path.join(os.tmpdir(), 'sk-e2e-backups-' + stamp);

module.exports = defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  reporter: 'list',
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node server.js',
    url: `http://127.0.0.1:${PORT}/api/auth/config`,
    timeout: 30_000,
    reuseExistingServer: false,
    env: {
      PORT: String(PORT),
      DB_DIR,
      BACKUPS_DIR,
      SERVER_ACCESS_PASSPHRASE: 'e2e-invite',
    },
  },
});
