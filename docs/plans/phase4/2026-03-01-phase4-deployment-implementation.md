# Phase 4: Local Production Deployment (Mac) — Implementation Plan

**Version:** 2.0
**Last Updated:** 2026-03-16

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deploy the application via Docker Compose on a personal Mac for local production use — production Dockerfiles, Docker Compose, Nginx reverse proxy (HTTP), database backups, and basic monitoring.

**Architecture:** Docker Compose on macOS with 4 containers: Nginx (reverse proxy + static files), Spring Boot (API), PostgreSQL 16 (database), Redis 7 (sessions). Nginx serves HTTP on localhost. HTTPS is not required for local-only access.

**Tech Stack:** Docker, Docker Compose, Nginx, pg_dump, launchd (macOS scheduler)

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Section 10 (VPS Deployment Plan, adapted for local Mac).

**Prerequisite:** Phases 1-3 complete (full-stack application running locally). Docker Desktop for Mac installed.

---

### Task 1: Production Dockerfile for Back-End

**Files:**
- Create: `Dockerfile.backend`
- Create: `.dockerignore`
- Modify: `backend/build.gradle` — disable plain JAR to prevent multi-JAR build conflicts

**Step 1: Disable plain JAR in build.gradle**

Add to `backend/build.gradle` (after the `java` block):
```groovy
jar {
    enabled = false
}
```

This prevents Gradle from producing both a plain JAR and a fat JAR, which would break the `COPY *.jar` in the Dockerfile.

**Step 2: Write .dockerignore**

```
.git
.gitignore
*.md
.env
.env.*
backups/
node_modules/
frontend/node_modules/
backend/.gradle/
backend/build/
docker-compose*.yml
Dockerfile*
scripts/
nginx/
```

**Step 3: Write multi-stage Dockerfile**

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
RUN ./gradlew bootJar --no-daemon -x test || true
COPY backend/src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system app && adduser --system --ingroup app app

COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/uploads && chown app:app /app/uploads

USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar", "--spring.profiles.active=prod"]
```

**Step 4: Verify build**

Run: `docker build -f Dockerfile.backend -t blog-backend .`
Expected: Image builds successfully.

**Step 5: Commit**

```bash
git add Dockerfile.backend .dockerignore backend/build.gradle
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
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:80/ || exit 1
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
- Create: `nginx/snippets/security-headers.conf`

**Step 1: Write shared security headers snippet**

`nginx/snippets/security-headers.conf`:
```nginx
add_header X-Frame-Options "SAMEORIGIN" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'self'" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

**Step 2: Write main Nginx configuration**

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
    gzip_vary on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml image/svg+xml;
    gzip_min_length 256;

    # Upload size limit
    client_max_body_size 10m;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=auth:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=api:10m rate=30r/s;

    # Logging
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent"';
    access_log /var/log/nginx/access.log main;

    include /etc/nginx/conf.d/*.conf;
}
```

**Step 3: Write server block**

`nginx/conf.d/default.conf`:
```nginx
server {
    listen 80;
    server_name localhost;

    # IMPORTANT: Nginx does not inherit add_header from parent blocks.
    # Each location block MUST include security-headers.conf individually.

    # Auth endpoints — strict rate limiting
    location /api/v1/auth/ {
        limit_req zone=auth burst=3 nodelay;
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        include /etc/nginx/snippets/security-headers.conf;
    }

    # API proxy
    location /api/ {
        limit_req zone=api burst=50 nodelay;
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        include /etc/nginx/snippets/security-headers.conf;
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
        include /etc/nginx/snippets/security-headers.conf;
        add_header Content-Disposition "inline" always;
        expires 30d;
    }

    # React SPA static files
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;
        include /etc/nginx/snippets/security-headers.conf;

        # Cache fingerprinted assets
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff2?)$ {
            include /etc/nginx/snippets/security-headers.conf;
            add_header Cache-Control "public, max-age=31536000, immutable";
        }
    }
}
```

**Step 4: Commit**

```bash
git add nginx/
git commit -m "feat: add Nginx config with security headers, rate limiting, API proxy"
```

---

