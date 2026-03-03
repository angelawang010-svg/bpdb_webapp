# Critical Implementation Review — Phase 1B: Auth System

**Plan reviewed:** `docs/plans/2026-03-01-phase1b-auth-security-implementation.md`
**Design reference:** `docs/plans/2026-02-27-java-migration-design.md` (v7.0)
**Review version:** 1
**Date:** 2026-03-03

---

## 1. Overall Assessment

The plan delivers a clear, test-driven auth system with well-structured entities, DTOs using Java records with Bean Validation, and a sensible Spring Security configuration. The code is generally clean and follows Spring conventions.

**However, the plan has several critical gaps** relative to the design document: missing brute-force/account-lockout protection, integration tests that will fail due to CSRF, manual SecurityContext manipulation that bypasses Spring Security's authentication pipeline, and a BCrypt DoS vector from unbounded password length. These must be addressed before implementation.

---

## 2. Critical Issues

### 2.1 Missing Account Lockout / Brute-Force Protection (Design Deviation)

**Description:** The design doc (§ Rate Limiting) explicitly requires per-account login lockout: track consecutive failed attempts in Redis (`login:failures:{username}`, TTL 15 min), lock after 5 failures for 15 minutes (return `423 Locked`), reset on success, and log all failed attempts with username + IP.

`AuthService.authenticate()` implements none of this. There is no Redis interaction, no failure counter, no lockout check, and no logging of failed attempts.

**Why it matters:** Without this, the auth system is vulnerable to credential stuffing and distributed brute-force attacks — the exact threat the design doc addresses. This is a security requirement, not a nice-to-have.

**Fix:** Add a `LoginAttemptService` (backed by Redis `StringRedisTemplate`) that:
- On authentication attempt: check if account is locked → throw `423` if so
- On failure: increment counter with TTL, log username + IP
- On success: delete the failure key
- Inject into `AuthService.authenticate()` or as a separate pre/post concern

### 2.2 Integration Tests Will Fail — CSRF Not Handled

**Description:** `SecurityConfig` enables CSRF via `CookieCsrfTokenRepository`. The `AuthControllerTest` sends POST requests to `/register` and `/login` without obtaining or sending a CSRF token. These tests will receive `403 Forbidden`, not the expected `201`/`200`.

**Why it matters:** The tests as written are not executable. They will fail on first run, blocking development.

**Fix:** Either:
- **(Recommended)** Use `csrf()` from `SecurityMockMvcRequestPostProcessors` on all POST requests in the test: `.with(csrf())`
- Or disable CSRF in the test security configuration (less realistic but simpler)

### 2.3 Manual SecurityContext Manipulation in Login Endpoint

**Description:** `AuthController.login()` manually creates a `UsernamePasswordAuthenticationToken`, sets it in `SecurityContextHolder`, and manually stores the context in the session. This bypasses Spring Security's `AuthenticationManager` pipeline, skipping session fixation protection, event publishing (`AuthenticationSuccessEvent`), and any configured `AuthenticationProvider` chain.

**Why it matters:** Session fixation protection is configured in `SecurityConfig` (`.sessionFixation().newSession()`) but is never triggered because authentication doesn't go through the framework. This defeats a security control the plan explicitly configures. Additionally, no `AuthenticationSuccessEvent` is published, which matters for audit logging and future integrations.

**Fix:** Define an `AuthenticationManager` bean in `SecurityConfig` and inject it into `AuthController`. Use `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password))` to let Spring Security handle session fixation, event publishing, and context storage. This requires implementing `UserDetailsService` to bridge `UserRepository` into Spring Security.

### 2.4 BCrypt DoS via Unbounded Password Length

**Description:** `RegisterRequest` validates `@Size(min = 8)` with no maximum. BCrypt implementations typically truncate at 72 bytes, but before hashing, the entire input is processed. An attacker can submit multi-megabyte passwords, causing expensive computation.

**Why it matters:** A small number of requests with very long passwords (e.g., 1MB each) can saturate BCrypt worker threads and degrade or deny service to legitimate users.

**Fix:** Add `max = 128` (or similar reasonable limit) to the `@Size` annotation on the password field.

### 2.5 `createdAt` Timestamp Set at Object Instantiation, Not Persistence

**Description:** `UserAccount.createdAt` is initialized as `private Instant createdAt = Instant.now()`. This sets the timestamp when the Java object is constructed, not when it's persisted to the database. If there's any delay between object creation and `save()` (validation, network latency, transaction queuing), the timestamp will be inaccurate.

**Why it matters:** Inaccurate creation timestamps undermine audit trails and time-based queries. The `updatable = false` constraint is correct but insufficient.

**Fix:** Use a `@PrePersist` callback:
```java
@PrePersist
protected void onCreate() {
    this.createdAt = Instant.now();
}
```
And remove the field initializer.

