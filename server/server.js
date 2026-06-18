require('dotenv').config();
const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { rateLimit } = require('express-rate-limit');
const notify = require('./notify');
const { createStore, migrateLegacyJson } = require('./db');
const SERVER_VERSION = (() => { try { return require('./package.json').version; } catch (_) { return 'unknown'; } })();

const app = express();
const PORT = process.env.PORT || 3000;
const SERVER_ACCESS_PASSPHRASE = process.env.SERVER_ACCESS_PASSPHRASE;
const MAX_BACKUPS_RETAINED = parseInt(process.env.MAX_BACKUPS_RETAINED || '15', 10);

// Subscription tiers — groundwork for future paid cloud plans (no billing yet).
// Everyone is "free" today; a user's plan is stored on their account record
// (absent = free). "pro" simply retains more backups for now.
const PLANS = {
  free: { label: 'Free', maxBackups: MAX_BACKUPS_RETAINED },
  pro:  { label: 'Pro',  maxBackups: 100 },
};
function planInfo(plan) { return PLANS[plan] || PLANS.free; }

// Admins are normal zero-knowledge accounts whose username is listed in
// ADMIN_USERS (.env, comma-separated). They can view account METADATA (usernames,
// plans, usage) and set plans — never vault contents, which stay encrypted.
const ADMIN_USERS = (process.env.ADMIN_USERS || '')
  .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
function isAdmin(username) { return ADMIN_USERS.includes(String(username || '').toLowerCase()); }
function requireAdmin(req, res, next) {
  if (!isAdmin(req.username)) return res.status(403).json({ error: 'Admin access required.' });
  next();
}

const BACKUPS_DIR = process.env.BACKUPS_DIR || path.join(__dirname, 'backups');
const DB_DIR = process.env.DB_DIR || path.join(__dirname, 'db');
const LEGACY_DB_FILE = path.join(DB_DIR, 'users.json');

// Ensure directories exist
if (!fs.existsSync(BACKUPS_DIR)) fs.mkdirSync(BACKUPS_DIR, { recursive: true });
if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });

// SQLite-backed store for accounts + sessions (atomic, crash-safe). Replaces the
// old single users.json file.
const store = createStore(DB_DIR);

// One-time import of a pre-existing users.json into SQLite (archives it after).
const migration = migrateLegacyJson(store, LEGACY_DB_FILE);
if (migration.migrated) {
  console.log(`Imported legacy users.json into SQLite: ${migration.users} user(s), ${migration.sessions} session(s).`);
} else if (migration.error) {
  console.error('Legacy users.json import error:', migration.error);
}

app.use(cors());
app.use(express.json({ limit: '1mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Rate limiters
const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 100,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many requests, please try again later.' }
});

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 15, // tight limit on auth endpoints
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many authentication attempts. Please try again later.' }
});

// --- Data store ---
// Accounts + sessions live in SQLite via ./db (the `store` created above).
// Encrypted vault backups remain individual files under BACKUPS_DIR.

// Prune old backups, retaining only the latest N for a specific user
const pruneOldBackups = (username) => {
  try {
    const userDir = path.join(BACKUPS_DIR, username);
    if (!fs.existsSync(userDir)) return;

    const files = fs.readdirSync(userDir)
      .filter(f => f.startsWith('backup-') && f.endsWith('.skbackup'))
      .map(f => {
        const filePath = path.join(userDir, f);
        const stat = fs.statSync(filePath);
        return { name: f, path: filePath, mtime: stat.mtimeMs };
      })
      .sort((a, b) => b.mtime - a.mtime); // Newest first

    // Retain count depends on the user's plan (free vs pro).
    const maxBackups = planInfo((store.getUser(username) || {}).plan).maxBackups;
    if (files.length > maxBackups) {
      const toDelete = files.slice(maxBackups);
      toDelete.forEach(file => {
        fs.unlinkSync(file.path);
        console.log(`Pruned old backup for [${username}]: ${file.name}`);
      });
    }
  } catch (err) {
    console.error(`Error pruning backups for [${username}]:`, err);
  }
};

// --- Authentication Middleware ---

const authenticateSession = (req, res, next) => {
  const token = req.headers['x-session-token'];
  if (!token) {
    return res.status(401).json({ error: 'Unauthorized: Session token missing.' });
  }

  const session = store.getSession(token);

  if (!session) {
    return res.status(401).json({ error: 'Unauthorized: Invalid or expired session.' });
  }

  // Optional: Expire session after 30 days
  const thirtyDays = 30 * 24 * 60 * 60 * 1000;
  if (Date.now() - session.createdAt > thirtyDays) {
    store.deleteSession(token);
    return res.status(401).json({ error: 'Unauthorized: Session expired.' });
  }

  req.username = session.username;
  next();
};