### Task 4: Production Docker Compose

**Files:**
- Create: `docker-compose.prod.yml`

**Step 1: Write production Compose file**

```yaml
networks:
  frontend:
  backend:

services:
  nginx:
    build:
      context: .
      dockerfile: Dockerfile.frontend
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./nginx/snippets:/etc/nginx/snippets:ro
      - uploads:/app/uploads:ro
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - frontend
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 128M
          cpus: '0.5'

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
    networks:
      - frontend
      - backend
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 512M
          cpus: '1.0'

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
    networks:
      - backend
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 256M
          cpus: '1.0'

  redis:
    image: redis:7
    command: redis-server --requirepass ${REDIS_PASSWORD}
    environment:
      REDISCLI_AUTH: ${REDIS_PASSWORD}
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - backend
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 128M
          cpus: '0.5'

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
git commit -m "feat: add production Docker Compose with network segmentation and resource limits"
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

**Step 1: Write backup script**

`scripts/backup.sh`:
```bash
#!/bin/bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
NOW=$(date +%Y%m%d_%H%M%S_%u_%d)
TIMESTAMP=$(echo "$NOW" | cut -d_ -f1,2)
DAY_OF_WEEK=$(echo "$NOW" | cut -d_ -f3)   # 1=Monday, 7=Sunday
DAY_OF_MONTH=$(echo "$NOW" | cut -d_ -f4)
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"

# Create daily backup
BACKUP_FILE="$BACKUP_DIR/daily/blogplatform_${TIMESTAMP}.sql.gz"
if ! docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump -U blogplatform blogplatform | gzip > "$BACKUP_FILE"; then
    echo "ERROR: Backup failed at $TIMESTAMP" >&2
    exit 1
fi

# Verify backup is meaningful (> 1KB; empty gzip is ~20 bytes)
if [ "$(stat -f%z "$BACKUP_FILE")" -lt 1024 ]; then
    echo "ERROR: Backup file suspiciously small at $TIMESTAMP" >&2
    rm -f "$BACKUP_FILE"
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

echo "Backup completed: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"
```

**Step 2: Make script executable**

Run: `chmod +x scripts/backup.sh`

**Step 3: Commit**

```bash
git add scripts/backup.sh
git commit -m "feat: add database backup script with retention policy"
```

---

### Task 7: Launchd Backup Scheduler (macOS)

**Files:**
- Create: `scripts/com.blogplatform.backup.plist`

**Step 1: Write launchd plist for daily backups**

`scripts/com.blogplatform.backup.plist`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.blogplatform.backup</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>REPLACE_WITH_REPO_PATH/scripts/backup.sh</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        <key>Hour</key>
        <integer>3</integer>
        <key>Minute</key>
        <integer>0</integer>
    </dict>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin</string>
    </dict>
    <key>StandardOutPath</key>
    <string>/tmp/blogplatform-backup.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/blogplatform-backup-error.log</string>
    <key>WorkingDirectory</key>
    <string>REPLACE_WITH_REPO_PATH</string>
</dict>
</plist>
```

Install with:
```bash
# Edit the plist to replace REPLACE_WITH_REPO_PATH with your actual repo path
cp scripts/com.blogplatform.backup.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.blogplatform.backup.plist
```

**Step 2: Commit**

```bash
git add scripts/com.blogplatform.backup.plist
git commit -m "feat: add macOS launchd plist for scheduled backups"
```

---

### Task 8: Local Smoke Test

**Step 1: Create .env and build**

```bash
cat > .env << 'EOF'
DB_PASSWORD=localtest12345678901234
REDIS_PASSWORD=localtest12345678901234
EOF

docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
```

**Step 2: Verify all services healthy**

Run: `docker compose -f docker-compose.prod.yml ps`
Expected: All 4 containers running and healthy.

**Step 3: Smoke test endpoints**

```bash
# Health check
curl http://localhost/actuator/health

# Register
curl -X POST http://localhost/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"prod","email":"prod@test.com","password":"Password1"}'

# Verify actuator blocked
curl http://localhost/actuator/env  # Should return 404
```

**Step 4: Test backup**

