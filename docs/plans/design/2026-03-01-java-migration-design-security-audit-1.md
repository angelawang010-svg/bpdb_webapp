# Security Audit Report — BlogPlatformDB Java Migration Design v6.0

**Audit Date:** 2026-03-01
**Audited Document:** `docs/plans/2026-02-27-java-migration-design.md` (v6.0)
**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Scope:** Design-level white-box security review of the architecture, data flows, authentication, authorization, deployment, and business logic described in the design document. This is a pre-implementation review — no source code exists yet. Findings are based on what the design specifies (or fails to specify).

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points
- **Public REST API** (`/api/v1/*`) — 30+ endpoints spanning auth, CRUD, admin, file upload, notifications
- **File upload** — image upload endpoint accepting multipart data
- **Nginx reverse proxy** — serves static files, proxies API, serves uploaded images at `/uploads/*`
- **Redis** — session store (internal Docker network)
- **PostgreSQL** — database (internal Docker network)
- **SSH** — server management (port 22)
- **Cron jobs** — backup, cleanup, monitoring scripts

### Trust Boundaries
1. Browser → Nginx (TLS termination)
2. Nginx → Spring Boot (internal HTTP, trusted network)
3. Spring Boot → PostgreSQL (internal, trusted)
4. Spring Boot → Redis (internal, trusted)
5. User input → Bean Validation → Service → Repository → DB
6. Markdown content → stored raw → rendered client-side via `react-markdown` + `rehype-sanitize`

### Sensitive Data
- Passwords (BCrypt hashed)
- Session tokens (Redis)
- CSRF tokens (cookie)
- Password reset tokens, email verification tokens
- User PII (email, names, profile data)
- Uploaded images

### Built-in Protections Noted
- Spring Security filter chain (session, CSRF, authz)
- BCrypt password hashing (work factor 12)
- Redis-backed sessions with fixation protection
- Bucket4j rate limiting (tiered)
- `rehype-sanitize` for XSS on Markdown rendering
- CSP header (`script-src 'self'`)
- UFW firewall (3 ports only)
- SSH key-only auth + fail2ban

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: IDOR on User Profile Update and Resource Access

**Vulnerability:** Insecure Direct Object Reference (IDOR) — OWASP A01 (Broken Access Control)
**Severity:** High
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Design Section 5 (API Endpoints), Lines for `PUT /api/v1/users/{id}`, `GET /api/v1/users/{id}/saved-posts`, `POST /api/v1/users/{id}/upgrade-vip`
- Related: `PUT /api/v1/notifications/{id}/read`, `DELETE /api/v1/comments/{id}`, `DELETE /api/v1/images/{id}`

**Risk & Exploit Path:**
The design specifies "Owner only" access for several endpoints that take a resource ID in the path (e.g., `PUT /api/v1/users/{id}`). However, the design does not specify *how* ownership verification is enforced. If the implementation simply trusts the `{id}` parameter without comparing it against the authenticated session's user ID, any authenticated user can modify another user's profile, read their saved posts, or mark their notifications as read.

The same risk applies to `DELETE /api/v1/comments/{id}` ("Owner, ADMIN") and `DELETE /api/v1/images/{id}` ("Owner, ADMIN") — the design does not describe how the system verifies that the authenticated user owns the comment or image.

**Evidence / Trace:**
```
PUT /api/v1/users/{id}  →  Access: "Owner only"  ← No mechanism specified
DELETE /api/v1/comments/{id}  →  Access: "Owner, ADMIN"  ← No mechanism specified
```

**Remediation:**
- Primary fix: Explicitly specify that every "Owner only" and "Owner, ADMIN" endpoint must compare the resource's `account_id` against `SecurityContextHolder.getContext().getAuthentication().getName()` (or equivalent principal extraction). Use `@PreAuthorize` with a custom security expression or a service-level check.
- Architectural improvement: Create a shared `OwnershipVerifier` utility or Spring Security `PermissionEvaluator` that all "owner" checks route through, preventing per-endpoint implementation inconsistencies.

---

### Finding #2: Missing MIME Type Validation Beyond Extension — Image Upload

**Vulnerability:** Unrestricted File Upload / Content-Type Spoofing — OWASP A01/A04
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- Design Section 6 (Image Upload Constraints)

**Risk & Exploit Path:**
The design specifies MIME type validation (JPEG/PNG/WebP) and filename sanitization (UUID-based). However, it does not mention validating the actual file content (magic bytes / file signature) as opposed to just the `Content-Type` header or file extension. An attacker could upload a polyglot file (e.g., a file with JPEG magic bytes but containing embedded HTML/JS) or simply set a spoofed `Content-Type` header. Since Nginx serves `/uploads/*` directly, the browser could interpret a mislabeled file as HTML and execute scripts in the application's origin.

