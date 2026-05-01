# Security Audit: Phase 4 Deployment Implementation Plan

**Audited file:** `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md` (v3.0)
**Audit date:** 2026-04-30
**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Scope:** Infrastructure-as-Code review — Dockerfiles, Docker Compose, Nginx configuration, backup scripts, launchd scheduling. White-box analysis of the deployment plan document. Application-layer code (Spring Boot, React) is out of scope.

---

## Finding #1: Docker Compose Exposes Port on All Network Interfaces

Vulnerability: Network Exposure — A05 (Security Misconfiguration)
Severity: **High**
Confidence: Confirmed
Attack Complexity: Low

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 298–299
- Related: Lines 10–11 (stated goal of "local production use")

Risk & Exploit Path:
`ports: "80:80"` binds to `0.0.0.0:80`, exposing the application to every device on the local network. On a Mac connected to WiFi, any peer on the same network can reach the application — authentication endpoints, API, uploaded files, and the health check. Combined with the absence of HTTPS (Finding #2), this creates a complete credential interception path. macOS firewall is disabled by default on many installations, providing no fallback protection. This directly contradicts the plan's stated goal of "local production use."

Evidence / Trace:
```yaml
nginx:
    ports:
      - "80:80"    # ← binds 0.0.0.0:80, all interfaces
```

Remediation:
- Primary fix — bind to localhost only:
  ```yaml
  ports:
    - "127.0.0.1:80:80"
  ```
- Defense-in-depth: Document that macOS firewall should be enabled (`System Settings > Network > Firewall`).

---

## Finding #2: All Traffic Transmitted in Cleartext (No HTTPS)

Vulnerability: Missing Encryption in Transit — A02 (Cryptographic Failures)
Severity: **Medium**
Confidence: Confirmed
Attack Complexity: Low

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 10–11, 204

Risk & Exploit Path:
All traffic — including login credentials, session cookies, and CSRF tokens — travels over plaintext HTTP. The plan acknowledges this is intentional for local-only access. However, if Finding #1 is not remediated, credentials are interceptable by any device on the same network via passive sniffing or ARP spoofing. Session cookies will lack the `Secure` flag, making them stealable. The `HSTS` header is also absent from the security headers snippet (line 150–154), though this is expected without HTTPS.

Remediation:
- Primary fix: Remediate Finding #1 (bind to 127.0.0.1) — this eliminates network exposure without requiring HTTPS.
- If the deployment ever serves non-localhost traffic, HTTPS becomes mandatory (the plan's Appendix correctly identifies this for future VPS deployment).

---

## Finding #3: Uploads Served Without File-Type Restriction — Stored XSS Risk

Vulnerability: Unrestricted Content Serving / Stored XSS — A01 (Broken Access Control) + A03 (Injection)
Severity: **High**
Confidence: High
Attack Complexity: Low

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 245–249 (Nginx uploads location)
- Related: Lines 77, 298–299 (uploads volume mount)

Risk & Exploit Path:
The Nginx `location /uploads/` block serves any file from `/app/uploads/` with no restriction on file type. If the backend allows uploading `.html`, `.svg`, or other active-content files (not verified — backend is out of scope but this plan doesn't enforce any restriction at the Nginx layer), an attacker can upload a file containing JavaScript. Since the file is served from the same origin (`'self'`), it passes the Content Security Policy. The `Content-Disposition: inline` header actively instructs the browser to render the content. A malicious SVG with embedded `<script>` tags would execute in the application's origin context, enabling session hijacking.

Additionally, the `alias` directive with a user-influenced path segment can be susceptible to path traversal if filenames contain `../` — though this depends on backend filename sanitization (out of scope).

Evidence / Trace:
```nginx
location /uploads/ {
    alias /app/uploads/;                              # ← serves any file type
    include /etc/nginx/snippets/security-headers.conf;
    add_header Content-Disposition "inline" always;    # ← renders in browser
    expires 30d;
}
```

Remediation:
- Primary fix — restrict to image file types only:
  ```nginx
  location ~* ^/uploads/.+\.(jpg|jpeg|png|gif|webp)$ {
      root /app;
      include /etc/nginx/snippets/security-headers.conf;
      add_header Cache-Control "public, max-age=2592000" always;
  }
  location /uploads/ {
      return 404;
  }
  ```
- Architectural improvement: Backend must validate upload MIME types and use UUID-based filenames (strip user-supplied names entirely).
- Defense-in-depth: Add a restrictive CSP to the uploads location: `add_header Content-Security-Policy "default-src 'none'" always;`

---

## Finding #4: Backup Files Written With No Permission Restrictions

Vulnerability: Insecure File Permissions — A01 (Broken Access Control)
Severity: **Low**
Confidence: High
Attack Complexity: Medium

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 482–515 (backup script)

Risk & Exploit Path:
The backup script creates `*.sql.gz` files containing the full database dump (including user password hashes, email addresses, and all post content). These files are created with the default umask (typically `0022`), making them world-readable. On a shared Mac or if backups are accidentally placed on a network-accessible volume, any local user can read them.

Evidence / Trace:
```bash
mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
# No chmod on directory or files
BACKUP_FILE="$BACKUP_DIR/daily/blogplatform_${TIMESTAMP}.sql.gz"
docker compose ... pg_dump ... | gzip > "$BACKUP_FILE"  # ← created with default umask
```

Remediation:
- Primary fix — restrict permissions:
  ```bash
  mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
  chmod 700 "$BACKUP_DIR" "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
  ```
  And after backup creation:
  ```bash
  chmod 600 "$BACKUP_FILE"
  ```

---

## Finding #5: Smoke Test Uses Hardcoded Weak Passwords & Same Password for Both Services

Vulnerability: Weak/Shared Credentials — A07 (Identification and Authentication Failures)
Severity: **Low**
Confidence: Confirmed
Attack Complexity: Low

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 609–612

Risk & Exploit Path:
The smoke test creates `.env` with `localtest12345678901234` for both DB and Redis passwords. Using identical passwords for different services means compromise of one service immediately compromises the other. While the cleanup step removes `.env`, if a developer leaves the stack running after testing, the services are protected by predictable credentials. In a local-only context with internal networking, exploitation requires local access.

Evidence / Trace:
```bash
cat > .env << 'EOF'
DB_PASSWORD=localtest12345678901234      # ← identical, predictable
REDIS_PASSWORD=localtest12345678901234   # ← identical, predictable
EOF
```

Remediation:
- Primary fix — generate random passwords:
  ```bash
  cat > .env << EOF
  DB_PASSWORD=$(openssl rand -base64 32)
  REDIS_PASSWORD=$(openssl rand -base64 32)
  EOF
  ```

---

## Finding #6: Backend Dockerfile Skips All Tests

Vulnerability: Quality/Security Gate Bypass — A05 (Security Misconfiguration)
Severity: **Low**
Confidence: Confirmed
Attack Complexity: N/A

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 67–68

Risk & Exploit Path:
Both Gradle invocations in the Dockerfile use `-x test`, skipping all tests. If the Docker build is the only pre-deployment step, security-related tests (authorization, input validation, CSRF protection) are never verified before deployment. This is a defense-in-depth gap — not directly exploitable, but removes a safety net.

Evidence / Trace:
```dockerfile
RUN ./gradlew bootJar --no-daemon -x test || true   # ← dependency cache layer
RUN ./gradlew bootJar --no-daemon -x test            # ← production build, no tests
```

Remediation:
- Primary fix: Document that `./gradlew test` must be run before `docker compose build`, or add a test stage to the Dockerfile (separate from the build artifact stage).
- For local dev, this is reasonable to keep builds fast — but should be explicit policy.

---

## Finding #7: Backup Restore Verification in Smoke Test Is Incomplete

Vulnerability: Logic Flaw — Best Practice
Severity: **Low**
Confidence: Confirmed
Attack Complexity: N/A

Location:
- File: `docs/plans/phase4/2026-03-01-phase4-deployment-implementation.md`, Lines 657–659

Risk & Exploit Path:
The backup restore verification pipes the gunzipped backup into `psql -c "SELECT 1"`, which only tests that `psql` can connect — it does not actually restore the backup or verify its contents. The `SELECT 1` runs independently of the piped input.

Evidence / Trace:
```bash
gunzip -c "$BACKUP_FILE" | docker compose ... exec -T postgres \
  psql -U blogplatform -d blogplatform -c "SELECT 1" > /dev/null
# ← psql -c ignores stdin; the backup data is discarded
```

Remediation:
- Primary fix — actually verify backup content:
  ```bash
  gunzip -c "$BACKUP_FILE" | head -5  # Verify SQL statements present
  # Or restore to a temp database:
  docker compose ... exec -T postgres psql -U blogplatform -d postgres \
    -c "CREATE DATABASE restore_test"
  gunzip -c "$BACKUP_FILE" | docker compose ... exec -T postgres \
    psql -U blogplatform -d restore_test
  docker compose ... exec -T postgres psql -U blogplatform -d postgres \
    -c "DROP DATABASE restore_test"
  ```

---

## 1. Executive Summary

This Phase 4 deployment plan demonstrates solid security awareness overall — network segmentation between frontend and backend Docker networks, non-root container users, rate limiting on auth endpoints, actuator endpoint blocking, security headers, and a well-structured backup rotation. The plan has been through two rounds of critical review, and it shows.

However, two high-severity issues require attention before deployment. The most concerning is that the Docker Compose configuration binds port 80 to all network interfaces (`0.0.0.0`), directly contradicting the "local production" intent and exposing the entire unencrypted application to the local network. This is a one-line fix (`127.0.0.1:80:80`). The second concern is that Nginx serves uploaded files with no file-type restriction, creating a stored XSS vector if the backend doesn't perfectly validate uploads — defense-in-depth requires the Nginx layer to independently restrict served content types.

With the localhost binding fix applied, the deployment is appropriate for its stated purpose of single-user local Mac usage. The remaining findings are low-severity hardening improvements.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Port exposed on all interfaces | A05 | High | Confirmed | 1 | BLOCK |
| 2 | No HTTPS (cleartext traffic) | A02 | Medium | Confirmed | 1 | WARN |
| 3 | Uploads served without type restriction | A01/A03 | High | High | 1 | BLOCK |
| 4 | Backup files world-readable | A01 | Low | High | 1 | WARN |
| 5 | Smoke test hardcoded weak passwords | A07 | Low | Confirmed | 1 | WARN |
| 6 | Dockerfile skips tests | A05 | Low | Confirmed | 1 | INFO |
| 7 | Backup restore verification is no-op | Best Practice | Low | Confirmed | 1 | INFO |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 2 | −40 |
| Medium | 1 | −8 |
| Low | 4 | −8 |

**Final SQS:** 44/100
**Hard gates triggered:** No (no Critical findings, no hardcoded production secrets, no known CVEs)
**Posture:** Unacceptable — block deployment, urgent remediation required

**Note:** The score is heavily impacted by the two High findings, both of which are straightforward one-section fixes. After remediation of Findings #1 and #3, the score would be **84/100 (Acceptable)**.

## 4. Positive Security Observations

1. **Network segmentation:** The `backend` Docker network is correctly marked `internal: true`, preventing PostgreSQL and Redis from initiating outbound connections. This is excellent practice.
2. **Non-root container execution:** The backend Dockerfile creates a dedicated `app` user and switches to it with `USER app`. The container runs unprivileged.
3. **Rate limiting on auth endpoints:** Nginx applies a separate, stricter rate-limit zone (`10r/m burst=5`) for `/api/v1/auth/`, protecting against brute-force attacks at the infrastructure layer.
4. **Actuator endpoint blocking:** Only `/actuator/health` is exposed; all other actuator endpoints return 404. The plan also verifies that health details are hidden (no component info leakage).
5. **Resource limits and log rotation:** All containers have CPU/memory limits and JSON-file log rotation, preventing resource exhaustion and unbounded disk growth.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Title | Why Prioritized | Effort | Owner |
|----------|---------|-------|-----------------|--------|-------|
| 1 | #1 | Port exposed on all interfaces | Highest severity × trivial exploitation × eliminates Finding #2 risk | Quick Win | DevOps |
| 2 | #3 | Uploads served without type restriction | Stored XSS from same origin bypasses CSP; needs Nginx-layer restriction regardless of backend validation | Quick Win | DevOps |
| 3 | #4 | Backup files world-readable | Database dumps contain all user data; one `chmod` line | Quick Win | DevOps |
| 4 | #7 | Backup restore verification is no-op | Current test gives false confidence; actual restore test is straightforward | Moderate | DevOps |
| 5 | #5 | Smoke test hardcoded passwords | Low risk but easy to generate random passwords instead | Quick Win | DevOps |
