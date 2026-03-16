# Critical Implementation Review: Phase 4 Deployment Plan

**Reviewed:** `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`
**Reviewer posture:** Senior Staff Engineer — strict code review
**Date:** 2026-03-16

---

## 1. Overall Assessment

The plan is well-structured with clear task decomposition, health checks, and a sensible single-VPS Docker Compose architecture. Strengths include multi-stage Docker builds, actuator endpoint blocking, gzip compression, and a tiered backup retention scheme.

However, there are several **critical correctness bugs, security gaps, and robustness issues** that would cause failures in production:

- Nginx config uses `${DOMAIN_NAME}` variable substitution but has no templating mechanism to resolve it.
- The backup script has a dead-code bug where the error check never fires.
- Several security hardening gaps (running containers as root, missing rate limiting, missing SSL hardening, piping remote scripts to shell).
- No logging, retry, or circuit-breaker strategy for any critical dependency.

---

## 2. Critical Issues

### 2.1 Nginx `${DOMAIN_NAME}` will not resolve (Task 3 — Correctness Bug)

**Description:** The `default.conf` uses `${DOMAIN_NAME}` as if it were a shell variable or Docker Compose interpolation. Nginx does not natively perform environment variable substitution in config files. The literal string `${DOMAIN_NAME}` will appear in the `server_name` and `ssl_certificate` directives, causing Nginx to fail to start or serve the wrong vhost.

**Impact:** Complete deployment failure — Nginx won't start or won't match any requests.

**Fix:** Use `envsubst` at container startup. In the Dockerfile or Compose entrypoint, add:
```bash
entrypoint: ["/bin/sh", "-c", "envsubst '$$DOMAIN_NAME' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'"]
```
And rename `default.conf` to `default.conf.template`. Pass `DOMAIN_NAME` as an environment variable to the nginx service in Compose.

---

### 2.2 Backup script error check is dead code (Task 6 — Correctness Bug)

**Description:** The script uses `set -euo pipefail` (line 2), which means any non-zero exit code from `pg_dump | gzip` will immediately terminate the script. The `if [ $? -ne 0 ]` block on line 363 will **never execute** because `set -e` kills the script first.

**Impact:** Backup failures will exit silently with no Slack notification. The entire alerting mechanism is non-functional.

**Fix:** Either:
- Remove `set -e` and handle errors manually throughout, or
- Wrap the `pg_dump` pipeline in a function/subshell with `|| true` and capture the exit code explicitly:
  ```bash
  if ! docker compose ... pg_dump ... | gzip > "$BACKUP_FILE"; then
      # send Slack alert
      exit 1
  fi
  ```

---

### 2.3 Containers run as root (Tasks 1 & 2 — Security)

**Description:** Neither Dockerfile creates a non-root user. The Spring Boot JAR runs as root inside the container, and Nginx runs its workers as root (the `user nginx;` directive handles workers but the master process is root, and the backend has no user directive at all).

**Impact:** If the application is compromised (e.g., RCE via deserialization), the attacker has root inside the container, making container escape significantly easier.

**Fix:** In `Dockerfile.backend`, add:
```dockerfile
RUN addgroup --system app && adduser --system --ingroup app app
USER app
```
Place this before `ENTRYPOINT`.

---

### 2.4 No SSL/TLS hardening (Task 3 — Security)

**Description:** The Nginx SSL configuration uses default protocol and cipher settings. There is no `ssl_protocols`, `ssl_ciphers`, `ssl_prefer_server_ciphers`, `ssl_session_cache`, or `ssl_session_timeout` directive. Default Nginx settings may allow TLS 1.0/1.1 and weak ciphers.

**Impact:** Vulnerable to downgrade attacks; will fail security audits and receive a poor SSL Labs score.

**Fix:** Add to the HTTPS server block:
```nginx
ssl_protocols TLSv1.2 TLSv1.3;
ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
ssl_prefer_server_ciphers off;
ssl_session_cache shared:SSL:10m;
ssl_session_timeout 1d;
ssl_stapling on;
ssl_stapling_verify on;
```

---

### 2.5 No rate limiting on API or auth endpoints (Task 3 — Security)

**Description:** There is no `limit_req_zone` or `limit_req` directive anywhere. Authentication endpoints (`/api/v1/auth/*`) are completely unprotected from brute-force attacks.

