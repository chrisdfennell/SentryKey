// Integration test for the SQLite-backed SentryKey server.
//
// Spawns the REAL server (server.js) against throwaway temp DB/backup dirs and
// exercises the full API surface, then verifies the one-time users.json -> SQLite
// migration. No external services needed (SERVER_ACCESS_PASSPHRASE left unset, so
// no invite gate; recovery uses no email/SMS provider, so no OTP).
//
//   node test_server.js     (exit 0 = all pass)

const { spawn } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

let failures = 0;
function check(name, cond) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
}

const mkTmp = (p) => fs.mkdtempSync(path.join(os.tmpdir(), p));

const INVITE = 'test-invite-code';
function startServer({ port, dbDir, backupsDir }) {
  // Set a known passphrase in the child env. dotenv.config() does NOT override
  // already-set vars, so this wins over any server/.env and keeps the test
  // deterministic; register calls pass it as the invite code.
  const env = { ...process.env, PORT: String(port), DB_DIR: dbDir, BACKUPS_DIR: backupsDir, SERVER_ACCESS_PASSPHRASE: INVITE, ADMIN_USERS: 'adminuser' };
  // stderr is inherited (not piped) to avoid a libuv handle-close assertion on
  // Windows when the child is killed.
  return spawn(process.execPath, ['server.js'], { cwd: __dirname, env, stdio: ['ignore', 'ignore', 'inherit'] });
}

async function waitReady(base, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try { if ((await fetch(`${base}/api/auth/config`)).ok) return; } catch (_) {}
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('server did not become ready in time');
}

async function jpost(base, p, body, headers = {}) {
  const r = await fetch(`${base}${p}`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body || {}) });
  let body2 = null; try { body2 = await r.json(); } catch (_) {}
  return { status: r.status, body: body2 };
}
async function jget(base, p, headers = {}) {
  const r = await fetch(`${base}${p}`, { headers });
  let body2 = null; try { body2 = await r.json(); } catch (_) {}
  return { status: r.status, body: body2 };
}
async function jdel(base, p, headers = {}) {
  const r = await fetch(`${base}${p}`, { method: 'DELETE', headers });
  let body2 = null; try { body2 = await r.json(); } catch (_) {}
  return { status: r.status, body: body2 };
}

const sampleVault = () => ({ app: 'SentryKey', version: 1, encrypted: true, kdf: 'pbkdf2', iterations: 210000, salt: 'bb', iv: 'aa', ciphertext: 'deadbeef' });

