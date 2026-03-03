# Security Audit: Phase 1B Auth System Implementation Plan

**Audited file:** `docs/plans/2026-03-01-phase1b-auth-security-implementation.md` (v1.1)
**Audit date:** 2026-03-03
**Auditor:** LCSA — White-box plan review
**Review status:** All findings verified and dispositioned with project owner.

**Scope:** This is a plan-level security review (no running code). All findings are based on the code snippets and architectural decisions specified in the plan. Confidence levels reflect that this is a design review, not a runtime analysis.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry points:** 5 HTTP endpoints under `/api/v1/auth/`:
- `POST /register` — public, creates user
- `POST /login` — public, authenticates user, creates session
- `POST /logout` — authenticated (Spring Security built-in)
- `GET /me` — authenticated, returns current user
- `GET /verify-email`, `POST /forgot-password`, `POST /reset-password` — listed in SecurityConfig permitAll but not implemented in this phase

**Trust boundaries:**
- User input → Controller (Jakarta Validation) → AuthService → UserRepository → PostgreSQL
- Session: Redis-backed via Spring Session
- Brute-force: Redis-backed LoginAttemptService

**Auth architecture:** Session-based with Spring Security, BCrypt(12), CSRF via cookie, CORS externalized, session fixation protection enabled, max 1 concurrent session.

**Sensitive data:** passwords, email addresses, session tokens, CSRF tokens.

**Technology stack protections:** Spring Security (session fixation, CSRF), Spring Data JPA (parameterized queries), Jakarta Bean Validation, BCrypt.

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: Double Password Hashing on Login — Performance Waste & Potential Race Condition

**Vulnerability:** Redundant Authentication — Business Logic Flaw (A07)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** N/A (design flaw, not directly exploitable)
**Disposition:** FIX — accepted

**Location:**
- File: AuthController.java (plan lines 1200–1232), Task 11 Step 3 (updated login method)

**Risk & Exploit Path:**
The final login flow calls `authService.authenticate()` (which does `passwordEncoder.matches()` + brute-force checks), then *immediately* calls `authenticationManager.authenticate()` which triggers `CustomUserDetailsService.loadUserByUsername()` and a *second* `passwordEncoder.matches()` via `DaoAuthenticationProvider`. This means:

1. BCrypt is computed **twice** per login (each BCrypt(12) takes ~250ms → ~500ms total per login).
2. The two checks are not atomic — a race condition exists where a password could be changed between the two checks, causing the second to fail after the first succeeded.
3. `AuthService.authenticate()` checks lockout and records failures, but `AuthenticationManager.authenticate()` does NOT — if the second check fails (e.g., race condition), the failure is not tracked, and the response may leak different error behavior.

**Evidence / Trace:**
```java
// Step 1: AuthService verifies password (BCrypt check #1)
UserAccount user = authService.authenticate(request.username(), request.password()); // ← BCrypt #1

// Step 2: AuthenticationManager verifies password AGAIN (BCrypt check #2)
Authentication authentication = authenticationManager.authenticate(  // ← BCrypt #2
        new UsernamePasswordAuthenticationToken(request.username(), request.password())
);
```

**Accepted Remediation:**
Drop `AuthenticationManager.authenticate()` from the login flow. After `AuthService.authenticate()` succeeds, construct the `Authentication` token manually:
```java
UserAccount user = authService.authenticate(request.username(), request.password());
Authentication auth = new UsernamePasswordAuthenticationToken(
    user.getUsername(), null,
    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
```
This preserves brute-force protection, avoids double hashing, and removes `AuthenticationManager` as a login dependency. Session fixation protection still works via Spring Session when the security context is stored in a new session. The `AuthenticationManager` bean can be removed from `SecurityConfig` if not used elsewhere.

---

### Finding #2: Email Enumeration via Registration Error Messages

**Vulnerability:** Information Disclosure — User Enumeration (A07)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low
**Disposition:** FIX — accepted (partial)

**Location:**
- File: AuthService.java (plan lines 883–888)
- File: AuthControllerTest.java (plan lines 1047–1049)

**Risk & Exploit Path:**
Registration returns distinct error messages for duplicate usernames ("Username already taken") vs. duplicate emails ("Email already registered"). An attacker can enumerate valid emails by observing different error responses.

