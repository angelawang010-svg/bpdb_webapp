# Phase 4: Production Deployment (VPS) — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prepare the application for production deployment on a VPS — production Dockerfiles, Docker Compose, Nginx reverse proxy, HTTPS, firewall, backups, monitoring, and security hardening.

**Architecture:** Single VPS running Docker Compose with 4 containers: Nginx (reverse proxy + static files), Spring Boot (API), PostgreSQL 16 (database), Redis 7 (sessions). Nginx handles HTTPS termination via Let's Encrypt. All ports except 22, 80, 443 blocked by UFW.

**Tech Stack:** Docker, Docker Compose, Nginx, Let's Encrypt/Certbot, UFW, pg_dump, cron, Slack webhooks

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Section 10 (VPS Deployment Plan).

**Prerequisite:** Phases 1-3 complete (full-stack application running locally).

---

### Task 1: Production Dockerfile for Back-End

**Files:**
- Create: `Dockerfile.backend`

**Step 1: Write multi-stage Dockerfile**

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
RUN ./gradlew dependencies --no-daemon
COPY backend/src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/uploads
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
```

**Step 2: Verify build**

Run: `docker build -f Dockerfile.backend -t blog-backend .`
Expected: Image builds successfully.

**Step 3: Commit**

```bash
git add Dockerfile.backend
git commit -m "feat: add production Dockerfile for Spring Boot backend"
```

---

### Task 2: Production Dockerfile for Front-End (Nginx)

**Files:**
- Create: `Dockerfile.frontend`

**Step 1: Write multi-stage Dockerfile**

```dockerfile
# Stage 1: Build
FROM node:20-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# Stage 2: Serve with Nginx
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Nginx config will be mounted via Docker Compose
EXPOSE 80 443
```

**Step 2: Verify build**

Run: `docker build -f Dockerfile.frontend -t blog-frontend .`
Expected: Image builds successfully.

**Step 3: Commit**

```bash
git add Dockerfile.frontend
git commit -m "feat: add production Dockerfile for React frontend (Nginx)"
```

---

### Task 3: Nginx Configuration

**Files:**
- Create: `nginx/nginx.conf`
- Create: `nginx/conf.d/default.conf`

**Step 1: Write Nginx configuration**

`nginx/nginx.conf`:
```nginx
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    sendfile on;
    keepalive_timeout 65;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml;
    gzip_min_length 256;

    # Security headers (applied globally)
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header Content-Security-Policy "script-src 'self'" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    include /etc/nginx/conf.d/*.conf;
}
```

`nginx/conf.d/default.conf`:
```nginx
server {
    listen 80;
    server_name ${DOMAIN_NAME};
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl;
    server_name ${DOMAIN_NAME};

    ssl_certificate /etc/letsencrypt/live/${DOMAIN_NAME}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN_NAME}/privkey.pem;

    # API proxy
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Health check (allow only this actuator endpoint)
    location = /actuator/health {
        proxy_pass http://backend:8080;
    }

    # Block all other actuator endpoints
    location /actuator/ {
        return 404;
    }

    # Uploaded images
    location /uploads/ {
        alias /app/uploads/;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Content-Disposition "inline" always;
        expires 30d;
    }

    # React SPA static files
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;

        # Cache fingerprinted assets
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff2?)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
    }
}
```

**Step 2: Commit**

```bash
git add nginx/
git commit -m "feat: add Nginx config with HTTPS, security headers, API proxy"
```

---

### Task 4: Production Docker Compose

**Files:**
- Create: `docker-compose.prod.yml`

**Step 1: Write production Compose file**

```yaml
services:
  nginx:
    build:
      context: .
      dockerfile: Dockerfile.frontend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
      - uploads:/app/uploads:ro
    depends_on:
      backend:
        condition: service_healthy
    restart: unless-stopped

  backend:
    build:
      context: .
      dockerfile: Dockerfile.backend
    environment:
      DB_HOST: postgres
      DB_NAME: blogplatform
      DB_USERNAME: blogplatform
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      SPRING_PROFILES_ACTIVE: prod
    volumes:
      - uploads:/app/uploads
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: blogplatform
      POSTGRES_USER: blogplatform
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U blogplatform"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis:
    image: redis:7
    command: redis-server --requirepass ${REDIS_PASSWORD}
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  pgdata:
  uploads:
```

**Step 2: Verify Compose config is valid**

Run: `docker compose -f docker-compose.prod.yml config`
Expected: Valid output, no errors.

**Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "feat: add production Docker Compose with all services"
```

---

### Task 5: Environment Configuration Files

**Files:**
- Modify: `.env.example` — add all production secrets
- Modify: `.gitignore` — ensure `.env` and sensitive files excluded

**Step 1: Update .env.example**

```
# Database
DB_PASSWORD=changeme-minimum-24-characters-random

# Redis
REDIS_PASSWORD=changeme-minimum-24-characters-random

# Domain
DOMAIN_NAME=yourdomain.com

# Email (transactional email service)
EMAIL_API_KEY=changeme-your-email-service-api-key
EMAIL_FROM=noreply@yourdomain.com
```

**Step 2: Update .gitignore**

Ensure these are in `.gitignore`:
```
.env
*.pem
/backups/
/uploads/
```

**Step 3: Commit**

```bash
git add .env.example .gitignore
git commit -m "feat: update env example and gitignore for production"
```

---

### Task 6: Database Backup Script

**Files:**
- Create: `scripts/backup.sh`
- Create: `scripts/backup-verify.sh`

**Step 1: Write backup script**

`scripts/backup.sh`:
```bash
#!/bin/bash
set -euo pipefail

BACKUP_DIR="/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DAY_OF_WEEK=$(date +%u)  # 1=Monday, 7=Sunday
DAY_OF_MONTH=$(date +%d)
SLACK_WEBHOOK="${SLACK_WEBHOOK_URL:-}"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"

# Create daily backup
BACKUP_FILE="$BACKUP_DIR/daily/blogplatform_${TIMESTAMP}.sql.gz"
docker compose -f /app/docker-compose.prod.yml exec -T postgres \
  pg_dump -U blogplatform blogplatform | gzip > "$BACKUP_FILE"

if [ $? -ne 0 ]; then
    if [ -n "$SLACK_WEBHOOK" ]; then
        curl -s -X POST "$SLACK_WEBHOOK" \
            -H 'Content-type: application/json' \
            -d '{"text":"⚠️ Blog Platform backup FAILED at '"$TIMESTAMP"'"}'
    fi
    exit 1
fi

# Weekly backup (Sunday)
if [ "$DAY_OF_WEEK" -eq 7 ]; then
    cp "$BACKUP_FILE" "$BACKUP_DIR/weekly/"
fi

# Monthly backup (1st of month)
if [ "$DAY_OF_MONTH" -eq "01" ]; then
    cp "$BACKUP_FILE" "$BACKUP_DIR/monthly/"
fi

# Retention: 7 daily, 4 weekly, 3 monthly
find "$BACKUP_DIR/daily" -name "*.sql.gz" -mtime +7 -delete
find "$BACKUP_DIR/weekly" -name "*.sql.gz" -mtime +30 -delete
find "$BACKUP_DIR/monthly" -name "*.sql.gz" -mtime +90 -delete

echo "Backup completed: $BACKUP_FILE"
```

`scripts/backup-verify.sh` (monthly automated verification):
```bash
#!/bin/bash
set -euo pipefail

LATEST_BACKUP=$(ls -t /backups/daily/*.sql.gz | head -1)
SLACK_WEBHOOK="${SLACK_WEBHOOK_URL:-}"

echo "Verifying backup: $LATEST_BACKUP"

# Start temporary PostgreSQL container
docker run -d --name backup-verify \
  -e POSTGRES_DB=verify -e POSTGRES_USER=verify -e POSTGRES_PASSWORD=verify \
  postgres:16

sleep 10

# Restore backup
gunzip -c "$LATEST_BACKUP" | docker exec -i backup-verify psql -U verify -d verify

# Run integrity checks
TABLES=$(docker exec backup-verify psql -U verify -d verify -t -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")

echo "Tables found: $TABLES"

# Cleanup
docker rm -f backup-verify

if [ "$TABLES" -lt 10 ]; then
    if [ -n "$SLACK_WEBHOOK" ]; then
        curl -s -X POST "$SLACK_WEBHOOK" \
            -H 'Content-type: application/json' \
            -d '{"text":"⚠️ Backup verification FAILED - only '"$TABLES"' tables found"}'
    fi
    exit 1
fi

echo "Backup verification passed: $TABLES tables"
```

**Step 2: Make scripts executable**

Run: `chmod +x scripts/backup.sh scripts/backup-verify.sh`

**Step 3: Commit**

```bash
git add scripts/
git commit -m "feat: add database backup and monthly verification scripts"
```

---

### Task 7: Monitoring — Disk Usage Alert Script

**Files:**
- Create: `scripts/check-disk.sh`

**Step 1: Write disk usage check**

```bash
#!/bin/bash
THRESHOLD=70
SLACK_WEBHOOK="${SLACK_WEBHOOK_URL:-}"
USAGE=$(df / | tail -1 | awk '{print $5}' | tr -d '%')

if [ "$USAGE" -gt "$THRESHOLD" ]; then
    if [ -n "$SLACK_WEBHOOK" ]; then
        curl -s -X POST "$SLACK_WEBHOOK" \
            -H 'Content-type: application/json' \
            -d '{"text":"⚠️ Blog Platform VPS disk usage at '"$USAGE"'% (threshold: '"$THRESHOLD"'%)"}'
    fi
fi
```

**Step 2: Commit**

```bash
git add scripts/check-disk.sh
git commit -m "feat: add disk usage monitoring script with Slack alerts"
```

---

### Task 8: Cron Job Configuration Documentation

**Files:**
- Create: `scripts/crontab.example`

**Step 1: Document cron jobs**

```
# Blog Platform Cron Jobs
# Install with: crontab scripts/crontab.example

# Daily database backup at 3:00 AM
0 3 * * * /app/scripts/backup.sh >> /var/log/backup.log 2>&1

# Disk usage check every 6 hours
0 */6 * * * /app/scripts/check-disk.sh >> /var/log/disk-check.log 2>&1