async function phaseFresh() {
  console.log('\n--- Phase A: fresh SQLite flow ---');
  const port = 38211, dbDir = mkTmp('skdb-'), backupsDir = mkTmp('skbk-'), base = `http://127.0.0.1:${port}`;
  const srv = startServer({ port, dbDir, backupsDir });
  try {
    await waitReady(base);
    const u = 'alice', k1 = 'authkey-one-1234567890';

    check('register -> 201', (await jpost(base, '/api/auth/register', { username: u, authKey: k1, inviteCode: INVITE })).status === 201);
    check('duplicate register -> 409', (await jpost(base, '/api/auth/register', { username: u, authKey: k1, inviteCode: INVITE })).status === 409);
    check('login wrong key -> 401', (await jpost(base, '/api/auth/login', { username: u, authKey: 'nope' })).status === 401);

    const login = await jpost(base, '/api/auth/login', { username: u, authKey: k1 });
    check('login ok -> 200 + token', login.status === 200 && !!login.body?.token);
    const auth = { 'x-session-token': login.body?.token };

    check('ping authenticates', (await jget(base, '/api/ping', auth)).body?.username === u);
    check('upload backup -> 201', (await jpost(base, '/api/backups/upload', sampleVault(), auth)).status === 201);

    const list = await jget(base, '/api/backups', auth);
    check('list shows 1 backup', list.body?.backups?.length === 1);
    const dl = await fetch(`${base}/api/backups/file/${list.body.backups[0].filename}`, { headers: auth });
    check('download matches ciphertext', (await dl.json()).ciphertext === 'deadbeef');

    const account = await jget(base, '/api/account', auth);
    check('account -> free plan + 1 backup', account.body?.plan === 'free' && account.body?.planLabel === 'Free' && account.body?.backups?.count === 1);

    check('recovery setup -> channels []', (await jpost(base, '/api/recovery/setup', { salt: 'rs', blob: 'rb', authKey: 'rauth' }, auth)).body?.channels?.length === 0);
    check('recovery start -> otpRequired false', (await jpost(base, '/api/recovery/start', { username: u })).body?.otpRequired === false);
    const fetchR = await jpost(base, '/api/recovery/fetch', { username: u });
    check('recovery fetch -> blob + vault', fetchR.body?.blob === 'rb' && !!fetchR.body?.vault);

    const k2 = 'authkey-two-new-99999';
    const reset = await jpost(base, '/api/recovery/reset', { username: u, recoveryAuthKey: 'rauth', newAuthKey: k2, newRecovery: { salt: 'rs2', blob: 'rb2', authKey: 'rauth2' } });
    check('recovery reset -> 200 + token', reset.status === 200 && !!reset.body?.token);
    check('login with NEW key works', (await jpost(base, '/api/auth/login', { username: u, authKey: k2 })).status === 200);
    check('login with OLD key fails', (await jpost(base, '/api/auth/login', { username: u, authKey: k1 })).status === 401);

    check('logout -> 200', (await jpost(base, '/api/auth/logout', {}, auth)).status === 200);
    check('ping after logout -> 401', (await jget(base, '/api/ping', auth)).status === 401);
    check('sentrykey.db file created', fs.existsSync(path.join(dbDir, 'sentrykey.db')));
  } finally {
    srv.kill();
  }
}

async function phaseMigration() {
  console.log('\n--- Phase B: legacy users.json migration ---');
  const port = 38212, dbDir = mkTmp('skdb2-'), backupsDir = mkTmp('skbk2-'), base = `http://127.0.0.1:${port}`;
  const u = 'legacyuser', k = 'legacy-authkey-abc';
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(k, salt, 64).toString('hex');
  fs.writeFileSync(path.join(dbDir, 'users.json'),
    JSON.stringify({ users: { [u]: { salt, hash, createdAt: Date.now() } }, sessions: {} }, null, 2));

  const srv = startServer({ port, dbDir, backupsDir });
  try {
    await waitReady(base);
    check('migrated user can log in', (await jpost(base, '/api/auth/login', { username: u, authKey: k })).status === 200);
    check('legacy users.json removed', !fs.existsSync(path.join(dbDir, 'users.json')));
    check('archive .bak present', fs.readdirSync(dbDir).some((f) => f.startsWith('users.json.imported-')));
  } finally {
    srv.kill();
  }
}