The login path mitigates this with timing-safe dummy hashing and a generic "Invalid credentials" message — but registration undoes that mitigation for email addresses.

**Evidence / Trace:**
```java
if (userRepository.existsByUsername(request.username())) {
    throw new BadRequestException("Username already taken");       // ← OK: usernames are public
}
if (userRepository.existsByEmail(normalizedEmail)) {
    throw new BadRequestException("Email already registered");     // ← ENUMERABLE
}
```

**Accepted Remediation:**
Keep "Username already taken" specific (usernames are public identifiers visible on blog posts). Genericize only the email check — return a message like "Registration failed — if this email is already in use, please try a different one or use forgot-password." Update the test that validates the specific email error message accordingly.

**Defense-in-depth:** Rate-limit the registration endpoint (addressed in Phase 1C with Bucket4j).

---

### Finding #3: LoginAttemptService Keyed on Username — IP-Blind Lockout Enables DoS

**Vulnerability:** Denial of Service via Account Lockout — Business Logic (A07)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low
**Disposition:** FIX — accepted

**Location:**
- File: LoginAttemptService.java (plan lines 610–638)

**Risk & Exploit Path:**
Brute-force protection keys lockout solely on `username` with no IP component. An attacker who knows a valid username can lock out that user by sending 5 failed login attempts from any IP. This is a classic account lockout DoS vector.

Preconditions: Attacker knows a valid username (usernames are public on the blog platform).
Impact: Targeted denial of service against any user.

**Evidence / Trace:**
```java
private static final String KEY_PREFIX = "login:failures:";  // keyed on username only

public boolean isBlocked(String username) {
    String attempts = redisTemplate.opsForValue().get(KEY_PREFIX + username);  // ← no IP component
    return attempts != null && Integer.parseInt(attempts) >= MAX_ATTEMPTS;
}
```

**Accepted Remediation:**
Use a composite key: `login:failures:{username}:{ip}` — lock out a specific IP's attempts against a specific username. Also add a global per-IP rate limit (e.g., `login:ip:{ip}` with a higher threshold like 20 attempts/hour). Update `LoginAttemptService` method signatures to accept an `ip` parameter, sourced from `HttpServletRequest.getRemoteAddr()` in the controller.

**Defense-in-depth:** The Bucket4j rate limiting in Phase 1C provides additional coverage, but per-endpoint rate limiting alone doesn't prevent targeted username lockout.

---

### Finding #4: DUMMY_HASH Is Not a Valid BCrypt Hash — Timing Mitigation May Be Ineffective

**Vulnerability:** Timing Side-Channel — Cryptographic Failure (A02)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** High
**Disposition:** FIX — accepted

**Location:**
- File: AuthService.java (plan lines 864–865)

**Risk & Exploit Path:**
The dummy hash `$2a$12$dummyhashfortimingequalitypadding000000000000000000000` is not a real BCrypt hash (the 22-character salt and 31-character hash segments use characters outside the BCrypt Base64 alphabet). BCrypt implementations may fast-fail on malformed hashes rather than performing a full comparison, which would *reintroduce* the very timing side-channel this pattern is meant to prevent.

**Evidence / Trace:**
```java
private static final String DUMMY_HASH =
        "$2a$12$dummyhashfortimingequalitypadding000000000000000000000";  // ← INVALID BCrypt
// ...
passwordEncoder.matches(password, DUMMY_HASH);  // ← may fast-fail or throw
```

**Accepted Remediation:**
Generate the dummy hash at application startup using the actual password encoder:
```java
private final String dummyHash;

public AuthService(UserRepository userRepository,
                   PasswordEncoder passwordEncoder,
                   LoginAttemptService loginAttemptService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.loginAttemptService = loginAttemptService;
    this.dummyHash = passwordEncoder.encode("dummy-startup-value");
}
```
This guarantees a valid BCrypt hash that will always perform a full comparison. Also update the test's `DUMMY_HASH` constant to match, or generate it in `@BeforeEach`.

---

### Finding #5: CustomUserDetailsService Returns accountId as Username — Semantic Mismatch