// --- Authentication API Endpoints ---

// Register User
app.post('/api/auth/register', authLimiter, (req, res) => {
  const { username, authKey, inviteCode } = req.body;

  if (!username || !authKey) {
    return res.status(400).json({ error: 'Username and Auth Key are required.' });
  }

  const cleanUsername = username.trim().toLowerCase();
  if (cleanUsername.length < 3 || !/^[a-zA-Z0-9_]+$/.test(cleanUsername)) {
    return res.status(400).json({ error: 'Invalid username: Must be at least 3 alphanumeric characters/underscores.' });
  }

  // If a global server passphrase is set, check it as an invite code
  if (SERVER_ACCESS_PASSPHRASE && inviteCode !== SERVER_ACCESS_PASSPHRASE) {
    return res.status(403).json({ error: 'Invalid invitation / server access passphrase.' });
  }

  if (store.getUser(cleanUsername)) {
    return res.status(409).json({ error: 'Username is already taken.' });
  }

  try {
    // Hash the client's Auth Key using Node's native scrypt algorithm
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync(authKey, salt, 64).toString('hex');

    store.putUser(cleanUsername, {
      salt,
      hash,
      plan: 'free',
      createdAt: Date.now()
    });

    // Create user's isolated backup directory
    const userDir = path.join(BACKUPS_DIR, cleanUsername);
    if (!fs.existsSync(userDir)) {
      fs.mkdirSync(userDir, { recursive: true });
    }

    console.log(`Registered user: ${cleanUsername}`);
    res.status(201).json({ message: 'User registered successfully.' });
  } catch (err) {
    console.error('Registration error:', err);
    res.status(500).json({ error: 'Internal server error during registration.' });
  }
});

// Login User
app.post('/api/auth/login', authLimiter, (req, res) => {
  const { username, authKey } = req.body;

  if (!username || !authKey) {
    return res.status(400).json({ error: 'Username and Auth Key are required.' });
  }

  const cleanUsername = username.trim().toLowerCase();
  const user = store.getUser(cleanUsername);

  if (!user) {
    return res.status(401).json({ error: 'Invalid username or password.' });
  }

  try {
    // Verify the Auth Key against the stored scrypt hash
    const hash = crypto.scryptSync(authKey, user.salt, 64).toString('hex');
    if (hash !== user.hash) {
      return res.status(401).json({ error: 'Invalid username or password.' });
    }
    if (user.suspended) {
      return res.status(403).json({ error: 'This account has been suspended. Contact the administrator.' });
    }

    // Generate random 32-byte session token
    const token = crypto.randomBytes(32).toString('hex');
    store.putSession(token, {
      username: cleanUsername,
      createdAt: Date.now()
    });

    console.log(`User logged in: ${cleanUsername}`);
    res.json({ message: 'Login successful.', token, username: cleanUsername });
  } catch (err) {
    console.error('Login error:', err);
    res.status(500).json({ error: 'Internal server error during login.' });
  }
});

// Logout User
app.post('/api/auth/logout', (req, res) => {
  const token = req.headers['x-session-token'];
  if (token) {
    const session = store.getSession(token);
    if (session) {
      store.deleteSession(token);
      console.log(`User logged out: ${session.username}`);
    }
  }
  res.json({ message: 'Logged out successfully.' });
});

// Ping verification for session
app.get('/api/ping', authenticateSession, (req, res) => {
  res.json({ status: 'ok', username: req.username, app: 'SentryKey Cloud Server' });
});

// Check if Server Access Passphrase is required for registration
app.get('/api/auth/config', (req, res) => {
  res.json({ registrationRestricted: !!SERVER_ACCESS_PASSPHRASE });
});

// Authenticated account overview: plan, usage, and limits. Returns only counts
// and sizes — never secret content — so it stays zero-knowledge.
app.get('/api/account', apiLimiter, authenticateSession, (req, res) => {
  const user = store.getUser(req.username) || {};
  const plan = user.plan || 'free';
  const info = planInfo(plan);
  const usage = backupUsage(req.username);
  res.json({
    username: req.username,
    plan,
    planLabel: info.label,
    isAdmin: isAdmin(req.username),
    createdAt: user.createdAt || null,
    backups: { count: usage.count, bytes: usage.bytes, max: info.maxBackups },
  });
});