**Evidence / Trace:**
```
Allowed MIME types: JPEG, PNG, WebP  →  "Validated in ImageService before saving"  ← Method not specified
/uploads/*  →  serves uploaded images  ← Served by Nginx directly, Content-Type inferred
```

**Remediation:**
- Primary fix: Validate file magic bytes (file signatures) in `ImageService`, not just the `Content-Type` header or extension. Use a library like Apache Tika for reliable content detection.
- Defense-in-depth: Configure Nginx to serve all `/uploads/*` files with `Content-Type: application/octet-stream` or the validated image type, and add `Content-Disposition: inline` only for confirmed image types. Add `X-Content-Type-Options: nosniff` (already mentioned for general security headers but must be confirmed on the `/uploads/*` location block specifically).

---

### Finding #3: Password Reset Token Insufficient Specification

**Vulnerability:** Weak Password Reset Flow — OWASP A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- Design Section 2 (auth package), Section 5 (Auth endpoints), Changelog v6.0

**Risk & Exploit Path:**
The design specifies "time-limited single-use tokens" for password reset but does not specify:
1. Token entropy/length — if tokens are short or sequential, they're brute-forceable despite rate limiting (rate limiting is per-IP, easily bypassed via distributed attack).
2. Token expiration duration — "time-limited" is unspecified. A 24-hour token is significantly weaker than a 15-minute token.
3. Token storage — are tokens stored hashed or in plaintext? If plaintext, a database breach leaks all unexpired reset tokens.
4. User enumeration — `POST /api/v1/auth/forgot-password` accepts an email. If the response differs based on whether the email exists, this enables user enumeration.

**Evidence / Trace:**
```
PasswordResetToken: token (unique), account_id FK, expires_at, used (boolean)
POST /api/v1/auth/forgot-password  →  "Accept email, send time-limited reset token"
```

**Remediation:**
- Primary fix: Specify: (1) tokens are cryptographically random, minimum 32 bytes (256 bits), URL-safe base64 encoded; (2) expiration of 15–30 minutes; (3) tokens stored as SHA-256 hashes in the database (compare hash on redemption); (4) `forgot-password` endpoint always returns the same response ("If an account exists, a reset email was sent") regardless of whether the email exists.
- Same requirements apply to `EmailVerificationToken`.

---

### Finding #4: No Account Lockout After Failed Login Attempts

**Vulnerability:** Brute Force / Credential Stuffing — OWASP A07
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Design Section 7 (Authentication & Security), Section 6 (Rate Limiting Strategy)

**Risk & Exploit Path:**
The design specifies rate limiting of 10 requests/minute on auth endpoints per IP address. However, there is no account-level lockout after repeated failed login attempts. An attacker using a botnet (many IPs) can attempt 10 passwords/minute/IP against a target account. With 100 IPs, that's 1,000 attempts/minute — enough to brute-force weak passwords quickly.

Rate limiting per IP is necessary but insufficient without per-account protection.

**Evidence / Trace:**
```
Auth endpoints: 10 requests/minute per IP address
No mention of: account lockout, progressive delays, CAPTCHA after N failures
```

**Remediation:**
- Primary fix: Add per-account failed login tracking. After 5 consecutive failed attempts, temporarily lock the account for 15 minutes (or require CAPTCHA). Reset the counter on successful login.
- Defense-in-depth: Log all failed login attempts with username and IP for anomaly detection.

---

### Finding #5: Redis Without Authentication

**Vulnerability:** Unauthenticated Access to Session Store — OWASP A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- Design Section 7 (Session Management), Section 10 (VPS Architecture)

**Risk & Exploit Path:**
The design describes Redis running as a Docker container on the internal Docker network. However, there is no mention of Redis requiring a password (`requirepass`). While Redis is not exposed to the internet (Docker internal network), a compromise of any container on the same Docker network (e.g., via an RCE in Spring Boot or an SSRF) would give the attacker unauthenticated access to Redis. From there, they can: (1) steal all active session data, (2) forge sessions to impersonate any user including admins, (3) flush all sessions causing a denial of service.

**Evidence / Trace:**
```
Redis 7 (container): :6379 (internal only)
RedisConfig.java: "Redis connection for Spring Session"
No mention of: requirepass, ACL, TLS
```

**Remediation:**
- Primary fix: Configure Redis with `requirepass` in the Docker Compose file. Store the password in the `.env` file and reference it in `application.yml` via `spring.data.redis.password`.
- Defense-in-depth: Consider Redis ACLs to restrict the session user to only `GET`/`SET`/`DEL` on session keys, not administrative commands like `FLUSHALL` or `CONFIG`.

---

### Finding #6: PostgreSQL Credentials Not Specified as Secured