**Vulnerability:** Broken Authentication Logic (A07)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low
**Disposition:** FIX — accepted

**Location:**
- File: CustomUserDetailsService.java (plan lines 250–259)
- File: AuthController.java (plan lines 1150–1151, 1167)

**Risk & Exploit Path:**
`CustomUserDetailsService.loadUserByUsername()` receives a `username` string but returns a `UserDetails` with `accountId.toString()` as the principal name. This means `AuthenticationManager.authenticate()` expects a username as input but produces an authentication token whose `getName()` returns the numeric account ID.

The `AuthController.login()` then does `Long.valueOf(authentication.getName())` — this works, but creates a fragile and confusing contract. More critically, the `loadUserByUsername` lookup uses `findByUsername`, but Spring Security may also call this for session deserialization or remember-me — if the stored principal is an account ID, re-authentication will fail because `findByUsername(accountIdString)` will return empty.

**Evidence / Trace:**
```java
// CustomUserDetailsService — stores accountId as "username" in UserDetails
return new User(
    account.getAccountId().toString(),   // ← accountId, NOT username
    account.getPasswordHash(),
    List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()))
);
```

**Accepted Remediation:**
Keep `username` as the UserDetails username (it's what `loadUserByUsername` receives and what session deserialization expects). In `AuthController`, look up the user by username instead of by ID:
```java
String username = authentication.getName(); // actual username
UserAccount user = userRepository.findByUsername(username).orElseThrow(...);
```
With Finding #1's fix (manual token construction), the `CustomUserDetailsService` is no longer in the login path but should still be corrected for session-related usage:
```java
return new User(
    account.getUsername(),           // ← username, not accountId
    account.getPasswordHash(),
    List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()))
);
```

---

### Finding #6: Login Endpoint Bypasses AuthService Lockout Check via AuthenticationManager

**Vulnerability:** Authentication Bypass — Inconsistent Enforcement (A07)
**Severity:** High
**Confidence:** High
**Attack Complexity:** Low
**Disposition:** FIX — accepted

**Location:**
- File: AuthController.java (plan lines 1134–1163, first version of login before the update)
- Related: AuthController.java (plan lines 1200–1232, updated version)

**Risk & Exploit Path:**
The plan presents **two versions** of the login method. The first version (lines 1134–1163) does NOT call `authService.authenticate()` at all — it goes directly to `authenticationManager.authenticate()`, completely bypassing the `LoginAttemptService` lockout check and failure recording. The plan then says to "update" to the second version, but this is error-prone:

1. An implementer could use the first version and miss the update instruction.
2. The plan provides a complete, compilable first version before the correction — a natural stopping point.
3. The note explaining the need for the update is prose, not code — easily missed.

If the first version is deployed, brute-force protection is completely absent from login.

**Evidence / Trace:**
```java
// FIRST VERSION (lines 1134-1163) — NO lockout check:
@PostMapping("/login")
public ResponseEntity<ApiResponse<AuthResponse>> login(...) {
    Authentication authentication = authenticationManager.authenticate(  // ← NO lockout check
            new UsernamePasswordAuthenticationToken(request.username(), request.password())
    );
    // ... no LoginAttemptService calls at all
}
```

**Accepted Remediation:**
Remove the first version from the plan entirely. Present only the corrected version (which uses `AuthService.authenticate()` + manual token construction per Finding #1's fix) to eliminate ambiguity. This also resolves the double-hashing issue since `AuthenticationManager.authenticate()` is removed from the flow completely.

---

### Finding #7: CSRF Cookie with HttpOnly=false

**Vulnerability:** Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Medium
**Disposition:** ACCEPT — standard SPA pattern

**Location:**
- File: SecurityConfig.java (plan line 326)

**Risk & Exploit Path:**
`CookieCsrfTokenRepository.withHttpOnlyFalse()` is used to allow JavaScript to read the CSRF token — this is standard practice for SPA architectures. However, if an XSS vulnerability exists anywhere in the application, the CSRF token is readable by the attacker's script. This is an accepted trade-off for SPAs but should be documented as a defense-in-depth reduction.

**Accepted Disposition:**
No code change. This is the correct pattern for SPA + session-based auth. Document as accepted risk. Ensure robust XSS prevention (CSP headers, output encoding) when implementing frontend.

---

### Finding #8: No Email Normalization Beyond Lowercase

**Vulnerability:** Input Validation Gap (A03)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium
**Disposition:** DEFER — acceptable for now

**Location:**
- File: AuthService.java (plan line 881)

**Risk & Exploit Path:**
Email normalization only applies `toLowerCase()`. Many email providers treat certain variations as equivalent (e.g., Gmail ignores dots: `user.name@gmail.com` = `username@gmail.com`, and `+` aliases: `user+tag@gmail.com` = `user@gmail.com`). An attacker could register multiple accounts for the same actual email.

This is a business logic concern rather than a direct security vulnerability, but it weakens uniqueness guarantees and could enable abuse (e.g., multiple trial accounts, vote manipulation).

**Accepted Disposition:**
`toLowerCase()` is sufficient for now. Full email canonicalization is complex and provider-specific. Email verification (planned for a later phase) will provide the real defense — unverified accounts should have limited privileges.

---

### Finding #9: `loginCount` Increment Is Not Atomic — Race Condition

**Vulnerability:** Race Condition — TOCTOU (Business Logic)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium
**Disposition:** DEFER — low impact

**Location:**
- File: AuthController.java (plan lines 1155–1159, 1224–1228)

**Risk & Exploit Path:**
Login count is read, incremented in Java, and written back. With `maximumSessions(1)`, concurrent logins are limited, but during the brief window of session creation, two simultaneous requests could read the same count and both write `count+1` instead of `count+2`. The impact is merely inaccurate analytics.

**Evidence / Trace:**
```java
profile.setLoginCount(profile.getLoginCount() + 1);  // ← non-atomic read-modify-write
userRepository.save(user);
```

**Accepted Disposition:**
Defer. Can be fixed during implementation with a JPQL atomic increment if desired:
`@Query("UPDATE UserProfile p SET p.loginCount = p.loginCount + 1, p.lastLogin = :now WHERE p.userAccount.accountId = :userId")`

---

### Finding #10: Swagger/OpenAPI Endpoints Publicly Accessible

**Vulnerability:** Information Disclosure — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low
**Disposition:** FIX — accepted

**Location:**
- File: SecurityConfig.java (plan line 338)

**Risk & Exploit Path:**
`/swagger-ui/**` and `/v3/api-docs/**` are permitted without authentication. In production, this exposes the complete API schema including all endpoints, parameter types, and validation rules — giving attackers a detailed map of the attack surface.

**Evidence / Trace:**
```java
.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
```

**Accepted Remediation:**
Restrict Swagger to dev/staging profiles only. Use `@Profile("dev")` on a separate security config that permits these paths, or use Spring Boot's `springdoc.swagger-ui.enabled=false` in production application.yml.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks

- **Finding #2 + #3 (before fix):** Username enumeration via registration + username-keyed lockout = targeted account DoS. After fixes: usernames are still public (they're on blog posts), but IP-keyed lockout prevents remote DoS. Residual risk: acceptable.

### Implicit Trust Assumptions

- **Resolved by Findings #1 + #6 fix:** The dual authentication path is eliminated. `AuthService.authenticate()` is the single source of truth for credential verification and lockout enforcement.

### Defense-in-Depth Gaps

- If Redis is unavailable, `LoginAttemptService` will throw exceptions. The plan does not specify fallback behavior — this could either lock out all users (if exceptions are treated as "blocked") or disable brute-force protection entirely (if exceptions are caught and treated as "not blocked"). **Recommendation:** During implementation, add a try-catch in `LoginAttemptService.isBlocked()` that logs the Redis failure and returns `false` (fail-open), relying on Bucket4j rate limiting as the secondary defense. Document this as a conscious fail-open decision.

---

## Final Report

### 1. Executive Summary

The Phase 1B auth implementation plan demonstrates solid security awareness: BCrypt with cost factor 12, CSRF protection, session fixation mitigation, brute-force protection, timing side-channel consideration, and input validation via Jakarta Bean Validation. These are the right patterns for a session-based auth system.

The audit identified 10 findings, of which 1 was High severity (ambiguous login flow risking brute-force bypass), 4 were Medium (double hashing, IP-blind lockout, invalid dummy hash, principal mismatch), and 5 were Low. The most impactful cluster was Findings #1/#5/#6, all stemming from the dual authentication path architecture — resolved by choosing `AuthService.authenticate()` as the single path and constructing the Spring Security token manually.

After applying the accepted remediations, the plan will be ready for implementation. The 7 FIX items are all plan-level changes (no running code to patch), and most are Quick Win effort.

### 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Disposition |
|---|-------|----------|----------|------------|-------------|
| 1 | Double Password Hashing on Login | A07 | Medium | Confirmed | FIX |
| 2 | Email Enumeration via Registration | A07 | Low | Confirmed | FIX (partial) |
| 3 | IP-Blind Account Lockout DoS | A07 | Medium | High | FIX |
| 4 | Invalid Dummy BCrypt Hash | A02 | Medium | Medium | FIX |
| 5 | accountId/Username Semantic Mismatch | A07 | Medium | Confirmed | FIX |
| 6 | Ambiguous Login — Brute-Force Bypass Risk | A07 | High | High | FIX |
| 7 | CSRF Cookie HttpOnly=false | A05 | Low | Confirmed | ACCEPT |
| 8 | Incomplete Email Normalization | A03 | Low | Medium | DEFER |
| 9 | Non-Atomic Login Count | Business Logic | Low | Medium | DEFER |
| 10 | Swagger Public in Production | A05 | Low | Confirmed | FIX |

### 3. Security Quality Score (SQS)

**Pre-remediation:**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | Medium | −8 |
| #2 | Low | −2 |
| #3 | Medium | −8 |
| #4 | Medium | −8 |
| #5 | Medium | −8 |
| #6 | High | −20 |
| #7 | Low | −2 |
| #8 | Low | −2 |
| #9 | Low | −2 |
| #10 | Low | −2 |

**Pre-remediation SQS:** 38/100 — Unacceptable

**Post-remediation (after applying accepted fixes for #1–6, #10):**

| Finding | Status | Deduction |
|---------|--------|-----------|
| #1 | Fixed | 0 |
| #2 | Fixed | 0 |
| #3 | Fixed | 0 |
| #4 | Fixed | 0 |
| #5 | Fixed | 0 |
| #6 | Fixed | 0 |
| #7 | Accepted | −2 |
| #8 | Deferred | −2 |
| #9 | Deferred | −2 |
| #10 | Fixed | 0 |

**Post-remediation SQS:** 94/100
**Hard gates triggered:** No
**Posture:** Strong — deploy with standard monitoring

### 4. Positive Security Observations

1. **BCrypt with cost factor 12** — appropriate work factor for password hashing, exceeds the minimum recommended 10.
2. **Timing side-channel awareness** — the dummy hash pattern for non-existent users is the correct approach (just needs a valid hash, Finding #4).
3. **Session fixation protection** — `.sessionFixation().newSession()` and `maximumSessions(1)` are properly configured.
4. **CSRF protection with SPA support** — cookie-based CSRF with `CsrfTokenRequestAttributeHandler` is the correct Spring Security 6.x pattern.
5. **Input validation at the boundary** — Jakarta Bean Validation on DTOs with specific regex patterns for username and password complexity.

### 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #6 — Remove ambiguous first login version | High severity; eliminates brute-force bypass risk | Quick Win | Plan Author |
| 2 | #1 — Single auth path with manual token | Architectural fix; resolves double hashing and cascades into #5/#6 | Moderate | Plan Author |
| 3 | #5 — Fix UserDetails principal to username | Prevents session re-auth breakage; straightforward | Quick Win | Plan Author |
| 4 | #4 — Generate dummy hash at startup | Timing mitigation currently may not work | Quick Win | Plan Author |
| 5 | #3 — Composite key for lockout | Prevents targeted account lockout DoS | Moderate | Plan Author |
| 6 | #2 — Genericize email duplicate message | Prevents email enumeration | Quick Win | Plan Author |
| 7 | #10 — Profile-gate Swagger | Prevents API schema exposure in production | Quick Win | Plan Author |
