# Security Audit Report — Phase 2B Platform Features Implementation Plan

**Audited File:** `docs/plans/phase2b/2026-03-01-phase2b-platform-features-implementation.md` (v3.0)
**Audit Date:** 2026-03-15
**Auditor:** LCSA (automated security review)
**Scope:** Plan-level security review — analyzing specified designs, code snippets, and architectural decisions for security vulnerabilities before implementation begins.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry Points (new in Phase 2B):**
- `POST/DELETE /api/v1/subscriptions` — authenticated
- `GET /api/v1/notifications`, `PUT .../read`, `PUT .../read-all` — authenticated
- `GET/PUT /api/v1/users/{id}`, `GET /api/v1/users/{id}/saved-posts` — owner/admin
- `POST /api/v1/auth/forgot-password`, `POST /api/v1/auth/reset-password` — public
- `POST /api/v1/auth/verify-email` — public
- `POST /api/v1/posts/{postId}/images`, `GET /api/v1/images/{id}`, `DELETE /api/v1/images/{id}` — various auth
- `POST /api/v1/users/{id}/upgrade-vip` — owner/admin
- `GET /api/v1/admin/posts/deleted`, `PUT .../restore`, `PUT .../users/{id}/role` — ADMIN only
- Swagger UI at `/swagger-ui.html` — public

**Trust Boundaries:**
- User input → Spring controllers → Services → PostgreSQL stored procedures/JPA
- File uploads → Apache Tika validation → local filesystem
- Async event pipeline: PostService → ApplicationEventPublisher → NotificationService (async)
- Scheduled jobs: cron → repository batch deletes

**Authentication Architecture:** Session-based (Spring Session + Redis), NOT JWT. CSRF enabled via CookieCsrfTokenRepository. BCrypt strength 12.

**Sensitive Data Flows:** Password reset tokens, email verification tokens, user email addresses, uploaded images, notification content, user profiles.

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: OpenAPI Security Scheme Misconfigured as JWT — Application Uses Sessions

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: Plan Task 21, Lines 694–724
- Related: `backend/src/main/java/com/blogplatform/config/SecurityConfig.java` (existing session-based auth)

**Risk & Exploit Path:**
The plan specifies a Bearer JWT security scheme in OpenAPI config, but the existing application uses session-based authentication with CSRF cookies. This means:
1. Swagger UI will prompt developers to enter a "Bearer JWT" token that doesn't exist.
2. Developers may attempt to build the frontend using JWT auth against a session-based backend.
3. If someone later adds JWT support to make Swagger work, they may introduce a parallel auth path that bypasses session security controls (CSRF, session fixation protection, concurrent session limits).

**Evidence / Trace:**
```java
// Task 21 — specifies JWT
.addSecuritySchemes("bearer-jwt",
        new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"))  // ← MISCONFIGURED — no JWT exists
```

vs. existing SecurityConfig.java:
```java
// Existing — session-based auth
http.sessionManagement(session -> session.maximumSessions(1)...);
http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
```

**Remediation:**
- **Primary fix:** Change OpenAPI config to document session-based auth with CSRF. Use `SecurityScheme.Type.APIKEY` with cookie-based session, or simply document that authentication requires `POST /api/v1/auth/login` and subsequent requests carry the session cookie.
- **Defense-in-depth:** Add a comment in the OpenAPI config explaining the auth mechanism to prevent future confusion.

---

### Finding #2: Email Content Injection via Post Title/Author in Notifications

**Vulnerability:** Injection — OWASP A03
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 12, Lines 144–149

**Risk & Exploit Path:**
Notification messages are constructed by string concatenation: `"New post: " + event.title() + " by " + event.authorName()`. If the post title or author display name contains crafted content and notifications are later rendered in a web UI or email, this creates a stored XSS vector. The plan does not specify output encoding for notification messages.

Additionally, if the `EmailService` (Task 14) is later upgraded from logging to actual SMTP sending, and notification content is included in emails, an attacker could inject email headers or HTML content through crafted post titles.

**Evidence / Trace:**
```java
n.setMessage("New post: " + event.title() + " by " + event.authorName()); // ← VULNERABLE — no sanitization
```

