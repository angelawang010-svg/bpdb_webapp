# Security Audit: Phase 1A — Project Setup & Infrastructure Implementation Plan

**Audited file:** `docs/plans/2026-03-01-phase1a-project-setup-implementation.md` (v1.2)
**Audit date:** 2026-03-03
**Auditor:** Lead Cyber-Security Auditor (LCSA)
**Scope:** White-box review of the implementation plan covering Spring Boot project initialization, Docker Compose infrastructure, Flyway migrations (schema + seed data), common layer classes, and test infrastructure.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Technology stack:** Java 21, Spring Boot 3.x, Gradle, PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers.

**Entry points identified in plan scope:**
- HTTP endpoints under `/api/v1/` (not yet defined in Phase 1A but framework is wired)
- Actuator endpoint `/actuator/health` (exposed)
- Docker Compose services (PostgreSQL on 5432, Redis on 6379)

**Trust boundaries:**
- User input → Spring MVC → JPA → PostgreSQL
- Session data → Redis
- Docker host network → container services

**Sensitive data flows:**
- `password_hash` in `user_account`
- Token hashes in `password_reset_token`, `email_verification_token`
- Admin seed credentials in V2 migration
- Database/Redis passwords in configuration files

**Authentication/Authorization:** Spring Security (configured in later phases), session-based auth via Redis.

**Built-in protections noted:**
- JPA/Hibernate (parameterized queries by default)
- Spring Security CSRF (enabled by default)
- `ddl-auto: validate` (Flyway owns schema)
- `open-in-view: false` (good practice)

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: Hardcoded Development Credentials in Source-Controlled Configuration

**Vulnerability:** Hardcoded Secrets — Cryptographic Failures & Data Exposure (OWASP A02)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `backend/src/main/resources/application-dev.yml`, Lines: 4-8 (as specified in plan)
- Related: `docker-compose.yml` (default passwords in `${DB_PASSWORD:-blogplatform}`, `${REDIS_PASSWORD:-devredispassword}`)

**Risk & Exploit Path:**
The `application-dev.yml` contains hardcoded plaintext credentials (`username: blogplatform`, `password: blogplatform`, `password: devredispassword`). The Docker Compose file mirrors these as fallback defaults. While these are dev-only values, they establish a pattern where:
1. Developers may inadvertently run production with dev profile active.
2. The hardcoded defaults in Docker Compose mean services start with known passwords even without a `.env` file, reducing the friction to deploy insecurely.

This is a common pattern for development environments and is partially mitigated by the `application-prod.yml` using environment variables without defaults (v1.2 fix M3). However, the dev profile credentials are still committed to source control.

**Evidence / Trace:**
```yaml
# application-dev.yml
spring:
  datasource:
    password: blogplatform        # ← HARDCODED
  data:
    redis:
      password: devredispassword  # ← HARDCODED
```

```yaml
# docker-compose.yml
POSTGRES_PASSWORD: ${DB_PASSWORD:-blogplatform}       # ← KNOWN DEFAULT
command: redis-server --requirepass ${REDIS_PASSWORD:-devredispassword}  # ← KNOWN DEFAULT
```

**Remediation:**
- Primary fix: Move dev credentials to `.env` file (already gitignored) and remove hardcoded passwords from `application-dev.yml`. Use `${DB_PASSWORD}` placeholders in dev profile too, or use Spring Boot's config import from `.env`.
- Defense-in-depth: Add a CI check that rejects commits containing password literals in YAML files. Add a startup check in production that fails if known dev passwords are detected.

---

### Finding #2: Hardcoded Admin Seed User with Known BCrypt Hash

**Vulnerability:** Default Credentials — Security Misconfiguration (OWASP A05)
**Severity:** High
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `backend/src/main/resources/db/migration/V2__seed_data.sql`, Lines: 10-15 (as specified in plan)

**Risk & Exploit Path:**
The V2 migration inserts an admin user with a hardcoded BCrypt password hash. The original plaintext was removed from the comment (v1.1 fix C4), but the hash itself is committed to source control. Anyone with access to the repository can:
1. Attempt offline brute-force/dictionary attacks against the BCrypt hash (BCrypt 12 rounds provides some protection but is not impervious to targeted attacks).
2. If the same hash reaches production (migration runs unconditionally), the admin account exists with a known password.

