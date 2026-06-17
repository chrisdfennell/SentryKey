require('dotenv').config();
const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { rateLimit } = require('express-rate-limit');

const app = express();
const PORT = process.env.PORT || 3000;
const SERVER_ACCESS_PASSPHRASE = process.env.SERVER_ACCESS_PASSPHRASE;
const MAX_BACKUPS_RETAINED = parseInt(process.env.MAX_BACKUPS_RETAINED || '15', 10);

const BACKUPS_DIR = path.join(__dirname, 'backups');
const DB_DIR = path.join(__dirname, 'db');
const DB_FILE = path.join(DB_DIR, 'users.json');

// Ensure directories exist
if (!fs.existsSync(BACKUPS_DIR)) fs.mkdirSync(BACKUPS_DIR, { recursive: true });
if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });

// Ensure DB file exists
if (!fs.existsSync(DB_FILE)) {
  fs.writeFileSync(DB_FILE, JSON.stringify({ users: {}, sessions: {} }, null, 2), 'utf8');
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

// --- Database Helper Functions ---

function loadDb() {
  try {
    const data = fs.readFileSync(DB_FILE, 'utf8');
    return JSON.parse(data);
  } catch (err) {
    console.error("Error loading DB, resetting:", err);
    return { users: {}, sessions: {} };
  }
}

function saveDb(db) {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
  } catch (err) {
    console.error("Error saving DB:", err);
  }
}

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

    if (files.length > MAX_BACKUPS_RETAINED) {
      const toDelete = files.slice(MAX_BACKUPS_RETAINED);
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

  const db = loadDb();
  const session = db.sessions[token];

  if (!session) {
    return res.status(401).json({ error: 'Unauthorized: Invalid or expired session.' });
  }

  // Optional: Expire session after 30 days
  const thirtyDays = 30 * 24 * 60 * 60 * 1000;
  if (Date.now() - session.createdAt > thirtyDays) {
    delete db.sessions[token];
    saveDb(db);
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

  const db = loadDb();
  if (db.users[cleanUsername]) {
    return res.status(409).json({ error: 'Username is already taken.' });
  }

  try {
    // Hash the client's Auth Key using Node's native scrypt algorithm
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync(authKey, salt, 64).toString('hex');

    db.users[cleanUsername] = {
      salt,
      hash,
      createdAt: Date.now()
    };
    saveDb(db);

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
  const db = loadDb();
  const user = db.users[cleanUsername];

  if (!user) {
    return res.status(401).json({ error: 'Invalid username or password.' });
  }

  try {
    // Verify the Auth Key against the stored scrypt hash
    const hash = crypto.scryptSync(authKey, user.salt, 64).toString('hex');
    if (hash !== user.hash) {
      return res.status(401).json({ error: 'Invalid username or password.' });
    }

    // Generate random 32-byte session token
    const token = crypto.randomBytes(32).toString('hex');
    db.sessions[token] = {
      username: cleanUsername,
      createdAt: Date.now()
    };
    saveDb(db);

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
    const db = loadDb();
    if (db.sessions[token]) {
      const username = db.sessions[token].username;
      delete db.sessions[token];
      saveDb(db);
      console.log(`User logged out: ${username}`);
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
  console.log(` DB location: ${DB_FILE}                            `);
  console.log(` Backups folder: ${BACKUPS_DIR}                     `);
  console.log(` Invite code required for register: ${!!SERVER_ACCESS_PASSPHRASE}`);
  console.log(`===================================================`);
});
