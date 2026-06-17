# SentryKey VPS Server Deployment Guide

This directory contains the self-hosted **SentryKey Web** landing page, interactive dashboard, and secure cloud backup vault APIs. You can easily deploy this lightweight Node.js service on any Virtual Private Server (VPS) running Linux (e.g., Ubuntu, Debian).

---

## ⚡ Quick deploy (recommended): Docker Compose + automatic HTTPS

This is the fastest path for **sentrykey.app**. It runs the server behind
[Caddy](https://caddyserver.com/), which obtains and renews Let's Encrypt TLS
certificates automatically — no Nginx/Certbot setup required.

**Prerequisites**
- A Linux host with a **public IP** and Docker + the Compose plugin installed.
- DNS: an **A record** (and AAAA if you have IPv6) for `sentrykey.app` (and
  `www.sentrykey.app`) pointing at that host's IP.
- Firewall: ports **80 and 443** open (port 80 is needed for the TLS challenge).

**Deploy**
```bash
# on your local machine: copy the server dir up
scp -r ./server user@your-vps-ip:/opt/sentrykey

# on the VPS:
cd /opt/sentrykey
cp .env.example .env
nano .env                      # set a strong SERVER_ACCESS_PASSPHRASE
docker compose up -d --build
```

That's it. Visit `https://sentrykey.app` — Caddy issues the certificate on first
request. Data persists in the `sentrykey-db` and `sentrykey-backups` volumes;
certificates persist in `caddy_data`.

```bash
docker compose logs -f          # watch startup / cert issuance
docker compose pull && docker compose up -d --build   # update later
```

> The domain in [`Caddyfile`](Caddyfile) is `sentrykey.app`. Change it there if
> you use a different domain/subdomain.

The manual options below (PM2 / systemd / raw Docker + Nginx + Certbot) remain
available if you prefer to manage TLS yourself.

---

## 🛠️ Step 1: Copy files to your VPS
You can copy this `server/` directory to your VPS using `scp`, `rsync`, or by cloning your repository:
```bash
# Example using scp (run from your local machine)
scp -r ./server user@your-vps-ip:/opt/sentrykey
```

---

## ⚙️ Step 2: Configure Environment
SSH into your VPS, navigate to the directory, and configure your `.env` file:
```bash
cd /opt/sentrykey
cp .env.template .env # or write a new one
nano .env
```

Ensure you configure a strong **Server Access Passphrase** to prevent public unauthorized access:
```env
PORT=3000
SERVER_ACCESS_PASSPHRASE=use_a_very_strong_random_password_here
MAX_BACKUPS_RETAINED=15
```

---

## 🚀 Step 3: Run the Service
Choose one of the following production-ready methods to run your server:

### Option A: Using PM2 (Recommended for Node)
PM2 is a production process manager that keeps your application alive, auto-restarts on crash, and starts on boot:
```bash
# Install PM2 globally
sudo npm install -g pm2

# Start SentryKey server
pm2 start server.js --name sentrykey-vault

# Save PM2 state and configure start-on-boot
pm2 save
pm2 startup
```

### Option B: Running as a Systemd Service
If you prefer standard Linux system services:
1. Create a service file:
   ```bash
   sudo nano /etc/systemd/system/sentrykey.service
   ```
2. Paste the following configuration:
   ```ini
   [Unit]
   Description=SentryKey Web Vault Server
   After=network.target

   [Service]
   Type=simple
   User=nobody
   WorkingDirectory=/opt/sentrykey
   ExecStart=/usr/bin/node server.js
   Restart=on-failure
   Environment=PORT=3000
   Environment=SERVER_ACCESS_PASSPHRASE=your_vps_passphrase_here

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable sentrykey
   sudo systemctl start sentrykey
   ```

### Option C: Using Docker
You can run SentryKey inside a secure, containerized sandbox:
```bash
# Build the Docker image
docker build -t sentrykey-vault .

# Run the container (persisting backups to host directory)
docker run -d \
  -p 3000:3000 \
  -v /var/sentrykey/backups:/usr/src/app/backups \
  -e SERVER_ACCESS_PASSPHRASE=your_vps_passphrase_here \
  --name sentrykey-vault \
  --restart unless-stopped \
  sentrykey-vault
```

---

## 🔒 Step 4: Configure Nginx Reverse Proxy & SSL (HTTPS)
Since SentryKey deals with 2FA metadata, **HTTPS is strictly required** for the browser's Web Crypto API to function correctly (`SubtleCrypto` is disabled by browsers on unencrypted `http://` pages).

1. Install Nginx:
   ```bash
   sudo apt update
   sudo apt install nginx
   ```
2. Create an Nginx server configuration block:
   ```bash
   sudo nano /etc/nginx/sites-available/sentrykey
   ```
3. Paste the following config (replace `yourdomain.com` with your actual domain or subdomain):
   ```nginx
   server {
       listen 80;
       server_name yourdomain.com;

       location / {
           proxy_pass http://localhost:3000;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection 'upgrade';
           proxy_set_header Host $host;
           proxy_cache_bypass $http_upgrade;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
       }
   }
   ```
4. Enable the configuration and restart Nginx:
   ```bash
   sudo ln -s /etc/nginx/sites-available/sentrykey /etc/nginx/sites-enabled/
   sudo nginx -t
   sudo systemctl restart nginx
   ```
5. Install Certbot and secure with Let's Encrypt SSL:
   ```bash
   sudo apt install certbot python3-certbot-nginx
   sudo certbot --nginx -d yourdomain.com
   ```
   Follow the prompts. Certbot will configure automated SSL renewal and redirect all HTTP traffic to secure HTTPS.

---

## 📲 How to Backup your companion app
1. Export a **Passphrase-locked Backup** from SentryKey Android (`.skbackup` file) or copy the encrypted string from SentryKey iOS.
2. Open your SentryKey VPS domain in your browser.
3. Authenticate with your **Server Access Passphrase**.
4. In the Sync Bar, click **Load Local Backup** and select your `.skbackup` file.
5. Enter your **Backup Passphrase** to decrypt and view/manage your vault.
6. Click **Sync to Cloud** to store this backup on your VPS. It will now be saved on disk and listable in the dropdown, allowing you to access it, edit it, and sync it back from any device.