**Remediation:**
- **Primary fix:** Sanitize/validate post titles and author names at the input boundary (PostService/UserService). Enforce character restrictions (no HTML, no control characters).
- **Defense-in-depth:** When rendering notifications (API response or email), apply context-appropriate output encoding. For the API, ensure JSON serialization handles this. For future email rendering, use a templating engine with auto-escaping.

---

### Finding #3: Image Upload — No Content-Security-Policy on Served Images

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 17, Lines 506–508

**Risk & Exploit Path:**
The plan correctly specifies `X-Content-Type-Options: nosniff` and proxies images through a controller (good — no static directory serving). However, it does not specify `Content-Security-Policy` headers on image responses. A crafted image that somehow passes Tika validation but is served with a permissive CSP could be leveraged in certain browser-level attacks. More critically, the plan does not specify that `Content-Type` on the response must be set from the **stored MIME type** (determined by Tika during upload), not from any request parameter or file extension.

**Evidence / Trace:**
```
GET /api/v1/images/{id} — serves image with Content-Disposition: inline, Content-Length,
X-Content-Type-Options: nosniff, and Cache-Control headers.
// ← Missing: Content-Security-Policy, explicit Content-Type from stored metadata
```

**Remediation:**
- **Primary fix:** Set `Content-Type` from the MIME type stored at upload time (validated by Tika). Add `Content-Security-Policy: default-src 'none'` to image responses to prevent any script execution context.
- **Defense-in-depth:** Consider `Content-Disposition: attachment` for downloads and `sandbox` CSP directive.

---

### Finding #4: Password Reset Token — Timing Side-Channel on Token Lookup

**Vulnerability:** Cryptographic Failures — OWASP A02
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: Plan Task 15, Lines 354–363

**Risk & Exploit Path:**
The plan correctly specifies constant-time response for the forgot-password endpoint (always returns 200). However, for the reset-password endpoint, the plan does not specify constant-time token comparison. The SHA-256 hash lookup via database query (`WHERE token_hash = :hash`) is inherently timing-safe (database lookups are not vulnerable to byte-by-byte timing), so the DB lookup itself is fine. However, the plan should ensure that the overall response time for valid-but-expired, valid-but-used, and invalid tokens is indistinguishable to prevent token existence oracle attacks.

**Evidence / Trace:**
```java
// Plan specifies these as different exceptions:
resetPassword_expiredToken_throwsBadRequest()   // different code path
resetPassword_usedToken_throwsBadRequest()       // different code path
// vs. invalid token — different code path entirely
// An attacker can distinguish "token exists but expired" from "token doesn't exist"
```

**Remediation:**
- **Primary fix:** Return the same error message and HTTP status code for all failure cases (invalid, expired, used). Use a single generic message like "Invalid or expired token."
- **Defense-in-depth:** Ensure all failure paths have similar response times. This is mostly academic given the 30-minute expiry window.

---

### Finding #5: Forgot-Password Rate Limit — Account-Level Only, No IP-Based Protection

**Vulnerability:** Broken Access Control / Business Logic — OWASP A01/A08
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 15, Lines 347–363

**Risk & Exploit Path:**
The plan implements per-account rate limiting (3 requests per hour) but defers IP-based rate limiting to "production deployment at the reverse proxy layer." This creates two gaps:
1. **Email bombing:** An attacker who knows a target's email can trigger 3 password reset emails per hour indefinitely — enough for harassment or to train the target to ignore reset emails (social engineering setup).
2. **Account enumeration via rate limit oracle:** If an attacker sends forgot-password requests for `user@example.com` and observes that the rate limit kicks in (even though the response is 200), they confirm the account exists. The plan says "returns 200 (constant-time) but no new token/email" — but if the *rate limit check itself* differs in timing or if the DB query for counting tokens takes measurably longer than "account not found," it leaks information.

**Evidence / Trace:**
```java
// Rate limit check requires DB query:
passwordResetTokenRepository.countByAccountAndCreatedAtAfter(account, ...) // ← only runs if account exists
// If account doesn't exist, this query never runs — potential timing difference
```