// --- Admin (managed-service operator tools; metadata only, zero-knowledge intact) ---

// List all accounts with metadata + usage.
app.get('/api/admin/users', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const users = store.allUsers().map((u) => ({
    username: u.username,
    plan: u.plan || 'free',
    suspended: !!u.suspended,
    createdAt: u.createdAt || null,
    hasRecovery: !!u.recovery,
    backups: backupUsage(u.username),
  })).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  res.json({ users, plans: Object.keys(PLANS) });
});

// Server-wide totals.
app.get('/api/admin/stats', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const users = store.allUsers();
  const planCounts = {};
  let totalBackups = 0, totalBytes = 0;
  for (const u of users) {
    const plan = u.plan || 'free';
    planCounts[plan] = (planCounts[plan] || 0) + 1;
    const usage = backupUsage(u.username);
    totalBackups += usage.count;
    totalBytes += usage.bytes;
  }
  res.json({ users: users.length, totalBackups, totalBytes, planCounts });
});

// Set a user's plan — the only way to grant "pro" until billing exists.
app.post('/api/admin/users/:username/plan', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const target = String(req.params.username || '').trim().toLowerCase();
  const plan = String(req.body.plan || '').trim().toLowerCase();
  if (!PLANS[plan]) return res.status(400).json({ error: 'Unknown plan.' });
  const user = store.getUser(target);
  if (!user) return res.status(404).json({ error: 'User not found.' });
  user.plan = plan;
  store.putUser(target, user);
  console.log(`Admin ${req.username} set ${target} -> ${plan}`);
  res.json({ username: target, plan });
});

// Server info / health for operators.
app.get('/api/admin/server', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  res.json({
    version: SERVER_VERSION,
    node: process.version,
    uptimeSeconds: Math.floor(process.uptime()),
    registrationRestricted: !!SERVER_ACCESS_PASSPHRASE,
    plans: PLANS,
    users: store.countUsers(),
    sessions: store.countSessions(),
    admins: ADMIN_USERS,
  });
});

// Suspend / unsuspend an account (blocks login; data is kept).
app.post('/api/admin/users/:username/suspend', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const target = String(req.params.username || '').trim().toLowerCase();
  const suspended = !!req.body.suspended;
  const user = store.getUser(target);
  if (!user) return res.status(404).json({ error: 'User not found.' });
  user.suspended = suspended;
  store.putUser(target, user);
  if (suspended) store.deleteSessionsForUser(target); // kick active sessions
  console.log(`Admin ${req.username} ${suspended ? 'suspended' : 'unsuspended'} ${target}`);
  res.json({ username: target, suspended });
});

// Force-logout: revoke all of a user's sessions.
app.post('/api/admin/users/:username/revoke-sessions', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const target = String(req.params.username || '').trim().toLowerCase();
  if (!store.getUser(target)) return res.status(404).json({ error: 'User not found.' });
  store.deleteSessionsForUser(target);
  console.log(`Admin ${req.username} revoked sessions for ${target}`);
  res.json({ username: target, ok: true });
});

// Delete an account: record + sessions + stored backups. Irreversible.
app.delete('/api/admin/users/:username', apiLimiter, authenticateSession, requireAdmin, (req, res) => {
  const target = String(req.params.username || '').trim().toLowerCase();
  if (target === req.username) return res.status(400).json({ error: "You can't delete your own admin account from here." });
  if (!store.getUser(target)) return res.status(404).json({ error: 'User not found.' });
  store.deleteSessionsForUser(target);
  store.deleteUser(target);
  deleteBackupsForUser(target);
  console.log(`Admin ${req.username} DELETED account ${target}`);
  res.json({ username: target, deleted: true });
});

// --- Zero-Knowledge Account Recovery ---
// The client wraps its encryption key under a one-time RECOVERY KEY (held only by
// the user) and proves possession via a derived recoveryAuthKey. The server stores
// only the wrapped blob + a hash of the auth key — it can't decrypt anything.
// Email/SMS (notify.js) are an OPTIONAL second factor; with no provider configured
// the recovery key alone is sufficient. See notify.js.

function sha256(s) { return crypto.createHash('sha256').update(s).digest('hex'); }
function maskEmail(e) { const [u, d] = String(e).split('@'); return (u ? u[0] : '') + '***@' + (d || ''); }
function maskPhone(p) { const s = String(p); return '***' + s.slice(-4); }

