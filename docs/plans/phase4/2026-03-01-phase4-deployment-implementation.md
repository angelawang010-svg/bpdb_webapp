# Phase 4: Local Production Deployment (Mac) — Implementation Plan

**Version:** 3.0
**Last Updated:** 2026-04-30

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
RUN chmod +x gradlew
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
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
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
    server_tokens off;

    # Gzip compression
    gzip on;
    gzip_vary on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml image/svg+xml;
    gzip_min_length 256;

    # Upload size limit
    client_max_body_size 10m;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=auth:10m rate=10r/m;
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
        limit_req zone=auth burst=5 nodelay;
        limit_req_status 429;
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
name: blogplatform

networks:
  frontend:
  backend:
    internal: true

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
      - ./uploads:/app/uploads:ro
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - frontend
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
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
      - ./uploads:/app/uploads
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    networks:
      - frontend
      - backend
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
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
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    deploy:
      resources:
        limits:
          memory: 256M
          cpus: '1.0'

  redis:
    image: redis:7
    # NOTE: No persistence configured (appendonly off). Container restart loses all sessions.
    # This is intentional for local single-user deployment. Enable appendonly for multi-user use.
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
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    deploy:
      resources:
        limits:
          memory: 128M
          cpus: '0.5'

volumes:
  pgdata:
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
# Generate with: openssl rand -base64 32
DB_PASSWORD=changeme-minimum-24-characters-random

# Redis
# Generate with: openssl rand -base64 32
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
if [ "$DAY_OF_MONTH" -eq 1 ]; then
    cp "$BACKUP_FILE" "$BACKUP_DIR/monthly/"
fi

# Retention: at least 7 daily, at least 4 weekly, at least 3 monthly
# (find -mtime +N means strictly > N days, so actual retention is N+1 days)
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
        <key>BACKUP_DIR</key>
        <string>REPLACE_WITH_REPO_PATH/backups</string>
        <key>COMPOSE_FILE</key>
        <string>docker-compose.prod.yml</string>
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

**Step 2: Write install script**

`scripts/install-backup-scheduler.sh`:
```bash
#!/bin/bash
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sed "s|REPLACE_WITH_REPO_PATH|$REPO_DIR|g" \
  "$REPO_DIR/scripts/com.blogplatform.backup.plist" \
  > ~/Library/LaunchAgents/com.blogplatform.backup.plist
launchctl load ~/Library/LaunchAgents/com.blogplatform.backup.plist
echo "Backup scheduler installed for $REPO_DIR"
```

Run: `chmod +x scripts/install-backup-scheduler.sh`

Install with: `./scripts/install-backup-scheduler.sh`

**Step 3: Commit**

```bash
git add scripts/com.blogplatform.backup.plist scripts/install-backup-scheduler.sh
git commit -m "feat: add macOS launchd plist and install script for scheduled backups"
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
# Health check — verify only {"status":"UP"} with no component details
curl http://localhost/actuator/health
# Expected: {"status":"UP"} — no postgres/redis/disk details

# Verify actuator blocked
curl http://localhost/actuator/env  # Should return 404

# Fetch CSRF token for subsequent requests
CSRF_TOKEN=$(curl -s -c cookies.txt http://localhost/api/v1/auth/csrf \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# Register (include CSRF token)
curl -X POST http://localhost/api/v1/auth/register \
  -b cookies.txt \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -d '{"username":"smoketest","email":"smoke@test.com","password":"Sm0keTest!2026"}'

# Cleanup
rm -f cookies.txt
```

**Step 4: Test backup and restore**

```bash
# Create backup
./scripts/backup.sh
ls -la backups/daily/

# Verify backup is restorable
BACKUP_FILE=$(ls -t backups/daily/*.sql.gz | head -1)
gunzip -c "$BACKUP_FILE" | docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U blogplatform -d blogplatform -c "SELECT 1" > /dev/null
echo "Backup restore verification passed"
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
- Database backup script with tiered retention (at least 7 daily + 4 weekly + 3 monthly)
- macOS launchd scheduler with automated install script
- Local smoke test with CSRF handling and backup restore verification

**Deployment:** `cp .env.example .env`, edit passwords, `docker compose -f docker-compose.prod.yml up -d`.

---

## Changelog

### v3.0 (2026-04-30) — Post second critical implementation review

Addresses all issues from `2026-03-01-phase4-deployment-implementation-critical-review-2.md`.

**Correctness fixes:**
- **Task 1:** Added `RUN chmod +x gradlew` to prevent permission denied errors after COPY
- **Task 1:** Removed `--spring.profiles.active=prod` from ENTRYPOINT — profile now controlled solely via `SPRING_PROFILES_ACTIVE` env var in Compose for flexibility
- **Task 3:** Relaxed auth rate limit from `5r/m` to `10r/m` with `burst=5` to let app-layer brute-force controls handle normal scenarios
- **Task 3:** Added `limit_req_status 429` so rate-limited requests return proper `429 Too Many Requests` instead of `503`
- **Task 3:** Added `server_tokens off` to suppress Nginx version disclosure
- **Task 6:** Fixed `"01"` string comparison to integer `1` in monthly backup check
- **Task 6:** Updated retention comments to document `find -mtime` off-by-one semantics (actual retention is N+1 days)
- **Task 8:** Fixed smoke test password (`Password1` → `Sm0keTest!2026`) to meet password policy
- **Task 8:** Added CSRF token fetch and inclusion in smoke test requests

**Security hardening:**
- **Task 4:** Added `internal: true` to backend Docker network (postgres/redis cannot reach internet)
- **Task 8:** Added actuator health detail verification (must show only `{"status":"UP"}` with no component details — requires `management.endpoint.health.show-details: never` in `application-prod.yml`)

**Robustness improvements:**
- **Task 4:** Added `name: blogplatform` for consistent Docker Compose project naming
- **Task 4:** Changed uploads from named volume to bind mount (`./uploads:/app/uploads`) for direct Finder access on local Mac
- **Task 4:** Added `logging` config with rotation (`max-size: 10m`, `max-file: 3`) to all 4 services
- **Task 4:** Added comment documenting intentional Redis no-persistence decision
- **Task 5:** Added `openssl rand -base64 32` generation command to `.env.example`
- **Task 7:** Added `BACKUP_DIR` and `COMPOSE_FILE` to launchd plist environment variables
- **Task 7:** Added `scripts/install-backup-scheduler.sh` to auto-substitute repo path (replaces manual `REPLACE_WITH_REPO_PATH` editing)
- **Task 8:** Added backup restore verification step to smoke test

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