# Monthly backup verification on the 15th at 4:00 AM
0 4 15 * * /app/scripts/backup-verify.sh >> /var/log/backup-verify.log 2>&1
```

**Step 2: Commit**

```bash
git add scripts/crontab.example
git commit -m "docs: add example crontab for backup and monitoring"
```

---

### Task 9: VPS Setup Script

**Files:**
- Create: `scripts/vps-setup.sh`

**Step 1: Write setup script**

```bash
#!/bin/bash
set -euo pipefail

echo "=== Blog Platform VPS Setup ==="

# 1. Update system
apt update && apt upgrade -y

# 2. Install Docker
curl -fsSL https://get.docker.com | sh

# 3. Install Certbot
apt install -y certbot

# 4. Install fail2ban
apt install -y fail2ban
systemctl enable fail2ban
systemctl start fail2ban

# 5. Configure UFW
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# 6. Harden SSH
sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart sshd

# 7. Create app directory
mkdir -p /app /backups

echo "=== Setup complete ==="
echo "Next steps:"
echo "1. Clone repo to /app"
echo "2. cp .env.example .env && nano .env"
echo "3. certbot certonly --standalone -d yourdomain.com"
echo "4. docker compose -f docker-compose.prod.yml up -d"
echo "5. crontab scripts/crontab.example"
```

**Step 2: Commit**

```bash
git add scripts/vps-setup.sh
git commit -m "feat: add VPS initial setup script with security hardening"
```

---

### Task 10: Local Production Smoke Test

**Step 1: Build and start production stack locally**

```bash
# Create a .env file for local testing
cat > .env << 'EOF'
DB_PASSWORD=localtest12345678901234
REDIS_PASSWORD=localtest12345678901234
DOMAIN_NAME=localhost
EOF