// Channels available for a user (email/sms on file AND provider configured).
function recoveryChannels(user) {
  const ch = [];
  if (user.email && notify.emailEnabled) ch.push('email');
  if (user.phone && notify.smsEnabled) ch.push('sms');
  return ch;
}

// Backup file count + total bytes for a user (metadata only, no contents read).
function backupUsage(username) {
  let count = 0, bytes = 0;
  try {
    const dir = path.join(BACKUPS_DIR, username);
    if (fs.existsSync(dir)) {
      for (const f of fs.readdirSync(dir)) {
        if (f.startsWith('backup-') && f.endsWith('.skbackup')) {
          count += 1;
          bytes += fs.statSync(path.join(dir, f)).size;
        }
      }
    }
  } catch (_) { /* best effort */ }
  return { count, bytes };
}

// Remove all of a user's stored backups (used by admin account deletion).
function deleteBackupsForUser(username) {
  try {
    const dir = path.join(BACKUPS_DIR, username);
    if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true });
  } catch (_) { /* best effort */ }
}

// Newest stored backup ciphertext for a user, or null.
function latestBackupContent(username) {
  try {
    const dir = path.join(BACKUPS_DIR, username);
    if (!fs.existsSync(dir)) return null;
    const files = fs.readdirSync(dir)
      .filter(f => f.startsWith('backup-') && f.endsWith('.skbackup'))
      .map(f => ({ f, m: fs.statSync(path.join(dir, f)).mtimeMs }))
      .sort((a, b) => b.m - a.m);
    if (!files.length) return null;
    return fs.readFileSync(path.join(dir, files[0].f), 'utf8');
  } catch (_) { return null; }
}

// Writes a SentryKey envelope as a new backup for the user.
function writeBackupForUser(username, envelopeObj) {
  const userDir = path.join(BACKUPS_DIR, username);
  if (!fs.existsSync(userDir)) fs.mkdirSync(userDir, { recursive: true });
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const ymd = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
  const hms = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const filename = `backup-${ymd}-${hms}.skbackup`;
  fs.writeFileSync(path.join(userDir, filename), JSON.stringify(envelopeObj, null, 2), 'utf8');
  pruneOldBackups(username);
  return filename;
}

// Enroll recovery for the logged-in user (client computed the wrap + auth key).
app.post('/api/recovery/setup', authLimiter, authenticateSession, (req, res) => {
  const { salt, blob, authKey, email, phone } = req.body;
  if (!salt || !blob || !authKey) {
    return res.status(400).json({ error: 'salt, blob and authKey are required.' });
  }
  const user = store.getUser(req.username);
  if (!user) return res.status(404).json({ error: 'User not found.' });

  const authSalt = crypto.randomBytes(16).toString('hex');
  user.recovery = {
    salt,
    blob,
    authSalt,
    authHash: crypto.scryptSync(authKey, authSalt, 64).toString('hex')
  };
  if (email !== undefined) user.email = String(email).trim() || undefined;
  if (phone !== undefined) user.phone = String(phone).trim() || undefined;
  delete user.recoveryOtp;
  store.putUser(req.username, user);
  res.json({ message: 'Recovery enabled.', channels: recoveryChannels(user) });
});

// Begin recovery: if an OTP channel is configured, send a code; else proceed.
app.post('/api/recovery/start', authLimiter, async (req, res) => {
  const cleanUsername = String(req.body.username || '').trim().toLowerCase();
  const user = store.getUser(cleanUsername);
  if (!user || !user.recovery) {
    return res.status(404).json({ error: 'No recovery is set up for this account.' });
  }
  const channels = recoveryChannels(user);
  if (channels.length === 0) {
    return res.json({ otpRequired: false });
  }
  const otp = String(Math.floor(100000 + Math.random() * 900000));
  user.recoveryOtp = { hash: sha256(otp), exp: Date.now() + 5 * 60 * 1000, attempts: 0 };
  store.putUser(cleanUsername, user);
  const sentTo = [];
  try {
    if (channels.includes('email')) { await notify.sendEmail(user.email, 'SentryKey recovery code', `Your SentryKey recovery code is ${otp}. It expires in 5 minutes.`); sentTo.push(maskEmail(user.email)); }
    if (channels.includes('sms')) { await notify.sendSms(user.phone, `SentryKey recovery code: ${otp} (expires in 5 min)`); sentTo.push(maskPhone(user.phone)); }
  } catch (err) {
    console.error('Recovery send failed:', err.message);
    return res.status(502).json({ error: 'Could not send the recovery code. Try again later.' });
  }
  res.json({ otpRequired: true, sentTo });
});