**Remediation:**
- **Primary fix:** Add a global IP-based rate limit in the application layer (not just reverse proxy) — e.g., Spring's `RateLimiter` or a Redis-based counter. Limit to 10 forgot-password requests per IP per hour.
- **Architectural improvement:** Ensure the forgot-password code path executes the same DB queries regardless of whether the account exists (constant-time pattern).

---

### Finding #6: Notification Endpoint — Missing Pagination Limits

**Vulnerability:** Unrestricted Resource Consumption — OWASP A04 (related)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Plan Task 12, Lines 173–175

**Risk & Exploit Path:**
`GET /api/v1/notifications` is specified as authenticated and paginated (implicitly, via Spring conventions), but the plan does not specify maximum page size. A user could request `?size=999999`, forcing the server to load and serialize a massive notification payload in a single request, leading to memory pressure or slow responses.

**Evidence / Trace:**
```
GET /api/v1/notifications — authenticated, extracts account_id from security context
// ← No max page size specified
```

**Remediation:**
- **Primary fix:** Enforce maximum page size (e.g., 50) in the controller or via a global `Pageable` resolver configuration. Spring Boot supports `spring.data.web.pageable.max-page-size`.

---

### Finding #7: Image Upload Directory — Path Not Validated as Absolute or Sandboxed

**Vulnerability:** Path Traversal — OWASP A01
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 17, Lines 500–502

**Risk & Exploit Path:**
The plan specifies "Save to configurable upload directory (default: `./uploads/`)." The UUID-based filename generation prevents path traversal via filenames (good). However, the plan does not specify validation of the configured upload directory path itself. If an operator misconfigures this to a sensitive location, or if the path is loaded from an environment variable that could be tampered with, images could be written to unintended locations.

More importantly, the `GET /api/v1/images/{id}` endpoint retrieves images by ID from the database, but the plan should specify that the file path stored in the database is validated against the configured upload directory before serving — ensuring a compromised DB record can't serve arbitrary files.

**Evidence / Trace:**
```
Save to configurable upload directory (default: ./uploads/)
// ← No specification that served file paths must be validated against upload root
```

**Remediation:**
- **Primary fix:** When serving images, resolve the stored file path and verify it starts with the canonical path of the upload directory (`uploadDir.toPath().toRealPath()`). Reject any path that escapes the sandbox.
- **Defense-in-depth:** Use an absolute path for the upload directory in configuration. Validate at startup.

---

### Finding #8: Admin Role Assignment — No Self-Demotion Check

**Vulnerability:** Business Logic — OWASP A04 (related)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 19, Lines 576–580

**Risk & Exploit Path:**
The plan includes a last-admin guard (cannot demote if count <= 1), which is good. However, it does not specify whether an admin can change their own role. If an admin accidentally demotes themselves and they're not the last admin, they lose access to admin functions with no self-service recovery path. This is a usability/availability concern, not a direct security vulnerability.

**Evidence / Trace:**
```
PUT /api/v1/admin/users/{id}/role — ADMIN only, assigns role
// Last-admin guard checks count, but no self-demotion warning/prevention
```

**Remediation:**
- **Primary fix:** Add a confirmation mechanism or prevent self-demotion without a second admin's approval. At minimum, log admin role changes as audit events.

---

### Finding #9: Scheduled Jobs — No Distributed Lock for Multi-Instance Deployments

**Vulnerability:** Business Logic / Race Condition — OWASP A04 (related)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Plan Task 20, Lines 622–666

**Risk & Exploit Path:**
The scheduled cleanup jobs use `@Scheduled` which runs on every application instance. In a multi-instance deployment, all instances will execute the same cleanup jobs simultaneously, leading to:
1. Redundant work and lock contention on the same rows.
2. Potential batch-delete conflicts where two instances try to delete the same rows.
3. The `DELETE ... WHERE id IN (SELECT id ... LIMIT)` pattern without `FOR UPDATE SKIP LOCKED` means two instances could select and attempt to delete the same batch.

This won't cause data corruption (deletes are idempotent) but causes unnecessary DB load.

**Evidence / Trace:**
```java
@Scheduled(cron = "0 0 2 * * *") // Runs on EVERY instance
public void cleanupOldNotifications() { ... }
```