**Vulnerability:** Potential Hardcoded/Weak Database Credentials — OWASP A02/A05
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** Low

**Location:**
- Design Section 10 (Deployment Process)

**Risk & Exploit Path:**
The deployment section mentions `cp .env.example .env` and editing it to "Set DB password, session secret, domain name." This is the correct pattern, but the design does not specify: (1) minimum password strength requirements for the database, (2) that `.env` must be in `.gitignore`, (3) that `.env.example` must not contain real credentials.

These are operational details, but their omission in a design document means they're easily missed during implementation.

**Evidence / Trace:**
```
# 4. Create .env file with production secrets
cp .env.example .env
nano .env  # Set DB password, session secret, domain name
```

**Remediation:**
- Primary fix: Add to the deployment section: (1) `.env` and `.env.example` must be in `.gitignore`; (2) `.env.example` contains placeholder values only; (3) database password must be randomly generated (minimum 24 characters); (4) document which secrets the `.env` file must contain (DB password, Redis password, session secret, email service API key).

---

### Finding #7: Actuator Health Endpoint Exposure

**Vulnerability:** Information Disclosure via Spring Boot Actuator — OWASP A05
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- Design Section 10 (Phase 4 deliverables, Monitoring)

**Risk & Exploit Path:**
The design mentions `/actuator/health` as a health check endpoint. Spring Boot Actuator, when included as a dependency, exposes multiple endpoints by default (e.g., `/actuator/env`, `/actuator/beans`, `/actuator/configprops`, `/actuator/mappings`). If these are not explicitly restricted, they can leak environment variables (including secrets), bean configurations, and the full API route map.

The design does not mention restricting Actuator endpoints.

**Evidence / Trace:**
```
Health check: /actuator/health
No mention of: management.endpoints.web.exposure.include, actuator security
```

**Remediation:**
- Primary fix: Add to the design: `management.endpoints.web.exposure.include=health` in `application-prod.yml` to expose only the health endpoint. Alternatively, restrict all actuator endpoints behind authentication or to internal-only access (Nginx blocks `/actuator/*` except `/actuator/health`).

---

### Finding #8: Notification Polling Susceptible to Information Leakage

**Vulnerability:** Missing Authorization on Notification Endpoints — OWASP A01
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** Medium

**Location:**
- Design Section 5 (Notifications endpoints)

**Risk & Exploit Path:**
`GET /api/v1/notifications` is listed as "Authenticated" and `PUT /api/v1/notifications/{id}/read` as "Owner only." The `GET` endpoint presumably returns only the authenticated user's notifications (filtered by `account_id`), but this is not explicitly stated. If the repository query does not filter by the current user's `account_id`, it could return all users' notifications.

Additionally, `PUT /api/v1/notifications/{id}/read` has the same IDOR concern as Finding #1 — the notification ID is in the path and ownership must be verified.

**Evidence / Trace:**
```
GET /api/v1/notifications  →  "Get own notifications"  ← Filtering mechanism not specified
NotificationRepository: findByAccountIdOrderByCreatedAtDesc()  ← Correct query exists, but controller must pass current user's ID
```

