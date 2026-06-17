// Optional email (SMTP) + SMS (Twilio REST) senders for recovery codes.
// EVERYTHING here is gated by .env: with nothing configured, sending is a no-op
// and the recovery flow falls back to "recovery key only" (no provider needed).
// Drop SMTP_* / TWILIO_* into .env later to light these up.

let nodemailer = null;
try { nodemailer = require('nodemailer'); } catch (_) { /* optional dependency */ }

const SMTP_HOST = process.env.SMTP_HOST;
const SMTP_PORT = parseInt(process.env.SMTP_PORT || '587', 10);
const SMTP_USER = process.env.SMTP_USER;
const SMTP_PASS = process.env.SMTP_PASS;
const SMTP_FROM = process.env.SMTP_FROM || SMTP_USER;

const TWILIO_SID = process.env.TWILIO_ACCOUNT_SID;
const TWILIO_TOKEN = process.env.TWILIO_AUTH_TOKEN;
const TWILIO_FROM = process.env.TWILIO_FROM_NUMBER;

const emailEnabled = !!(nodemailer && SMTP_HOST && SMTP_USER && SMTP_PASS);
const smsEnabled = !!(TWILIO_SID && TWILIO_TOKEN && TWILIO_FROM);

let transporter = null;
if (emailEnabled) {
  transporter = nodemailer.createTransport({
    host: SMTP_HOST,
    port: SMTP_PORT,
    secure: SMTP_PORT === 465,
    auth: { user: SMTP_USER, pass: SMTP_PASS }
  });
}

async function sendEmail(to, subject, text) {
  if (!emailEnabled || !to) return false;
  await transporter.sendMail({ from: SMTP_FROM, to, subject, text });
  return true;
}

async function sendSms(to, body) {
  if (!smsEnabled || !to) return false;
  const url = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_SID}/Messages.json`;
  const params = new URLSearchParams({ To: to, From: TWILIO_FROM, Body: body });
  const auth = Buffer.from(`${TWILIO_SID}:${TWILIO_TOKEN}`).toString('base64');
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params
  });
  if (!res.ok) throw new Error(`SMS send failed (${res.status})`);
  return true;
}

module.exports = { emailEnabled, smsEnabled, sendEmail, sendSms };