function otpOk(user, otp) {
  const o = user.recoveryOtp;
  if (!o) return false;
  if (Date.now() > o.exp || o.attempts >= 5) return false;
  o.attempts += 1;
  return sha256(String(otp || '')) === o.hash;
}

// Return the wrapped recovery material (+ latest vault) for the client to unwrap.
app.post('/api/recovery/fetch', authLimiter, (req, res) => {
  const cleanUsername = String(req.body.username || '').trim().toLowerCase();
  const user = store.getUser(cleanUsername);
  if (!user || !user.recovery) return res.status(404).json({ error: 'No recovery on file.' });
  if (recoveryChannels(user).length > 0) {
    const ok = otpOk(user, req.body.otp);
    store.putUser(cleanUsername, user); // persist the attempts counter
    if (!ok) return res.status(401).json({ error: 'Invalid or expired code.' });
  }
  res.json({ salt: user.recovery.salt, blob: user.recovery.blob, vault: latestBackupContent(cleanUsername) });
});

// Finalize recovery: prove the recovery key, rotate login + recovery, store the
// re-encrypted vault, and hand back a fresh session.
app.post('/api/recovery/reset', authLimiter, (req, res) => {
  const cleanUsername = String(req.body.username || '').trim().toLowerCase();
  const { recoveryAuthKey, otp, newAuthKey, newRecovery, vault } = req.body;
  const user = store.getUser(cleanUsername);
  if (!user || !user.recovery) return res.status(404).json({ error: 'No recovery on file.' });
  if (!recoveryAuthKey || !newAuthKey || !newRecovery || !newRecovery.salt || !newRecovery.blob || !newRecovery.authKey) {
    return res.status(400).json({ error: 'Missing recovery reset fields.' });
  }
  if (recoveryChannels(user).length > 0 && !otpOk(user, otp)) {
    store.putUser(cleanUsername, user);
    return res.status(401).json({ error: 'Invalid or expired code.' });
  }
  // Verify the user actually holds the recovery key.
  const expected = crypto.scryptSync(recoveryAuthKey, user.recovery.authSalt, 64).toString('hex');
  if (expected !== user.recovery.authHash) {
    return res.status(401).json({ error: 'Invalid recovery key.' });
  }

  // Rotate the login credential to the new master password's auth key.
  const salt = crypto.randomBytes(16).toString('hex');
  user.salt = salt;
  user.hash = crypto.scryptSync(newAuthKey, salt, 64).toString('hex');

  // Rotate the recovery material (re-wrapped under the new encryption key).
  const authSalt = crypto.randomBytes(16).toString('hex');
  user.recovery = {
    salt: newRecovery.salt,
    blob: newRecovery.blob,
    authSalt,
    authHash: crypto.scryptSync(newRecovery.authKey, authSalt, 64).toString('hex')
  };
  delete user.recoveryOtp;

  // Store the re-encrypted vault (optional — only if the client had one).
  if (vault) {
    try {
      const env = typeof vault === 'string' ? JSON.parse(vault) : vault;
      if (env && env.app === 'SentryKey' && env.encrypted && env.ciphertext) {
        writeBackupForUser(cleanUsername, env);
      }
    } catch (_) { /* ignore a malformed vault */ }
  }

  // Persist the rotated credentials + issue a fresh session.
  const token = crypto.randomBytes(32).toString('hex');
  store.putUser(cleanUsername, user);
  store.putSession(token, { username: cleanUsername, createdAt: Date.now() });
  console.log(`Recovery reset for: ${cleanUsername}`);
  res.json({ message: 'Recovery successful.', token, username: cleanUsername });
});

// --- User-Sandboxed Backup API Endpoints ---