**Remediation:**
- **Primary fix:** Use ShedLock or a similar distributed lock library to ensure only one instance runs each job.
- **Defense-in-depth:** Add `FOR UPDATE SKIP LOCKED` to batch select queries so concurrent instances don't contend on the same rows.

---

### Finding #10: Email Verification — No Rate Limit on Resend

**Vulnerability:** Business Logic — OWASP A04
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 16, Lines 420–430

**Risk & Exploit Path:**
The plan specifies email verification on registration but does not mention a "resend verification email" endpoint or rate limiting on verification token creation. If a resend mechanism is added later without rate limiting, it becomes an email bombing vector. Even without resend, the plan should specify that creating a new verification token invalidates previous ones to prevent token accumulation.

**Remediation:**
- **Primary fix:** Specify that creating a new verification token invalidates (deletes or marks used) any existing unused tokens for the same account.
- **Defense-in-depth:** If a resend endpoint is added, apply the same rate limiting pattern as forgot-password (max 3 per hour per account).

---

### Finding #11: LoggingEmailService Logs Email Body — PII Exposure in Dev/Test Logs

**Vulnerability:** Data Exposure — OWASP A02
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: Plan Task 14, Lines 251–253

**Risk & Exploit Path:**
The `LoggingEmailService` logs the full email body at INFO level, which includes password reset tokens (in plaintext URL form) and email verification tokens. In dev/test environments with shared logging infrastructure (e.g., centralized log aggregation), this exposes sensitive tokens to anyone with log access.

**Evidence / Trace:**
```java
log.info("EMAIL TO: {} | SUBJECT: {} | BODY: {}", to, subject, body); // ← Logs plaintext reset tokens
```

**Remediation:**
- **Primary fix:** Log at DEBUG level instead of INFO, or redact the token portion of the body. At minimum, ensure dev/test log infrastructure is access-controlled.
- **Defense-in-depth:** This is acceptable for local development but should be flagged for any shared environment.

---

### Finding #12: Swagger UI and Actuator Exposed — No Profile Restriction

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 21, Lines 726–730
- Related: Existing `SecurityConfig.java` permits `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`

**Risk & Exploit Path:**
The existing SecurityConfig permits public access to Swagger UI and actuator health. The plan (Task 21) adds OpenAPI configuration but does not specify profile-based restrictions. If deployed to production without changes, Swagger UI exposes the full API surface to attackers (endpoint discovery, parameter types, authentication requirements). While `/actuator/health` alone is low-risk, it confirms the application is running and reveals the tech stack.

**Remediation:**
- **Primary fix:** Restrict Swagger UI and API docs endpoints to dev/test profiles only. In SecurityConfig, conditionally permit these paths based on `@Profile`.
- **Defense-in-depth:** If Swagger must be available in production, require authentication to access it.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: User Enumeration via Multiple Oracles

Findings #4 and #5 combine: the forgot-password endpoint has both a timing oracle (account existence check before rate limit query) and a rate-limit oracle (rate limit only triggers for existing accounts). While individually low-severity, together they provide a reliable user enumeration mechanism. **Combined severity: Medium.**

### Implicit Trust: Notification System Trusts Post Content

Finding #2 highlights that the notification system trusts post title and author name without sanitization. Combined with the async nature (Task 12), there's no synchronous validation gate between post creation and notification content — any input validation must happen at the PostService level.

### Defense-in-Depth Gap: No Audit Logging for Security Events

The plan specifies post audit logging (Phase 2A) but does not mention audit logging for security-sensitive operations introduced in Phase 2B: password resets (successful and failed), email verifications, admin role changes, or image uploads. If an account is compromised via password reset, there would be no audit trail.

### Deployment Context: Upload Directory Persistence

The plan stores uploaded images on the local filesystem (`./uploads/`). In containerized deployments, this data is ephemeral unless a persistent volume is mounted. The plan does not address this, which could lead to data loss.

---

## 1. Executive Summary

The Phase 2B implementation plan demonstrates strong security awareness in several areas — particularly the password reset token design (SHA-256 hashing, single-use, constant-time forgot-password responses), image upload validation (Tika magic bytes, MIME allowlist, SVG rejection, decompression bomb protection, UUID filenames), and the batched notification retry design.