**Impact:** Credential-stuffing and brute-force attacks are trivially easy. Combined with the absence of fail2ban integration for the application layer, this is a significant risk.

**Fix:** Add to `nginx.conf` http block:
```nginx
limit_req_zone $binary_remote_addr zone=auth:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=api:10m rate=30r/s;
```
Apply `limit_req zone=auth burst=3 nodelay;` to a dedicated `/api/v1/auth/` location block, and `limit_req zone=api burst=50 nodelay;` to the general `/api/` block.

---

### 2.6 `Content-Security-Policy` is too restrictive and incomplete (Task 3 — Correctness)

**Description:** The CSP is `script-src 'self'` only. This is missing `default-src`, `style-src`, `img-src`, `connect-src`, and `font-src`. A React SPA likely needs `style-src 'self' 'unsafe-inline'` (for styled-components or inline styles) and `connect-src 'self'` (for API calls). As written, the browser will block stylesheets, images from uploads, and API fetch requests.

**Impact:** The frontend will be partially or fully broken in production.

**Fix:** Replace with a complete, minimal CSP:
```nginx
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' /uploads/; connect-src 'self'; font-src 'self'; frame-ancestors 'self'" always;
```

---

### 2.7 Security headers inside nested `location` are silently dropped (Task 3 — Correctness Bug)

**Description:** In the `/uploads/` location block, `add_header` directives are used. Nginx's `add_header` behavior means that when `add_header` appears in a child block, **all** parent-level `add_header` directives (X-Frame-Options, HSTS, CSP, etc.) are **no longer inherited**. The same applies to the nested static asset `location` block.

**Impact:** Uploaded files and static assets will be served **without** security headers (no HSTS, no X-Frame-Options, no CSP). This creates XSS and clickjacking vectors on uploaded content.

**Fix:** Either:
- Repeat all security headers in every location block that uses `add_header`, or
- Use the `more_set_headers` directive from the `headers-more` module which doesn't have this inheritance issue, or
- Move security headers into a shared snippet file and `include` it in every location block.

---

### 2.8 VPS setup pipes remote script to shell (Task 9 — Security)

**Description:** `curl -fsSL https://get.docker.com | sh` downloads and executes an arbitrary remote script as root. This is a supply-chain risk.

**Impact:** If `get.docker.com` is compromised or intercepted (unlikely but high-impact), the VPS is fully compromised before any hardening takes place.

**Fix:** Use the official Docker apt repository installation method instead:
```bash
apt install -y ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
# ... add repo and apt install docker-ce
```
This verifies GPG signatures and uses the system package manager.

---

### 2.9 Redis password exposed in `docker compose ps` and process list (Task 4 — Security)

**Description:** The Redis `command: redis-server --requirepass ${REDIS_PASSWORD}` puts the password on the process command line, visible via `docker inspect`, `docker compose ps`, and `/proc` inside the container. The same is true for the healthcheck `redis-cli -a ${REDIS_PASSWORD} ping`.

**Impact:** Any user with Docker socket access or process listing can read the Redis password in cleartext.

**Fix:** Mount a Redis config file instead:
```yaml
volumes:
  - ./redis/redis.conf:/usr/local/etc/redis/redis.conf:ro
command: redis-server /usr/local/etc/redis/redis.conf
```
With `requirepass` set in the config file. For the healthcheck, use `REDISCLI_AUTH` environment variable instead of `-a`.

---

### 2.10 No resource limits on containers (Task 4 — Reliability)

**Description:** No `mem_limit`, `cpus`, or `deploy.resources` are set on any service. A memory leak in the backend or a runaway query in PostgreSQL can consume all VPS resources, taking down all services.

**Impact:** Single-service failure cascades to full outage.

**Fix:** Add resource limits to each service:
```yaml
deploy:
  resources:
    limits:
      memory: 512M  # backend
      cpus: '1.0'
```

---

### 2.11 PostgreSQL has no network isolation (Task 4 — Security / Zero Trust)

**Description:** All services are on the default Docker Compose network. PostgreSQL and Redis are accessible from the Nginx container, which only needs to reach the backend. This violates least-privilege network access.

**Impact:** If the Nginx container is compromised (e.g., via a vulnerability in Nginx itself), the attacker has direct network access to the database.

**Fix:** Define two networks:
```yaml
networks:
  frontend:
  backend:
```
Put Nginx and backend on `frontend`, backend and postgres/redis on `backend`. This ensures Nginx cannot directly reach the database.