The plan includes a comment "Do NOT rely on this seed data in production," but Flyway versioned migrations (`V2`) run exactly once on every database they encounter, including production. There is no technical control preventing this.

**Evidence / Trace:**
```sql
-- V2__seed_data.sql
INSERT INTO user_account (username, email, password_hash, role, email_verified)
VALUES ('admin', 'admin@blogplatform.com',
        '$2a$12$LJ3m4ys3uz0b/tMkgqHUZeJ0SJyKfxBVOKFqW8GbMFmJN7gmPVqtG',  -- ← KNOWN HASH
        'ADMIN', TRUE);  -- ← DEFAULT ADMIN ACCOUNT
```

**Remediation:**
- Primary fix: Move admin seed to a dev-only seed mechanism (e.g., a Spring `CommandLineRunner` conditioned on `@Profile("dev")`, or a separate `V2__dev_seed_data.sql` that's only placed on classpath in dev builds). Alternatively, use Flyway's `locations` config to separate dev-only migrations.
- Architectural improvement: Production admin provisioning should be a manual, audited process (CLI tool, admin API protected by startup token, or infrastructure-as-code).
- Defense-in-depth: Add a startup check in production that warns or blocks if the known seed admin hash is detected.

---

### Finding #3: Database and Redis Ports Bound to All Interfaces

**Vulnerability:** Network Exposure — Security Misconfiguration (OWASP A05)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docker-compose.yml`, Lines: 8, 17 (port mappings)

**Risk & Exploit Path:**
The Docker Compose file maps PostgreSQL (5432) and Redis (6379) to `0.0.0.0` by default (`"5432:5432"`, `"6379:6379"`). On a multi-homed development machine or cloud VM, these services are accessible from any network interface, not just localhost. Combined with Finding #1's weak default passwords, this creates a direct path to database compromise for anyone on the same network.

**Evidence / Trace:**
```yaml
ports:
  - "5432:5432"   # ← Binds to 0.0.0.0
  - "6379:6379"   # ← Binds to 0.0.0.0
```

**Remediation:**
- Primary fix: Bind to localhost only: `"127.0.0.1:5432:5432"` and `"127.0.0.1:6379:6379"`.
- Defense-in-depth: Document that production deployments must use private networking, not port-mapped containers.

---

### Finding #4: Missing `updated_at` Column on `user_account` Table

**Vulnerability:** Audit Trail Gap — Business Logic Flaw (OWASP A08 adjacent)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** N/A

**Location:**
- File: `backend/src/main/resources/db/migration/V1__initial_schema.sql`, `user_account` table definition

**Risk & Exploit Path:**
The `user_account` table has `created_at` but no `updated_at`. Account-level changes (role escalation, password changes, VIP status changes, email verification) cannot be timestamped at the row level. This makes forensic investigation of account compromise or privilege escalation more difficult. The changelog notes this was "skipped as scope expansion" (M4), which is a reasonable project decision, but from a security perspective it's worth flagging.

**Evidence / Trace:**
```sql
CREATE TABLE user_account (
    -- ... columns ...
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    -- No updated_at column
);
```

**Remediation:**
- Primary fix: Add `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` to `user_account` and extend `AuditableEntity` for the UserAccount JPA entity.
- Defense-in-depth: Consider a separate `user_account_audit_log` table for security-critical changes (role changes, password resets, email changes).

---

### Finding #5: No Row-Level Security or Soft-Delete Filtering on `blog_post`

**Vulnerability:** Broken Access Control Risk — (OWASP A01)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `backend/src/main/resources/db/migration/V1__initial_schema.sql`, `blog_post` table

**Risk & Exploit Path:**
The `blog_post` table has `is_deleted BOOLEAN NOT NULL DEFAULT FALSE` for soft deletion, but there is no database-level default filter or view to enforce it. Every query against `blog_post` must remember to include `WHERE is_deleted = FALSE`. A missed filter in any future query (especially raw SQL or JPQL) could leak deleted content. This is a defense-in-depth concern — the primary protection will be in the application layer (JPA `@Where` annotations or repository methods), which is out of scope for Phase 1A.

**Remediation:**
- Primary fix: When implementing the `BlogPost` JPA entity, use Hibernate's `@SQLRestriction("is_deleted = false")` (Hibernate 6.3+) or `@Where(clause = "is_deleted = false")`.
- Architectural improvement: Consider a PostgreSQL view `active_blog_post` that filters soft-deleted rows, used as the default query target.

---

### Finding #6: `GlobalExceptionHandler` Catch-All May Suppress Security Exceptions

**Vulnerability:** Error Handling Interference — Error Handling & Information Leakage (OWASP A09)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `backend/src/main/java/com/blogplatform/common/exception/GlobalExceptionHandler.java`, `handleUnexpected` method

**Risk & Exploit Path:**
The `@ExceptionHandler(Exception.class)` catch-all returns a generic 500. Spring Security throws specific exceptions (`AccessDeniedException`, `AuthenticationException`) that are normally handled by Spring Security's `ExceptionTranslationFilter` to produce 401/403 responses. If `GlobalExceptionHandler` catches these first (and depending on Spring's exception handler ordering), authenticated users could see 500 instead of 403, and unauthenticated users could see 500 instead of 401. More critically, this could mask security failures.

**Evidence / Trace:**
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    log.error("Unexpected error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("An unexpected error occurred"));  // ← May catch security exceptions
}
```

**Remediation:**
- Primary fix: Add explicit handlers for `AccessDeniedException` (→ 403) and `AuthenticationException` (→ 401) before the catch-all, or exclude them:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    if (ex instanceof org.springframework.security.access.AccessDeniedException) {
        throw (org.springframework.security.access.AccessDeniedException) ex;
    }
    // ... existing logic
}
```
- Architectural improvement: Order `@RestControllerAdvice` with `@Order(Ordered.LOWEST_PRECEDENCE)` and ensure Spring Security's filter chain handles its own exceptions before reaching the controller advice.

---

### Finding #7: `payment.amount` Lacks Upper Bound Constraint

**Vulnerability:** Business Logic Flaw — (OWASP A08)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `backend/src/main/resources/db/migration/V1__initial_schema.sql`, `payment` table

**Risk & Exploit Path:**
The `amount` column has `CHECK (amount > 0)` but no upper bound. `NUMERIC(10,2)` allows values up to 99,999,999.99. Without an application-layer or database-layer upper bound, a manipulated payment request could create entries with unreasonably large amounts, potentially causing accounting issues or integer overflow in downstream systems that process amounts as integers (cents).

**Evidence / Trace:**
```sql
amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),  -- ← No upper bound
```

**Remediation:**
- Primary fix: Add a reasonable upper bound: `CHECK (amount > 0 AND amount <= 99999.99)` (or whatever max makes business sense).
- Defense-in-depth: Validate payment amounts in the service layer with business-appropriate limits.

---

### Finding #8: Comment Content Length Limit May Be Insufficient for XSS Prevention

**Vulnerability:** Input Validation Gap — Input Validation & Output Encoding (OWASP A03/A06)
**Severity:** Low
**Confidence:** Low
**Attack Complexity:** Medium

**Location:**
- File: `backend/src/main/resources/db/migration/V1__initial_schema.sql`, `comment` table

**Risk & Exploit Path:**
`comment.content` is `VARCHAR(250)`, which provides a natural length limit but does not prevent XSS. The 250-character limit is actually sufficient for many XSS payloads (e.g., `<img src=x onerror=alert(1)>` is only 33 characters). This is a **Requires Verification** item — XSS prevention depends on output encoding in the frontend/API response layer, which is out of Phase 1A scope. The database constraint alone is not a security control.

**Remediation:**
- Primary fix: Ensure HTML/script content is sanitized on input or properly encoded on output in the API layer (Phase 1B/1C).
- This is noted for tracking; no schema change needed.

---

## Pass 3: Cross-Cutting & Compositional Analysis

**Chained attack path:** Finding #1 (known credentials) + Finding #3 (ports on 0.0.0.0) = unauthenticated remote database access for anyone on the same network. This combination elevates the individual medium-severity findings to a **High** composite risk in shared development environments (e.g., coworking spaces, shared cloud VPCs).

**Implicit trust assumptions:**
- The plan assumes Flyway migrations run in trusted environments. The seed admin user (Finding #2) is a versioned migration that will execute in production.
- Spring Security exception handling is assumed to take precedence over `@RestControllerAdvice`, but this depends on filter chain ordering (Finding #6).

**Defense-in-depth gaps:**
- If `application-prod.yml` profile is not explicitly activated, the app could fall back to base `application.yml` which has no datasource config — this is actually good (fail-closed). However, if someone runs with `dev` profile in production, they get hardcoded credentials.
- No database connection encryption (SSL) is configured for any profile.

**Deployment context:**
- Docker Compose is dev-only, which limits blast radius of Findings #1 and #3.
- No Dockerfile for the application itself (not in scope yet).
- No CI/CD pipeline defined yet.

---

## 1. Executive Summary

The Phase 1A implementation plan establishes a solid Spring Boot foundation with good security defaults: JPA validation mode, disabled open-in-view, environment-variable-driven production config, and proper use of Testcontainers for testing. Two prior critical reviews have already addressed several important issues (timezone-safe columns, cascading deletes, role constraints, Redis healthcheck password exposure).

The remaining security concerns center on **credential management** and **default configurations**. The most significant issue is the hardcoded admin user in a Flyway versioned migration that will execute in all environments, including production. The network exposure of Docker services combined with known default passwords creates a composite risk in shared development environments. The `GlobalExceptionHandler` catch-all could interfere with Spring Security's exception handling.

None of these findings represent immediately exploitable vulnerabilities in a fresh dev setup, but several (particularly Findings #2 and #6) will become critical if not addressed before production deployment.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Hardcoded Dev Credentials in Source Control | A02 | Medium | Confirmed | 2 (dev yml + compose) | WARN |
| 2 | Hardcoded Admin Seed User in Versioned Migration | A05 | High | High | 1 | BLOCK |
| 3 | DB/Redis Ports Bound to All Interfaces | A05 | Medium | Confirmed | 2 | WARN |
| 4 | Missing `updated_at` on `user_account` | A08 | Low | High | 1 | WARN |
| 5 | No Soft-Delete Filtering at DB Level | A01 | Low | Medium | 1 | WARN |
| 6 | Catch-All May Suppress Security Exceptions | A09 | Medium | Medium | 1 | WARN |
| 7 | Payment Amount Lacks Upper Bound | A08 | Low | Medium | 1 | WARN |
| 8 | Comment Content Length Not an XSS Control | A03 | Low | Low | 1 | INFO |

## 3. Security Quality Score (SQS)

**Calculation (starts at 100):**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #2 | High | −20 |
| #1 | Medium | −8 |
| #3 | Medium | −8 |
| #6 | Medium | −8 |
| #4 | Low | −2 |
| #5 | Low | −2 |
| #7 | Low | −2 |
| #8 | Low (Info) | −1 |

**Total deductions:** −51

**Final SQS:** 49/100
**Hard gates triggered:** No (no confirmed Critical, no hardcoded secrets that work without cracking — the BCrypt hash alone is not a directly usable credential)
**Posture:** Unacceptable — block deployment, urgent remediation required

**Note:** This score reflects the plan as written for a hypothetical production deployment. For a dev-only Phase 1A that will be hardened before production, the practical risk is lower. However, the score accurately reflects what would ship if the plan were executed as-is and deployed to production.

## 4. Positive Security Observations

1. **`ddl-auto: validate` with Flyway** — Schema is managed by versioned migrations, not Hibernate auto-DDL. This prevents schema drift and accidental data loss.
2. **`open-in-view: false`** — Disabling the Open Session in View anti-pattern prevents lazy-loading leaks through the controller layer and reduces the risk of N+1 queries exposing unintended data.
3. **Production config uses environment variables without defaults (v1.2 M3)** — The app fails fast if required secrets are missing, preventing silent fallback to insecure defaults.
4. **Actuator limited to health endpoint only** — Minimizes information exposure from management endpoints.
5. **Token tables store hashes, not plaintext** — `password_reset_token.token_hash` and `email_verification_token.token_hash` indicate proper token handling design.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Title | Why Prioritized | Effort | Owner |
|----------|---------|-------|-----------------|--------|-------|
| 1 | #2 | Admin seed in versioned migration | High severity, runs in all environments, easy to exploit if hash is cracked | Quick Win | Backend |
| 2 | #3 | Ports bound to 0.0.0.0 | Directly exploitable in shared networks, compounds with #1 | Quick Win | DevOps |
| 3 | #6 | Catch-all suppresses security exceptions | Could break auth entirely when Spring Security is wired in Phase 1B | Quick Win | Backend |
| 4 | #1 | Hardcoded dev credentials | Low urgency for pure dev, but sets bad pattern | Moderate | Backend |
| 5 | #7 | Payment amount unbounded | Business logic protection, easy to add constraint | Quick Win | Backend |