async function phaseAdmin() {
  console.log('\n--- Phase C: admin panel ---');
  const port = 38213, dbDir = mkTmp('skdb3-'), backupsDir = mkTmp('skbk3-'), base = `http://127.0.0.1:${port}`;
  const srv = startServer({ port, dbDir, backupsDir });
  try {
    await waitReady(base);
    const adminK = 'admin-authkey', userK = 'user-authkey';
    await jpost(base, '/api/auth/register', { username: 'adminuser', authKey: adminK, inviteCode: INVITE });
    await jpost(base, '/api/auth/register', { username: 'normaluser', authKey: userK, inviteCode: INVITE });
    const adminAuth = { 'x-session-token': (await jpost(base, '/api/auth/login', { username: 'adminuser', authKey: adminK })).body?.token };
    const userAuth = { 'x-session-token': (await jpost(base, '/api/auth/login', { username: 'normaluser', authKey: userK })).body?.token };

    const list = await jget(base, '/api/admin/users', adminAuth);
    check('admin can list users (2)', list.status === 200 && list.body?.users?.length === 2);
    check('non-admin -> 403 on admin list', (await jget(base, '/api/admin/users', userAuth)).status === 403);

    const setp = await jpost(base, '/api/admin/users/normaluser/plan', { plan: 'pro' }, adminAuth);
    check('admin sets plan -> pro', setp.status === 200 && setp.body?.plan === 'pro');

    const acct = await jget(base, '/api/account', userAuth);
    check('user account reflects pro plan', acct.body?.plan === 'pro' && acct.body?.planLabel === 'Pro');
    check('isAdmin true for admin, false for user',
      (await jget(base, '/api/account', adminAuth)).body?.isAdmin === true && acct.body?.isAdmin === false);
    check('unknown plan -> 400', (await jpost(base, '/api/admin/users/normaluser/plan', { plan: 'enterprise' }, adminAuth)).status === 400);

    const srvInfo = await jget(base, '/api/admin/server', adminAuth);
    check('server info: version + counts', !!srvInfo.body?.version && typeof srvInfo.body?.users === 'number' && typeof srvInfo.body?.sessions === 'number');

    check('suspend user', (await jpost(base, '/api/admin/users/normaluser/suspend', { suspended: true }, adminAuth)).status === 200);
    check('suspended user cannot log in (403)', (await jpost(base, '/api/auth/login', { username: 'normaluser', authKey: userK })).status === 403);
    check('unsuspend restores login',
      (await jpost(base, '/api/admin/users/normaluser/suspend', { suspended: false }, adminAuth)).status === 200
      && (await jpost(base, '/api/auth/login', { username: 'normaluser', authKey: userK })).status === 200);

    const freshTok = (await jpost(base, '/api/auth/login', { username: 'normaluser', authKey: userK })).body?.token;
    await jpost(base, '/api/admin/users/normaluser/revoke-sessions', {}, adminAuth);
    check('revoked session token is rejected (401)', (await jget(base, '/api/account', { 'x-session-token': freshTok })).status === 401);

    await jpost(base, '/api/auth/register', { username: 'tempuser', authKey: 'temp-k', inviteCode: INVITE });
    check('admin deletes account', (await jdel(base, '/api/admin/users/tempuser', adminAuth)).status === 200);
    check('deleted user cannot log in (401)', (await jpost(base, '/api/auth/login', { username: 'tempuser', authKey: 'temp-k' })).status === 401);
    check('admin cannot delete self (400)', (await jdel(base, '/api/admin/users/adminuser', adminAuth)).status === 400);
  } finally {
    srv.kill();
  }
}

async function phaseSync() {
  console.log('\n--- Phase D: multi-device sync (vault revisions + conflict) ---');
  const port = 38214, dbDir = mkTmp('skdb4-'), backupsDir = mkTmp('skbk4-'), base = `http://127.0.0.1:${port}`;
  const srv = startServer({ port, dbDir, backupsDir });
  try {
    await waitReady(base);
    const k = 'sync-authkey';
    await jpost(base, '/api/auth/register', { username: 'syncuser', authKey: k, inviteCode: INVITE });
    const auth = { 'x-session-token': (await jpost(base, '/api/auth/login', { username: 'syncuser', authKey: k })).body?.token };
    const upload = (baseRev) => jpost(base, '/api/backups/upload', sampleVault(),
      baseRev === undefined ? auth : { ...auth, 'x-base-rev': String(baseRev) });

    check('initial rev is 0', (await jget(base, '/api/backups', auth)).body?.rev === 0);
    const up1 = await upload(0);
    check('upload at base rev 0 -> rev 1', up1.status === 201 && up1.body?.rev === 1);
    const up2 = await upload(0);
    check('stale base rev -> 409 + currentRev', up2.status === 409 && up2.body?.currentRev === 1);
    const up3 = await upload(1);
    check('upload at base rev 1 -> rev 2', up3.status === 201 && up3.body?.rev === 2);
    const up4 = await upload(undefined); // legacy client, no header -> still accepted
    check('legacy upload (no base rev) accepted -> rev 3', up4.status === 201 && up4.body?.rev === 3);
    check('list reflects rev 3', (await jget(base, '/api/backups', auth)).body?.rev === 3);
  } finally {
    srv.kill();
  }
}

(async () => {
  await phaseFresh();
  await phaseMigration();
  await phaseAdmin();
  await phaseSync();
  console.log(`\n${failures === 0 ? 'ALL PASS ✅' : failures + ' FAILURE(S) ❌'}`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