---

## 3. Minor Issues & Improvements

- **Task 1, line 41:** The `HEALTHCHECK` uses `curl` but the `eclipse-temurin:21-jre` image does not include curl. Either install curl in the Dockerfile or switch to `wget -q --spider` which is available, or better yet, use Spring Boot's built-in healthcheck via a small Java utility.

- **Task 2:** The frontend Dockerfile has no `.dockerignore`. Without it, the entire repo (including `.git`, `backend/`, `node_modules/`) is sent as build context, making builds slow.

- **Task 4:** The `backend` service sets `SPRING_PROFILES_ACTIVE: prod` in both the Compose environment AND the Dockerfile ENTRYPOINT (`--spring.profiles.active=prod`). This is redundant and potentially confusing if they diverge.

- **Task 6, line 395:** `ls -t /backups/daily/*.sql.gz | head -1` in `backup-verify.sh` will fail with a glob error if the directory is empty. Use `find` with `-maxdepth 1` instead.

- **Task 6:** The backup verification script uses `sleep 10` to wait for PostgreSQL to start. This is brittle. Use a retry loop with `pg_isready`.

- **Task 6:** Backups are not encrypted. If the VPS disk or backup storage is compromised, all database contents are exposed in cleartext. Consider piping through `gpg --symmetric` or `age`.

- **Task 7:** The disk check script has no `set -euo pipefail` and silently does nothing if `SLACK_WEBHOOK` is empty. Consider logging to stdout/stderr as well so cron captures it.

- **Task 9:** The VPS setup script does not disable root SSH login (`PermitRootLogin no`), only disables password auth. Root login via SSH key is still possible.

- **Task 9:** No automatic security updates configured (e.g., `unattended-upgrades`).

- **Task 10:** The smoke test uses `-k` (insecure) for HTTPS with `localhost`, but the Nginx config will try to load Let's Encrypt certs which won't exist locally. The smoke test will fail. Needs a self-signed cert generation step or an HTTP-only local testing mode.

- **Task 5:** No `JWT_SECRET` or `SESSION_SECRET` in `.env.example`. If the Spring Boot app uses JWT or session signing, this is a missing secret.

- **Task 4:** No log rotation or log driver configuration. Container logs will grow unbounded on the VPS.

---

## 4. Questions for Clarification

1. **Uploads volume:** The uploads volume is shared between backend (read/write) and Nginx (read-only). Is there any content validation or virus scanning on uploaded files? The plan doesn't mention it, and serving user-uploaded files directly via Nginx is a common attack vector (e.g., uploading an HTML file with embedded JS).

2. **Certbot renewal:** The plan mentions initial cert acquisition (`certbot certonly --standalone`) but doesn't include a cron job or systemd timer for automatic renewal. Certs expire every 90 days — is renewal handled elsewhere?

3. **Zero-downtime deployments:** The plan doesn't address how to deploy updates. Will deployments require downtime (`docker compose down && up`)? Consider a rolling-restart strategy or a blue-green approach for the backend service.

4. **Database migrations:** How are schema migrations (Flyway/Liquibase) run during deployment? If the backend runs migrations on startup, what happens if two instances start simultaneously?

5. **Monitoring beyond disk:** The plan includes disk monitoring but no application-level monitoring (error rates, response times, JVM metrics). Is this intentionally deferred?

---

## 5. Final Recommendation

**Major revisions needed.**

The plan has a solid skeleton but contains two correctness bugs that will cause immediate deployment failure (Nginx variable substitution, broken backup error alerting), multiple security gaps that violate zero-trust principles (root containers, no network segmentation, no rate limiting, no SSL hardening, exposed Redis password), and a CSP that will break the frontend. The smoke test as written will also fail due to missing local SSL certs.

**Key changes required before implementation:**

1. Fix Nginx `${DOMAIN_NAME}` resolution via `envsubst` (blocker)
2. Fix backup script error handling (dead code)
3. Fix CSP to include all required directives (blocker — breaks frontend)
4. Fix `add_header` inheritance in nested location blocks (security regression)
5. Add non-root users to both Dockerfiles
6. Add SSL/TLS hardening directives
7. Add rate limiting on auth endpoints
8. Add Docker network segmentation (frontend/backend)
9. Add container resource limits
10. Fix smoke test to work without Let's Encrypt certs