// List all backups for the logged-in user
app.get('/api/backups', apiLimiter, authenticateSession, (req, res) => {
  const userDir = path.join(BACKUPS_DIR, req.username);
  if (!fs.existsSync(userDir)) {
    fs.mkdirSync(userDir, { recursive: true });
  }

  try {
    const files = fs.readdirSync(userDir)
      .filter(f => f.startsWith('backup-') && f.endsWith('.skbackup'))
      .map(f => {
        const filePath = path.join(userDir, f);
        const stat = fs.statSync(filePath);
        const nameParts = f.replace('backup-', '').replace('.skbackup', '').split('-');
        let dateLabel = stat.mtime;
        if (nameParts.length >= 2) {
          const yyyymmdd = nameParts[0];
          const hhmmss = nameParts[1];
          dateLabel = `${yyyymmdd.slice(0,4)}-${yyyymmdd.slice(4,6)}-${yyyymmdd.slice(6,8)} ${hhmmss.slice(0,2)}:${hhmmss.slice(2,4)}:${hhmmss.slice(4,6)}`;
        }
        return {
          filename: f,
          sizeBytes: stat.size,
          mtimeMs: stat.mtimeMs,
          timestamp: dateLabel
        };
      })
      .sort((a, b) => b.mtimeMs - a.mtimeMs);

    res.json({ backups: files });
  } catch (err) {
    console.error(`Error listing backups for ${req.username}:`, err);
    res.status(500).json({ error: 'Failed to list backups.' });
  }
});

// Get a specific backup for the logged-in user
app.get('/api/backups/file/:filename', apiLimiter, authenticateSession, (req, res) => {
  const { filename } = req.params;
  const cleanFilename = path.basename(filename);

  if (!cleanFilename.startsWith('backup-') || !cleanFilename.endsWith('.skbackup')) {
    return res.status(400).json({ error: 'Invalid backup filename format.' });
  }

  const filePath = path.join(BACKUPS_DIR, req.username, cleanFilename);
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'Backup file not found.' });
  }

  try {
    const content = fs.readFileSync(filePath, 'utf8');
    res.type('application/json').send(content);
  } catch (err) {
    console.error(`Error reading backup ${cleanFilename} for ${req.username}:`, err);
    res.status(500).json({ error: 'Failed to read backup file.' });
  }
});

// Upload a new encrypted backup for the logged-in user
app.post('/api/backups/upload', apiLimiter, authenticateSession, (req, res) => {
  const payload = req.body;

  if (!payload || typeof payload !== 'object') {
    return res.status(400).json({ error: 'Invalid payload: must be a JSON object' });
  }

  if (payload.app !== 'SentryKey' || !payload.encrypted || !payload.ciphertext) {
    return res.status(400).json({ error: 'Invalid backup structure: must be an encrypted SentryKey vault' });
  }

  const userDir = path.join(BACKUPS_DIR, req.username);
  if (!fs.existsSync(userDir)) {
    fs.mkdirSync(userDir, { recursive: true });
  }

  try {
    const now = new Date();
    const pad = (num) => String(num).padStart(2, '0');
    const yyyymmdd = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
    const hhmmss = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    const filename = `backup-${yyyymmdd}-${hhmmss}.skbackup`;

    const filePath = path.join(userDir, filename);
    fs.writeFileSync(filePath, JSON.stringify(payload, null, 2), 'utf8');

    console.log(`Saved new backup for [${req.username}]: ${filename}`);

    pruneOldBackups(req.username);

    res.status(201).json({ message: 'Backup stored successfully.', filename });
  } catch (err) {
    console.error(`Error saving backup for ${req.username}:`, err);
    res.status(500).json({ error: 'Failed to save backup file.' });
  }
});

// Delete a specific backup for the logged-in user
app.delete('/api/backups/:filename', apiLimiter, authenticateSession, (req, res) => {
  const { filename } = req.params;
  const cleanFilename = path.basename(filename);

  if (!cleanFilename.startsWith('backup-') || !cleanFilename.endsWith('.skbackup')) {
    return res.status(400).json({ error: 'Invalid backup filename format.' });
  }

  const filePath = path.join(BACKUPS_DIR, req.username, cleanFilename);
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'Backup file not found.' });
  }

  try {
    fs.unlinkSync(filePath);
    console.log(`Deleted backup for [${req.username}]: ${cleanFilename}`);
    res.json({ message: 'Backup deleted successfully.' });
  } catch (err) {
    console.error(`Error deleting backup ${cleanFilename} for ${req.username}:`, err);
    res.status(500).json({ error: 'Failed to delete backup file.' });
  }
});

// Start the server
app.listen(PORT, () => {
  console.log(`===================================================`);
  console.log(` SentryKey Zero-Knowledge VPS Server is running     `);
  console.log(` Port: ${PORT}                                      `);
  console.log(` DB location: ${store.dbPath}                       `);
  console.log(` Backups folder: ${BACKUPS_DIR}                     `);
  console.log(` Invite code required for register: ${!!SERVER_ACCESS_PASSPHRASE}`);
  console.log(`===================================================`);
});