However, the plan contains a confirmed architectural mismatch (JWT OpenAPI config vs. session-based auth) that will cause developer confusion and could lead to the introduction of a parallel, less-secure auth mechanism. Several medium-severity issues relate to incomplete rate limiting, missing output encoding for notification content, and production-exposure of Swagger UI. The scheduled jobs lack distributed locking, which is an operational concern for multi-instance deployments.

No critical vulnerabilities were identified. The plan is **suitable for implementation** with the recommended remediations applied, particularly Findings #1, #5, and #12 which should be addressed before or during implementation.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | OpenAPI JWT scheme vs. session auth mismatch | A05 | Medium | Confirmed | 1 | FIX BEFORE IMPL |
| 2 | Email/notification content injection via post title | A03 | Medium | Medium | 1 | FIX DURING IMPL |
| 3 | Missing CSP and explicit Content-Type on image responses | A05 | Medium | High | 1 | FIX DURING IMPL |
| 4 | Timing side-channel on password reset token lookup | A02 | Low | Medium | 1 | ACCEPT |
| 5 | Forgot-password rate limit — account-only, no IP-based | A01 | Medium | High | 1 | FIX DURING IMPL |
| 6 | Notification endpoint — no max page size | A04 | Low | Medium | 1 | FIX DURING IMPL |
| 7 | Image upload directory path not sandboxed on serve | A01 | Medium | Medium | 1 | FIX DURING IMPL |
| 8 | Admin self-demotion not prevented | A04 | Low | High | 1 | ACCEPT |
| 9 | Scheduled jobs — no distributed lock | A04 | Low | Medium | 1 | FIX BEFORE PROD |
| 10 | Email verification — no resend rate limit / token invalidation | A04 | Low | High | 1 | FIX DURING IMPL |
| 11 | LoggingEmailService logs plaintext tokens | A02 | Low | Confirmed | 1 | ACCEPT |
| 12 | Swagger UI and actuator exposed without profile restriction | A05 | Medium | High | 1 | FIX BEFORE IMPL |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 6 | −48 |
| Low | 6 | −12 |

**Final SQS:** 40/100
**Hard gates triggered:** No
**Posture:** Unacceptable — block deployment, urgent remediation required

**Note:** This score reflects the plan as written. Since this is a pre-implementation review, the score represents risk *if implemented without changes*. Applying the recommended remediations during implementation would bring the score to approximately 86/100 (Strong).

## 4. Positive Security Observations

1. **Password reset token design is excellent.** SHA-256 hashing of tokens (not storing plaintext), SecureRandom generation, 30-minute expiry, single-use flag, and constant-time forgot-password response. This follows industry best practices.
2. **Image upload security is thorough.** MIME allowlist, SVG rejection, Apache Tika magic byte validation, decompression bomb protection via header-only dimension reads, UUID filename generation, and controller-proxied serving (no static directory exposure).
3. **Notification batching and retry design is well-architected.** Separating the dispatcher from per-batch retry prevents duplicate notifications on partial failure. The `@Recover` fallback with dead-letter TODO shows awareness of failure modes.
4. **Last-admin guard prevents lockout.** The 409 Conflict response on attempting to demote the last admin is a good business logic safety check.
5. **Batch deletion with independent transactions.** Using `REQUIRES_NEW` propagation for each batch prevents long-held locks — this is the correct pattern for large-scale cleanup operations.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — OpenAPI JWT mismatch | Architectural confusion will propagate to frontend development; fix is trivial | Quick Win | Backend |
| 2 | #12 — Swagger/actuator in production | Direct attack surface exposure; one config change | Quick Win | Backend / DevOps |
| 3 | #5 — IP-based rate limiting on forgot-password | Prevents email bombing and strengthens user enumeration protection | Moderate | Backend |
| 4 | #7 — Image serve path sandboxing | Prevents arbitrary file read if DB is compromised; add canonical path check | Quick Win | Backend |
| 5 | #3 — CSP on image responses + explicit Content-Type | Prevents edge-case browser attacks on served images | Quick Win | Backend |