**Remediation:**
- Primary fix: Explicitly state that the controller must extract the current user's `account_id` from the security context and pass it to the repository — never accept `account_id` as a request parameter for notification queries.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: SSRF + Redis Session Forgery
If an SSRF vulnerability is discovered in any endpoint (e.g., via image URL processing, social links JSON, or Markdown rendering that fetches external resources), and Redis has no authentication (Finding #5), an attacker could chain: SSRF → Redis → forge admin session → full application compromise. This elevates the Redis authentication finding from Medium to effectively High in a chained scenario.

### Implicit Trust: Front-End as Sanitization Boundary
The design explicitly places XSS sanitization at the front-end renderer (`rehype-sanitize`). This means any consumer of the API that does *not* use `rehype-sanitize` (mobile app, third-party integration, curl) would receive unsanitized Markdown that could contain XSS payloads. The CSP header provides a second layer, but only for browsers that support it. This is an acceptable design trade-off for a single-SPA application but should be documented as an explicit constraint: **the API is not safe to consume without client-side sanitization**.

### Defense-in-Depth Gap: No WAF
There is no Web Application Firewall (WAF) mentioned. At the VPS scale, this is acceptable — Nginx + rate limiting + application-level validation provide reasonable protection. However, this should be noted as a gap to address if traffic increases or if targeted attacks are observed.

### Deployment: Docker Socket Exposure
The design uses Docker Compose on a VPS. If the deployment user has access to the Docker socket (`/var/run/docker.sock`), that user effectively has root access to the host. The design doesn't mention running containers with a non-root user or restricting Docker socket access. This is a standard Docker security concern.

---

## Final Report Sections

## 1. Executive Summary

The BlogPlatformDB Java migration design document (v6.0) demonstrates a **mature, security-conscious architecture** for a small-scale blog platform. The design addresses many common security concerns proactively: session-based auth with Redis persistence, CSRF protection, tiered rate limiting, XSS prevention with `rehype-sanitize` + CSP, filename sanitization on uploads, soft-delete patterns, and a locked-down firewall. The iterative review process (six versions) has progressively hardened the design.

The primary security concerns are at the **specification level** rather than being architectural flaws. Several "Owner only" endpoints lack explicit ownership verification mechanisms (Finding #1), which creates high risk of IDOR if implementers don't add the checks. Password reset token specifications are incomplete (Finding #3), and Redis runs without authentication (Finding #5), creating a session forgery risk if any container is compromised. There are no critical findings that would block implementation, but the High-severity IDOR concern should be addressed in the design before coding begins.

Overall, the design is **ready for implementation** with the remediation items below incorporated. The security posture is strong for a small-scale VPS deployment serving a few thousand users.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | IDOR on User/Comment/Image/Notification Endpoints | A01 | High | Medium | 6+ endpoints | FIX |
| 2 | Image Upload MIME Validation Insufficient | A01/A04 | Medium | Medium | 1 | FIX |
| 3 | Password Reset Token Spec Incomplete | A07 | Medium | Medium | 2 (reset + email verify) | FIX |
| 4 | No Account Lockout After Failed Logins | A07 | Medium | High | 1 | FIX |
| 5 | Redis Without Authentication | A05 | Medium | Medium | 1 | FIX |
| 6 | Database Credential Management Underspecified | A02/A05 | Low | Low | 1 | FIX |
| 7 | Actuator Endpoint Exposure | A05 | Low | Medium | 1 | FIX |
| 8 | Notification Endpoint Authorization Gap | A01 | Low | Low | 1 | FIX |

## 3. Security Quality Score (SQS)

**Calculation (starts at 100):**

| Severity | Count | Deduction |
|----------|-------|-----------|
| Critical | 0 | 0 |
| High | 1 | −20 |
| Medium | 4 | −32 (4 × −8) |
| Low | 3 | −6 (3 × −2) |

**Final SQS:** 42/100
**Hard gates triggered:** No
**Posture:** Unacceptable — block deployment, urgent remediation required

**Important context:** This score reflects the *design document's specification completeness*, not the security of a running application. Most findings are specification gaps (missing details) rather than architectural flaws. Addressing them in the design document before implementation would likely raise the score to 85+ with minimal effort.

## 4. Positive Security Observations

1. **Layered XSS prevention** — Markdown storage + `rehype-sanitize` + CSP `script-src 'self'` + back-end HTML rejection in comments. This is a well-thought-out, defense-in-depth approach.
2. **Session architecture** — Redis-backed sessions with fixation protection, CSRF via `CookieCsrfTokenRepository`, and explicit `withHttpOnlyFalse()` documentation for Axios compatibility. Very few design docs get this right.
3. **Tiered rate limiting** — Bucket4j with separate limits for anonymous, authenticated, and auth endpoints. The 10/min on auth endpoints specifically addresses brute force.
4. **Deferred payment processing** — Explicitly stubbing the VIP payment endpoint (501) and documenting that "client-submitted payment data must never be trusted" shows strong security awareness. Many projects ship broken payment flows.
5. **Image upload constraints** — UUID-based filenames (prevents path traversal), file size limits, per-user quotas, and MIME type validation. The design covers the most common upload attack vectors.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why Prioritized | Effort | Owner |
|----------|---------|-----------------|--------|-------|
| 1 | #1 — IDOR on Owner-only Endpoints | High severity, low attack complexity, affects 6+ endpoints. Without explicit ownership checks, every "Owner only" endpoint is vulnerable by default. | Quick Win — add a paragraph to the design specifying the ownership verification pattern | Backend |
| 2 | #3 — Password Reset Token Spec | Medium severity but affects a security-critical flow. Incomplete specs lead to insecure implementations (short tokens, plaintext storage, user enumeration). | Quick Win — add token specs to the design doc | Backend |
| 3 | #5 — Redis Authentication | Medium severity, enables session forgery if any container is compromised. Trivial to add `requirepass` to Docker Compose. | Quick Win — one line in docker-compose.yml + one property in application.yml | DevOps |
| 4 | #4 — Account Lockout | Medium severity, addresses distributed brute force that rate limiting alone cannot stop. | Moderate — requires a new table or Redis key for tracking failed attempts, plus lockout logic in AuthService | Backend |
| 5 | #7 — Actuator Restriction | Low severity but trivial to fix and prevents information disclosure. | Quick Win — one property in application-prod.yml | Backend |