# Build and start
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
```

**Step 2: Verify all services healthy**

Run: `docker compose -f docker-compose.prod.yml ps`
Expected: All 4 containers running and healthy.

**Step 3: Smoke test endpoints**

```bash
# Health check
curl -k https://localhost/actuator/health

# Register
curl -k -X POST https://localhost/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"prod","email":"prod@test.com","password":"Password1"}'

# Verify actuator blocked
curl -k https://localhost/actuator/env  # Should return 404
```

**Step 4: Tear down**

```bash
docker compose -f docker-compose.prod.yml down -v
rm .env
```

**Step 5: Commit any fixes**

```bash
git commit -m "fix: phase 4 production smoke test fixes"
```

---

## Summary

Phase 4 delivers (10 tasks):
- Multi-stage production Dockerfile for Spring Boot backend
- Multi-stage production Dockerfile for React frontend (Nginx)
- Nginx configuration with HTTPS, security headers, API proxy, actuator blocking, SPA routing
- Production Docker Compose with health checks and restart policies
- Environment configuration (.env.example with all secrets documented)
- Database backup script with retention (7 daily + 4 weekly + 3 monthly)
- Monthly automated backup verification
- Disk usage monitoring with Slack webhook alerts
- VPS setup script (Docker, Certbot, fail2ban, UFW, SSH hardening)
- Local production smoke test

**Deployment:** After this phase, the operator provisions a VPS, runs `vps-setup.sh`, clones the repo, configures `.env`, obtains SSL cert, and runs `docker compose up -d`.
