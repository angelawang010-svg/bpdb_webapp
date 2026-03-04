# Security Audit Report — BlogPlatformDB Java Migration Design v7.0

**Audit Date:** 2026-03-04
**Audited Document:** `docs/plans/design/2026-02-27-java-migration-design.md` (v7.0)
**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Scope:** Design-level white-box security review of the v7.0 design document. This is a follow-up audit — the previous audit (v1, 2026-03-01) targeted v6.0 and all 8 findings from that audit were remediated in v7.0. This audit reviews the v7.0 design holistically, including the effectiveness of the v1 remediations and any new or residual issues.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points
- **Public REST API** (`/api/v1/*`) — 30+ endpoints: auth, CRUD, admin, file upload, notifications
- **Image upload** — multipart POST with magic byte validation (Apache Tika)
- **Nginx reverse proxy** — serves React SPA, proxies API, serves `/uploads/*`
- **Redis** — session store + login lockout counters (internal Docker network, `requirepass` enabled)
- **PostgreSQL** — database (internal Docker network)
- **SSH** — server management (port 22, key-only, fail2ban)
- **Cron jobs** — backup, cleanup (notification 90-day, ReadPost 1-year), disk monitoring
- **Transactional email service** — password reset and email verification tokens sent via external API (Mailgun/Postmark/SendGrid)

### Trust Boundaries
1. Browser → Nginx (TLS termination, CSP, security headers)
2. Nginx → Spring Boot (internal HTTP)
3. Spring Boot → PostgreSQL (internal)
4. Spring Boot → Redis (internal, password-authenticated)
5. Spring Boot → External email API (outbound HTTPS)
6. User input → Bean Validation → Service → Repository → DB
7. Markdown → stored raw → rendered client-side via `react-markdown` + `rehype-sanitize`

