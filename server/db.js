// SQLite data layer for SentryKey accounts + sessions.
//
// Replaces the old single `db/users.json` file (which was rewritten in full on
// every change and silently reset to empty if a crash mid-write corrupted it).
// SQLite gives us atomic, crash-safe writes (WAL), durable storage, and indexed
// point lookups instead of re-parsing the whole file on every request.
//
// Encrypted vault *backups* are intentionally NOT stored here — they remain
// individual files under BACKUPS_DIR (already per-user and append-only-ish).
//
// User records are stored as a JSON blob per row so the exact object shape the
// handlers expect ({ salt, hash, createdAt, recovery?, email?, phone?,
// recoveryOtp? }) is preserved verbatim — no field mapping to drift out of sync.

const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

/**
 * Open (creating if needed) the SQLite store in `dbDir`.
 * @param {string} dbDir directory to hold sentrykey.db (+ WAL sidecar files)
 */
function createStore(dbDir) {
  fs.mkdirSync(dbDir, { recursive: true });
  const dbPath = path.join(dbDir, 'sentrykey.db');

  const db = new Database(dbPath);
  // WAL = concurrent readers + atomic commits; NORMAL = durable without fsync
  // on every write (safe under WAL). These survive process/container restarts.
  db.pragma('journal_mode = WAL');
  db.pragma('synchronous = NORMAL');

  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      username TEXT PRIMARY KEY,
      data     TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token      TEXT PRIMARY KEY,
      username   TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_sessions_username ON sessions(username);
  `);

  const q = {
    getUser:     db.prepare('SELECT data FROM users WHERE username = ?'),
    putUser:     db.prepare(`INSERT INTO users (username, data) VALUES (?, ?)
                             ON CONFLICT(username) DO UPDATE SET data = excluded.data`),
    delUser:     db.prepare('DELETE FROM users WHERE username = ?'),
    countUsers:  db.prepare('SELECT COUNT(*) AS n FROM users'),
    getSession:  db.prepare('SELECT username, created_at FROM sessions WHERE token = ?'),
    putSession:  db.prepare(`INSERT INTO sessions (token, username, created_at) VALUES (?, ?, ?)
                             ON CONFLICT(token) DO UPDATE SET username = excluded.username, created_at = excluded.created_at`),
    delSession:  db.prepare('DELETE FROM sessions WHERE token = ?'),
  };

  return {
    db,
    dbPath,

    /** @returns {object|null} the user record, or null if absent */
    getUser(username) {
      const row = q.getUser.get(username);
      return row ? JSON.parse(row.data) : null;
    },
    /** Insert or replace a user record (the whole object). */
    putUser(username, obj) {
      q.putUser.run(username, JSON.stringify(obj));
    },
    deleteUser(username) {
      q.delUser.run(username);
    },
    countUsers() {
      return q.countUsers.get().n;
    },

    /** @returns {{username:string, createdAt:number}|null} */
    getSession(token) {
      const row = q.getSession.get(token);
      return row ? { username: row.username, createdAt: row.created_at } : null;
    },
    putSession(token, session) {
      q.putSession.run(token, session.username, session.createdAt);
    },
    deleteSession(token) {
      q.delSession.run(token);
    },
  };
}

/**
 * One-time import of a legacy `users.json` ({ users, sessions }) into SQLite.
 * Runs only when the users table is empty, then archives the JSON file so it is
 * never re-imported (and never silently lost). Idempotent and transactional.
 *
 * @returns {{migrated:boolean, users?:number, sessions?:number, reason?:string, error?:string}}
 */
function migrateLegacyJson(store, legacyJsonPath) {
  try {
    if (store.countUsers() > 0) return { migrated: false, reason: 'users table already populated' };
    if (!fs.existsSync(legacyJsonPath)) return { migrated: false, reason: 'no legacy users.json' };

    const data = JSON.parse(fs.readFileSync(legacyJsonPath, 'utf8'));
    const users = (data && data.users) || {};
    const sessions = (data && data.sessions) || {};

    const importAll = store.db.transaction(() => {
      for (const [username, obj] of Object.entries(users)) {
        if (obj && typeof obj === 'object') store.putUser(username, obj);
      }
      for (const [token, sess] of Object.entries(sessions)) {
        if (sess && sess.username && sess.createdAt) store.putSession(token, sess);
      }
    });
    importAll();

    // Keep the original file as a timestamped backup (never delete user data).
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    fs.renameSync(legacyJsonPath, `${legacyJsonPath}.imported-${stamp}.bak`);

    return { migrated: true, users: Object.keys(users).length, sessions: Object.keys(sessions).length };
  } catch (err) {
    console.error('Legacy users.json migration failed:', err);
    return { migrated: false, error: err.message };
  }
}

module.exports = { createStore, migrateLegacyJson };