```bash
./scripts/backup.sh
ls -la backups/daily/
```

**Step 5: Tear down**

```bash
docker compose -f docker-compose.prod.yml down -v
rm .env
```

**Step 6: Commit any fixes**

```bash
git commit -m "fix: phase 4 smoke test fixes"
```

---

## Summary

Phase 4 delivers (8 tasks):
- Multi-stage production Dockerfile for Spring Boot backend (non-root user, healthcheck)
- Multi-stage production Dockerfile for React frontend (Nginx)
- Nginx configuration with security headers, rate limiting, API proxy, actuator blocking, SPA routing
- Production Docker Compose with health checks, restart policies, network segmentation, and resource limits
- Environment configuration (.env.example with secrets documented)
- Database backup script with tiered retention (7 daily + 4 weekly + 3 monthly)
- macOS launchd scheduler for automated backups
- Local smoke test

**Deployment:** `cp .env.example .env`, edit passwords, `docker compose -f docker-compose.prod.yml up -d`.

---

## Changelog

### v2.0 (2026-03-16) — Post critical implementation review

Addresses all issues from `2026-03-01-phase4-deployment-implementation-critical-review-1.md`.

**Critical fixes (would cause build/runtime failures):**
- **Task 1:** Added `jar { enabled = false }` in `build.gradle` to prevent multi-JAR build conflicts with `COPY *.jar`
- **Task 1:** Added `-XX:MaxRAMPercentage=75.0` JVM flag to prevent OOM kills within 512M container limit
- **Task 3:** Removed invalid CSP source `/uploads/` from `img-src` (`'self'` already covers same-origin paths)
- **Task 3:** Added `client_max_body_size 10m` to support image uploads > 1MB
- **Task 4:** Fixed Redis config — replaced `redis.conf` file (which cannot do env var substitution) with command-line `--requirepass` and `REDISCLI_AUTH` for healthcheck

**Robustness fixes:**
- **Task 1:** Improved Gradle dependency caching layer: `./gradlew bootJar --no-daemon -x test || true`
- **Task 1:** Expanded `.dockerignore` with `Dockerfile*`, `docker-compose*`, `scripts/`, `nginx/`
- **Task 2:** Added `HEALTHCHECK` to frontend Dockerfile for consistency
- **Task 3:** Removed misleading server-level `include security-headers.conf` (Nginx does not inherit `add_header` into child blocks); added warning comment
- **Task 3:** Added `gzip_vary on` and additional gzip types (`application/xml`, `image/svg+xml`)
- **Task 3:** Fixed static asset caching: replaced redundant `expires 1y` + `Cache-Control` with single `add_header Cache-Control "public, max-age=31536000, immutable"`
- **Task 6:** Changed `set -uo pipefail` to `set -euo pipefail` so non-pipeline failures are caught
- **Task 6:** Single `date` capture to prevent race across midnight
- **Task 6:** Replaced empty-file check with 1KB minimum size check (empty gzip is ~20 bytes, not zero)
- **Task 7:** Added `EnvironmentVariables` with `PATH` including `/opt/homebrew/bin` so `docker` is found by launchd

### v1.0 (2026-03-01) — Initial plan

---

## Appendix: Future VPS Deployment (Deferred)

When ready to deploy to a VPS, the following additions are needed:

1. **HTTPS/TLS:** Add SSL server block to Nginx config, install Certbot, add `ssl_protocols TLSv1.2 TLSv1.3` and cipher hardening, add HSTS header, add HTTP→HTTPS redirect block. Use `envsubst` for `DOMAIN_NAME` templating.
2. **Firewall:** UFW configuration (deny incoming, allow 22/80/443).
3. **SSH hardening:** Disable password auth, disable root login.
4. **fail2ban:** Install and configure for SSH and Nginx.
5. **VPS setup script:** Combine the above into an automated provisioning script.
6. **Certbot renewal cron job.**
7. **Disk usage monitoring with Slack alerts.**
8. **Backup verification script** (restore to temporary container, check table count).
9. **Unattended security updates** (`unattended-upgrades`).
10. **DOMAIN_NAME and EMAIL_* env vars** in `.env.example`.