### 2.6 Error Responses in SecurityConfig Use String Concatenation, Not ApiResponse

**Description:** The `authenticationEntryPoint` and `accessDeniedHandler` in `SecurityConfig` build JSON via string concatenation. The rest of the application uses the `ApiResponse` wrapper. This creates inconsistency and is fragile (no escaping, no ObjectMapper).

**Why it matters:** If `ApiResponse` format changes, these responses won't be updated. String concatenation for JSON is error-prone and could produce malformed output.

**Fix:** Inject `ObjectMapper` into `SecurityConfig` and serialize `ApiResponse` objects properly:
```java
objectMapper.writeValue(response.getWriter(),
    ApiResponse.error("Authentication required"));
```

---

## 3. Minor Issues & Improvements

### 3.1 No Username Pattern Validation

`RegisterRequest.username` has `@Size(min=3, max=50)` but no `@Pattern`. Users could register with usernames containing spaces, control characters, or SQL-injection-like strings. Add a pattern like `@Pattern(regexp = "^[a-zA-Z0-9_-]+$")`.

### 3.2 No Email Normalization

Emails are stored as-provided. `Test@Example.com` and `test@example.com` would be treated as different emails (unique constraint on raw value). Normalize to lowercase before checking existence and before saving.

### 3.3 Login Count / Last Login Never Updated

`UserProfile` has `lastLogin` and `loginCount` fields, but the login flow never updates them. The design doc implies these should be tracked. Add update logic in the login flow (either in `AuthService.authenticate()` or as a post-authentication listener).

### 3.4 CORS Origins Hardcoded

`WebConfig` hardcodes `http://localhost:5173`. This should come from `application.yml` properties (e.g., `app.cors.allowed-origins`) so it can be configured per environment without code changes.

### 3.5 `findById` Added as an Afterthought in Task 10

Task 10 says "Add `findById` method to `AuthService`" as a side note, but it also requires adding a `ResourceNotFoundException` import. This should be a proper step, not a footnote, to avoid being missed during implementation.

### 3.6 Inconsistent Column Naming Strategy

Some fields use explicit `@Column(name = "...")` (e.g., `account_id`, `password_hash`) while others don't (e.g., `username`, `email`, `bio`). This relies on the JPA naming strategy being snake_case, which is Spring Boot's default, but the inconsistency is confusing. Either annotate all or none.

### 3.7 `maximumSessions(1)` Without Session Registry

`SecurityConfig` sets `.maximumSessions(1)` but doesn't configure a `SessionRegistry` bean (typically `SpringSessionBackedSessionRegistry` for Redis-backed sessions). Without it, concurrent session control won't work. Either add the registry or remove the constraint and document it as a future enhancement.

### 3.8 Missing Test: Duplicate Registration

`AuthControllerTest` doesn't test registering the same username/email twice. This is an important integration-level check to verify the `BadRequestException` propagates correctly through the controller advice.

### 3.9 Timing Side-Channel on User Enumeration

`AuthService.authenticate()` throws immediately for non-existent users (no bcrypt comparison) but performs bcrypt for wrong passwords. The timing difference could reveal whether a username exists. Mitigation: perform a dummy `passwordEncoder.matches()` against a fixed hash when the user is not found.

---

## 4. Questions for Clarification

1. **Account lockout scope:** Should lockout be implemented in Phase 1B (this plan) or deferred to Phase 1C with rate limiting? The design doc groups it with rate limiting, but it's logically part of auth.

2. **UserDetailsService integration:** The plan bypasses Spring Security's `UserDetailsService` entirely. Is this intentional, or should a proper `UserDetailsService` implementation bridge `UserRepository` into the Spring Security pipeline?

3. **Session store in tests:** `AuthControllerTest` sets `spring.session.store-type=none`. Is there a shared `@TestConfiguration` or base test class from Phase 1A that should be reused for consistency?

4. **Logout invalidation:** `SecurityConfig` configures logout at `/api/v1/auth/logout`, but there's no explicit Redis session invalidation. Is Spring Session's default invalidation (via `HttpSession.invalidate()`) sufficient, or should the plan explicitly call `sessionRepository.deleteById()`?

---

## 5. Final Recommendation

**Major revisions needed.**

The plan must address before implementation:

1. **Account lockout / brute-force protection** — design doc requirement, security-critical
2. **CSRF handling in integration tests** — tests are non-functional as written
3. **Use AuthenticationManager instead of manual SecurityContext** — current approach defeats session fixation protection
4. **Add password max length** — simple fix, prevents DoS
5. **Fix `createdAt` to use `@PrePersist`** — correctness issue

The remaining minor issues (3.1–3.9) should be addressed but are not blocking.