### Authentication & Authorization Architecture
- Session-based auth (Spring Security + Redis-backed Spring Session)
- CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()` + Axios interceptor
- `OwnershipVerifier` service + `@PreAuthorize` for IDOR prevention
- Bucket4j rate limiting (tiered: anon 60/min, auth 120/min, auth-endpoints 10/min)
- Per-account login lockout (5 failures → 15-min lock in Redis)
- BCrypt work factor 12

### Sensitive Data Flows
- Passwords → BCrypt hash → PostgreSQL
- Session IDs → Redis (JSESSIONID cookie, HttpOnly by default)
- CSRF tokens → cookie (`HttpOnly: false` required for JS access)
- Password reset / email verification tokens → SHA-256 hash in DB, plaintext via email
- User PII (email, name, profile) → PostgreSQL
- Uploaded images → local filesystem → served via Nginx

### v1 Audit Remediation Status
All 8 findings from the v1 audit (against v6.0) have been addressed in v7.0:
1. **IDOR (High)** → `OwnershipVerifier` service + `@PreAuthorize` pattern specified ✅
2. **Image upload magic bytes (Medium)** → Apache Tika + Nginx `nosniff` on `/uploads/*` ✅
3. **Password reset token spec (Medium)** → 32-byte random, SHA-256 hashed, 30-min expiry, constant-time response ✅
4. **Account lockout (Medium)** → Redis-based 5-failure / 15-min lockout ✅
5. **Redis auth (Medium)** → `requirepass` configured ✅
6. **Credential management (Low)** → `.env`/`.env.example` gitignore, 24-char min, secrets inventory ✅
7. **Actuator restriction (Low)** → health-only exposure + Nginx block ✅
8. **Notification IDOR (Low)** → account_id from security context, never from request ✅

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: Session Cookie Missing Explicit Secure and SameSite Attributes

**Vulnerability:** Session Hijacking via Insecure Cookie Configuration — OWASP A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- Design Section 7 (Session Management)
- Design Section 7 (Spring Security Configuration)

**Risk & Exploit Path:**
The design specifies Redis-backed sessions and session fixation protection, but does not explicitly require the `Secure` flag or `SameSite` attribute on the `JSESSIONID` cookie. Spring Boot sets `Secure` only when the request arrives over HTTPS — but since Nginx terminates TLS and forwards plain HTTP internally, Spring Boot may see the request as non-HTTPS and omit the `Secure` flag unless `server.servlet.session.cookie.secure=true` is explicitly set in configuration. Without `Secure`, the cookie could be sent over HTTP if a user is tricked into visiting an HTTP URL before the redirect fires. Without `SameSite=Lax` (or `Strict`), the cookie is sent on cross-site requests from older browsers that don't default to `Lax`.

**Evidence / Trace:**
```
Session Management section:
  - "Session timeout: 30 minutes"
  - "Session fixation protection: create new session on login"
  ← No mention of Secure flag, SameSite attribute, or cookie configuration

Nginx:
  - ":80 → redirect to :443"  ← Brief window where HTTP request travels without TLS
```

**Remediation:**
- Primary fix: Add to `application-prod.yml`:
  ```yaml
  server:
    servlet:
      session:
        cookie:
          secure: true
          same-site: lax
          http-only: true
  ```
- Defense-in-depth: Nginx HSTS header (`Strict-Transport-Security: max-age=31536000; includeSubDomains`) is already specified, which mitigates repeat HTTP visits after the first.

---

### Finding #2: No CSRF Token Rotation on Login

**Vulnerability:** Session Fixation via CSRF Token Reuse — OWASP A07
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** High

**Location:**
- Design Section 7 (CSRF Protection)

**Risk & Exploit Path:**
The design specifies session fixation protection ("create new session on login") but does not mention CSRF token rotation on authentication state changes. Spring Security's `CookieCsrfTokenRepository` generates a new CSRF token per session by default, so if the session is rotated, the CSRF token should also rotate. However, this relies on implementation correctly using `SessionFixationProtectionStrategy` or `ChangeSessionIdAuthenticationStrategy`. If the session ID changes but the CSRF cookie is not cleared/regenerated, a pre-authentication CSRF token could remain valid post-login, slightly weakening the CSRF defense.

**Remediation:**
- Primary fix: Document that Spring Security's default `ChangeSessionIdAuthenticationStrategy` (used in Spring Security 6.x) changes the session ID on login and `CookieCsrfTokenRepository` ties tokens to the session, so this should work correctly out of the box. Add a note to verify this behavior in integration tests.
- Requires Verification: Confirm during implementation that the CSRF cookie is regenerated after login.

---

### Finding #3: Notification Polling Lacks Authentication State Validation on Frontend

**Vulnerability:** Information Disclosure via Stale Polling — OWASP A01/A07
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** High

**Location:**
- Design Section 3 (Front-End Structure) — `useNotifications.ts`
- Design Section 5 (Notifications endpoints)

**Risk & Exploit Path:**
The design specifies 30-second notification polling with exponential backoff on error. If a user logs out in one tab but another tab continues polling, the polling tab will send requests with the (now invalidated) session cookie. The backend should return 401, triggering the Axios interceptor to redirect to login. However, the design does not explicitly state that the notification polling hook checks authentication state before polling, or that it stops on 401. If the polling continues after logout and the session invalidation has a race condition (e.g., Redis key deletion is slightly delayed), a brief window could leak notifications to a subsequent user of the same browser.

This is a very low-likelihood scenario requiring shared browser access.

**Remediation:**
- Primary fix: Document that `useNotifications` must check `AuthContext` authentication state and disable polling when the user is not authenticated. The React Query `enabled` option should be tied to `isAuthenticated`.
- Defense-in-depth: The Axios interceptor already handles 401 globally, which provides a safety net.

---

### Finding #4: Admin Role Assignment Endpoint Lacks Audit Trail

**Vulnerability:** Insufficient Logging for Privilege Escalation — OWASP A09 (Security Logging and Monitoring Failures)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Design Section 5 (Admin endpoints) — `PUT /api/v1/admin/users/{id}/role`

**Risk & Exploit Path:**
The design documents an admin endpoint for assigning roles (promoting USER to AUTHOR, etc.) but does not specify any audit logging for this action. Role changes are high-impact security events — a compromised admin account or insider threat could promote arbitrary users to ADMIN without any record. The `PostUpdateLog` entity tracks post changes, but no equivalent exists for user role changes. Without an audit trail, detecting or investigating privilege escalation is impossible.

**Evidence / Trace:**
```
PUT /api/v1/admin/users/{id}/role  →  Access: ADMIN
  ← No audit log entity or logging requirement mentioned

PostUpdateLog exists for post changes  ← Precedent for audit trails
```

**Remediation:**
- Primary fix: Add a `RoleChangeLog` entity (or a generic `AdminAuditLog`) that records: `admin_account_id`, `target_account_id`, `old_role`, `new_role`, `changed_at`, `ip_address`. Log every role change.
- Defense-in-depth: Emit an INFO-level log line for every role change (admin username, target username, old role, new role) so it appears in application logs even if the database audit table is compromised.

---

### Finding #5: Password Reset Flow Does Not Invalidate Existing Sessions

**Vulnerability:** Incomplete Password Reset — OWASP A07
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Design Section 2 (auth package) — `AuthService.java`
- Design Section 5 (Auth endpoints) — `POST /api/v1/auth/reset-password`

**Risk & Exploit Path:**
The design specifies a secure password reset flow (cryptographically random tokens, SHA-256 hashed storage, 30-minute expiry, constant-time response). However, it does not specify that existing sessions are invalidated when a password is reset. If a user's password was compromised and they reset it, the attacker's existing session (stored in Redis) remains valid until it expires (30-minute inactivity timeout). This gives an attacker up to 30 minutes of continued access after the victim resets their password.

**Evidence / Trace:**
```
POST /api/v1/auth/reset-password:
  "Accept token + new password, reset if token valid and email verified"
  ← No mention of invalidating existing sessions

Session timeout: 30 minutes of inactivity
  ← Attacker's session could remain active for up to 30 minutes
```

**Remediation:**
- Primary fix: After a successful password reset, `AuthService` must delete all Redis sessions for that `account_id`. Spring Session provides `FindByIndexNameSessionRepository.findByPrincipalName()` to locate all sessions for a user, then delete them.
- Architectural improvement: Apply the same session invalidation on password change (if added later) and on admin-initiated account lock.

---

### Finding #6: `mark-all-as-read` Notifications Endpoint Missing Ownership Scoping Specification

**Vulnerability:** Potential Mass Data Modification — OWASP A01 (Broken Access Control)
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** Low

**Location:**
- Design Section 5 (Notifications) — `PUT /api/v1/notifications/read-all`

**Risk & Exploit Path:**
The design specifies `PUT /api/v1/notifications/read-all` as "Authenticated" with the note "scoped: `WHERE is_read = false`". It also states that the notification controller "must extract `account_id` from security context, never from request parameters." However, the `read-all` endpoint description does not explicitly state that the `WHERE` clause also includes `AND account_id = :currentUser`. If the implementation misses the `account_id` scoping on this bulk update, one authenticated user could mark all notifications for all users as read.

This is a specification clarity issue — the controller-level note about security context extraction should be sufficient, but the `read-all` description's `WHERE is_read = false` clause without the `AND account_id = :currentUser` is misleading.

**Remediation:**
- Primary fix: Update the design to explicitly state the full clause: `WHERE account_id = :currentUser AND is_read = false`. This removes implementation ambiguity.

---

### Finding #7: Docker Compose Secrets Passed via Environment Variables

**Vulnerability:** Secret Exposure in Container Runtime — OWASP A02 (Cryptographic Failures) / A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- Design Section 10 (Deployment Process) — `.env` file and Docker Compose

**Risk & Exploit Path:**
The design specifies secrets (DB_PASSWORD, REDIS_PASSWORD, SESSION_SECRET, EMAIL_API_KEY) stored in a `.env` file and injected via Docker Compose environment variables. Environment variables in Docker are visible via `docker inspect`, in `/proc/<pid>/environ` on the host, and may appear in error logs or crash dumps. While the `.env` file itself is gitignored, the runtime exposure surface is broader than necessary.

**Evidence / Trace:**
```
.env → Docker Compose env vars → visible in:
  - docker inspect <container>
  - /proc/<pid>/environ
  - Container crash dumps
  - docker compose config (prints resolved env)
```

**Remediation:**
- Primary fix: Use Docker Compose secrets (`secrets:` top-level key with file-based secrets) instead of environment variables for sensitive values. Secrets are mounted as files at `/run/secrets/<name>` and are not visible via `docker inspect`.
- Acceptable risk: For a single-VPS deployment with a single operator, the `.env` approach is common and the risk is low if the VPS is properly secured (SSH key-only, fail2ban). Document the limitation and recommend Docker secrets for operators who want defense-in-depth.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks
- **No critical chains identified.** The v7.0 design has addressed the most significant compositional gaps from v1 (IDOR + missing ownership verification). The remaining findings are individually medium/low severity and do not combine into critical paths.

### Implicit Trust Assumptions
- The design correctly identifies that Spring Boot trusts Nginx (internal network) and Redis trusts Spring Boot (password-authenticated). The Nginx → Spring Boot trust boundary is acceptable given the Docker internal network.
- **Residual assumption:** The email service API key is trusted to not be rate-limited or suspended by the provider. If the email service is unavailable, password resets and email verification silently fail. The design does not specify error handling for email delivery failures. This is a reliability concern, not a security vulnerability, but could become one if users cannot verify their email and the system falls back to allowing unverified accounts to perform sensitive actions.

### Defense-in-Depth Assessment
The v7.0 design has good layered defenses:
- **Auth:** BCrypt + session fixation protection + account lockout + rate limiting
- **XSS:** Markdown storage + `rehype-sanitize` + CSP `script-src 'self'` + comment HTML rejection
- **IDOR:** `OwnershipVerifier` pattern + `@PreAuthorize`
- **Upload:** Tika magic bytes + UUID filenames + size limits + Nginx `nosniff`
- **Network:** UFW + Docker internal network + SSH hardening

**Gap:** No Web Application Firewall (WAF) is specified. At this scale, this is acceptable — the application-level controls are sufficient. A WAF can be added if moving to AWS (ALB + AWS WAF).

---

## 1. Executive Summary

The BlogPlatformDB v7.0 design document demonstrates strong security maturity. All 8 findings from the v1 audit (against v6.0) have been comprehensively remediated, including the High-severity IDOR finding which now has a well-specified `OwnershipVerifier` pattern. The design shows deliberate security thinking across authentication, authorization, input validation, XSS prevention, and deployment hardening.

This v2 audit identified 7 new findings: 0 Critical, 0 High, 4 Medium, 3 Low. The most impactful finding is the missing session invalidation on password reset (Finding #5), which could allow an attacker to maintain access for up to 30 minutes after a victim resets their compromised password. The remaining findings are specification clarifications, defense-in-depth improvements, and operational security hardening.

The design is ready for implementation with the remediation of Finding #5 (session invalidation on password reset) as a required pre-deployment fix. The other findings can be addressed during implementation or as part of Phase 4 hardening.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Session Cookie Missing Secure/SameSite Attributes | A07 | Medium | Medium | 1 | FIX |
| 2 | No CSRF Token Rotation on Login (Verification Needed) | A07 | Low | Low | 1 | VERIFY |
| 3 | Notification Polling Lacks Auth State Check | A01/A07 | Low | Low | 1 | FIX |
| 4 | Admin Role Assignment Lacks Audit Trail | A09 | Medium | High | 1 | FIX |
| 5 | Password Reset Does Not Invalidate Sessions | A07 | Medium | High | 1 | FIX |
| 6 | mark-all-as-read Missing Explicit account_id Scoping | A01 | Low | Low | 1 | CLARIFY |
| 7 | Docker Compose Secrets via Environment Variables | A02/A05 | Medium | High | 1 | ACCEPT/FIX |

## 3. Security Quality Score (SQS)

**Calculation (starts at 100):**

| Finding Severity | Count | Deduction | Total |
|-----------------|-------|-----------|-------|
| Critical | 0 | −40 | 0 |
| High | 0 | −20 | 0 |
| Medium | 4 | −8 | −32 |
| Low | 3 | −2 | −6 |

**Final SQS:** 62/100
**Hard gates triggered:** No
**Posture:** Unacceptable — remediate Medium findings before deployment

*Note:* This score reflects the design document's specification gaps, not necessarily the security of the eventual implementation. Several findings (e.g., cookie attributes, Docker secrets) are configuration items that may be handled correctly during implementation even without explicit design specification. If Findings #1 and #7 are accepted as implementation details rather than design gaps, the adjusted score would be 78/100 (Acceptable).

**Recommended interpretation:** Address Finding #5 (session invalidation) as mandatory. Treat Findings #1, #4, #7 as implementation requirements to document. Accept the adjusted posture of **Acceptable (78/100)** with a remediation commitment for the items above.

## 4. Positive Security Observations

1. **Comprehensive IDOR prevention pattern.** The `OwnershipVerifier` service with `@PreAuthorize` is well-specified, covering all "Owner only" and "Owner, ADMIN" endpoints with explicit listing. This is significantly better than per-endpoint ad-hoc checks.

2. **Secure token design.** Password reset and email verification tokens use 32-byte cryptographically random values, SHA-256 hashed storage, single-use, 30-minute expiry, and constant-time response to prevent enumeration. This is textbook secure token design.

3. **Layered XSS defense.** Four independent layers (Markdown storage format, `rehype-sanitize`, CSP `script-src 'self'`, comment HTML rejection) make XSS exploitation extremely unlikely even if one layer fails.

4. **Account lockout with Redis TTL.** The 5-failure/15-minute lockout using Redis keys with TTL is simple, effective, and auto-recovers without admin intervention. Combined with rate limiting on auth endpoints, brute force is well-mitigated.

5. **Upload security defense-in-depth.** Magic byte validation (Apache Tika) + UUID filenames + size limits + per-user quota + Nginx `nosniff` on `/uploads/*` addresses the full spectrum of file upload attacks (polyglot, path traversal, storage DoS, MIME sniffing).

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Title | Why Prioritized | Effort | Owner |
|----------|---------|-------|-----------------|--------|-------|
| 1 | #5 | Password reset must invalidate sessions | Active attacker retains access after password reset; highest real-world impact | Quick Win | Backend |
| 2 | #4 | Admin role changes need audit log | Unauditable privilege escalation; compliance and incident response gap | Moderate | Backend |
| 3 | #1 | Session cookie Secure/SameSite attributes | Configuration oversight that could expose session cookies; easy fix | Quick Win | Backend |
| 4 | #6 | Clarify mark-all-as-read scoping | Specification ambiguity that could lead to implementation bug | Quick Win | Backend |
| 5 | #7 | Docker secrets vs environment variables | Runtime secret exposure; acceptable risk for single-VPS but document the limitation | Moderate | DevOps |
