# Security Audit: Phase 1C — Rate Limiting, Entities & Verification

**Audit Date:** 2026-03-03
**Auditor:** LCSA (Claude)
**Target:** `docs/plans/2026-03-01-phase1c-ratelimit-entities-implementation.md` (v1.2)
**Scope:** Plan review — code snippets for Tasks 11–15 (rate limiting filter, ownership verifier, 16 JPA entities, auth flow integration test, verification steps)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry Points:**
- `RateLimitFilter` — servlet filter on all `/api/` requests, inspects SecurityContext, resolves client IP
- `OwnershipVerifier` — authorization helper called by future service layer code
- 16 JPA entities — define the data model persisted via Hibernate/JPA
- `AuthFlowIT` — integration test exercising the full auth lifecycle

**Trust Boundaries:**
- HTTP request → RateLimitFilter → Spring Security → Controllers → Service → JPA → PostgreSQL
- Client IP comes from `request.getRemoteAddr()` (trusted only behind properly configured reverse proxy)
- SecurityContext populated by Spring Security before RateLimitFilter (`@Order(1)` runs after Security at order `-100`)

**Data Flows of Note:**
- `AuthorProfile.socialLinks` — user-supplied JSON stored as JSONB, rendered on frontend
- `Image.imageUrl` — URL string stored in DB, rendered as `<img src>` or `<a href>` on frontend
- `Notification.message` — server-generated TEXT, displayed to users
- `BlogPost.content` — Markdown TEXT up to 100KB, rendered via react-markdown

**Technology Stack Protections:**
- Spring Security session-based auth with Redis backing
- Hibernate `ddl-auto: validate` ensures entity-schema alignment
- Bean Validation (`jakarta.validation`) on entity fields
- Bucket4j for rate limiting with Caffeine local cache

---

## Pass 2 & 3: Findings

---

### Finding #1: AuthorProfile socialLinks — No Validation on JSONB Content

**Vulnerability:** Stored XSS via URL Scheme Injection — A03 (Injection) / A01 (Broken Access Control overlap)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `backend/src/main/java/com/blogplatform/author/AuthorProfile.java` (plan lines 1030–1032)

**Risk & Exploit Path:**
The `socialLinks` field is a `Map<String, String>` stored as JSONB with zero validation on keys or values. When the frontend renders author profiles, it will likely create `<a href="...">` links from these values. An attacker with AUTHOR role can store `javascript:alert(document.cookie)` or `data:text/html,...` as a social link value. Any user viewing the author's profile clicks a link and executes attacker-controlled JavaScript.

Preconditions: Attacker has AUTHOR role. Frontend renders social links as clickable anchors without scheme validation.

**Evidence / Trace:**
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "social_links", columnDefinition = "jsonb")
private Map<String, String> socialLinks;  // ← NO @Size on map, NO validation on values
```

Source: Author profile update request → `socialLinks` map → PostgreSQL JSONB → Frontend `<a href>` rendering → XSS

**Remediation:**
- Primary fix: Add a DTO-level validator for the author profile update endpoint that validates: (a) map size ≤ 10 entries, (b) keys match an allowlist (`twitter`, `github`, `linkedin`, `website`, etc.) or are `@Size(max=30)`, (c) values match `@URL` or `@Pattern(regexp = "^https?://.*")` to restrict to HTTP(S) schemes only.
- Defense-in-depth: Frontend should also validate URL schemes before rendering as `href` (allowlist `https://` only).

---

### Finding #2: Image imageUrl — No URL Format or Scheme Validation

**Vulnerability:** Stored XSS via Malicious URI Scheme — A03 (Injection)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `backend/src/main/java/com/blogplatform/image/Image.java` (plan lines 1307–1308)

**Risk & Exploit Path:**
The `imageUrl` field has only `@Size(max = 255)` — no format or scheme validation. If any future endpoint allows users to supply image URLs (e.g., linking external images), an attacker can store `javascript:...` or `data:image/svg+xml;base64,...` payloads. When the frontend renders `<img src="...">` or `<a href="...">` for image links, XSS executes.

Note: If images are always server-generated URLs from upload, this risk is lower (server controls the value). But the entity doesn't enforce this invariant.

**Evidence / Trace:**
```java
@Size(max = 255)
@Column(name = "image_url", unique = true)
private String imageUrl;  // ← No @URL or @Pattern validation
```

**Remediation:**
- Primary fix: Add `@Pattern(regexp = "^/uploads/.*|^https?://.*")` to restrict to relative upload paths or HTTP(S) URLs. Better yet, if images are always uploaded via the application, enforce a `/uploads/` prefix pattern.
- Architectural: Ensure the image upload service is the only code path that sets `imageUrl` — never accept user-supplied URLs directly.

---

### Finding #3: Notification message — Unbounded TEXT Without Size Constraint

**Vulnerability:** Unrestricted Resource Consumption — A04 (Insecure Design)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `backend/src/main/java/com/blogplatform/notification/Notification.java` (plan lines 1243–1244)

**Risk & Exploit Path:**
The `message` field has only `@NotBlank` with no `@Size` constraint. While notifications are typically server-generated (not directly user-controlled), if any notification path interpolates user content (e.g., "User X commented: {comment_text}"), an attacker with a 250-char comment could generate large notification messages if the template expands. More importantly, a bug or future code change could allow unbounded message creation, leading to storage bloat.

**Evidence / Trace:**
```java
@NotBlank
@Column(name = "message", nullable = false, columnDefinition = "TEXT")
private String message;  // ← No @Size limit
```

**Remediation:**
- Primary fix: Add `@Size(max = 1000)` (or appropriate limit for notification messages).

---

### Finding #4: Rate Limit Caffeine Cache Eviction Enables Bucket Reset

**Vulnerability:** Rate Limit Bypass via Cache Eviction — A04 (Insecure Design)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `backend/src/main/java/com/blogplatform/config/RateLimitFilter.java` (plan lines 84–87)

**Risk & Exploit Path:**
The Caffeine cache is capped at 100,000 entries with LRU eviction. An attacker with access to a large IP pool (cloud instances, rotating proxies) can:
1. Send requests from >100K unique IPs, filling the cache
2. Cause eviction of legitimate users' rate limit buckets
3. Evicted users get fresh buckets on next request (full capacity reset)

This doesn't directly bypass the attacker's own rate limits (each new IP gets a fresh bucket with full capacity — but only one bucket per IP), but it does reset other users' accumulated rate limit state. For authenticated user buckets (keyed by username), eviction means a heavy user who was approaching their limit gets reset.

Preconditions: Attacker controls >100K unique source IPs. This is a high barrier for most attackers.

**Evidence / Trace:**
```java
private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
        .maximumSize(100_000)          // ← LRU eviction at capacity
        .expireAfterAccess(Duration.ofMinutes(5))
        .build();
```

**Remediation:**
- Primary fix: Acceptable for single-VPS deployment. Document the limitation.
- Architectural: For production under attack, migrate to Redis-backed Bucket4j (already noted in plan). Redis doesn't have the eviction-resets-bucket problem since you can use `EXPIRE` with proper TTLs.
- Defense-in-depth: Add monitoring/alerting on cache size approaching capacity.

---

### Finding #5: ReadPost and SavedPost Entities Missing equals/hashCode

**Vulnerability:** Data Integrity Issue — not a direct security vulnerability but can cause authorization bypass in edge cases
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `backend/src/main/java/com/blogplatform/post/ReadPost.java` (plan lines 766–791)
- File: `backend/src/main/java/com/blogplatform/post/SavedPost.java` (plan lines 840–866)

**Risk & Exploit Path:**
All other entities in the plan implement `equals()`/`hashCode()`. ReadPost and SavedPost (composite-key entities using `@IdClass`) do not. When Hibernate manages these entities in collections (e.g., checking if a user has already read a post), identity comparison falls back to object reference equality. This can cause:
- Duplicate entries in Sets/Collections
- `contains()` checks returning false for logically equal entities
- If "has read post" is a security gate for commenting (as the design doc implies), a false negative could incorrectly deny access.

**Evidence / Trace:**
```java
@Entity
@Table(name = "read_posts")
@IdClass(ReadPostId.class)
public class ReadPost {
    // ... fields and getters/setters ...
    // ← NO equals() or hashCode() override
}
```

Contrast with every other entity:
```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Category that)) return false;
    return id != null && id.equals(that.id);
}
```

**Remediation:**
- Primary fix: Add `equals()`/`hashCode()` to both ReadPost and SavedPost, delegating to their composite key fields:
```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ReadPost that)) return false;
    return account != null && post != null
        && Objects.equals(account.getId(), that.account != null ? that.account.getId() : null)
        && Objects.equals(post.getId(), that.post != null ? that.post.getId() : null);
}

@Override
public int hashCode() {
    return Objects.hash(
        account != null ? account.getId() : null,
        post != null ? post.getId() : null
    );
}
```

---

## 1. Executive Summary

Phase 1C's implementation plan is **well-structured and security-conscious**. The rate limiting filter correctly orders after Spring Security, uses `auth.getName()` rather than casting principals, and the OwnershipVerifier provides a clean IDOR prevention pattern. The plan has already been through two rounds of critical review that addressed significant issues (IP spoofing notes, anonymous user detection, equals/hashCode on most entities).

The remaining findings are moderate: two stored XSS vectors via unvalidated URL/URI fields (socialLinks, imageUrl) that depend on frontend rendering behavior, one unbounded text field, a theoretical cache eviction attack, and two entities missing equals/hashCode. None are blocking for Phase 1 deployment since the affected entities don't have CRUD endpoints yet — but they **must be addressed before Phase 2** when these entities get API exposure.

The most business-critical finding is #1 (socialLinks validation) because it creates a stored XSS path the moment author profile editing is implemented.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | AuthorProfile socialLinks — No JSONB content validation | A03 | Medium | High | 1 | FIX BEFORE PHASE 2 |
| 2 | Image imageUrl — No URL scheme validation | A03 | Medium | High | 1 | FIX BEFORE PHASE 2 |
| 3 | Notification message — Unbounded TEXT | A04 | Low | High | 1 | FIX BEFORE PHASE 2 |
| 4 | Rate limit cache eviction enables bucket reset | A04 | Low | Medium | 1 | ACCEPT |
| 5 | ReadPost/SavedPost missing equals/hashCode | Integrity | Low | High | 2 | FIX |

## 3. Security Quality Score (SQS)

**Calculation:**
- Starting: 100
- 2 × Medium: −16
- 3 × Low: −6
- **Total deductions: −22**

**Final SQS:** 78/100
**Hard gates triggered:** No
**Posture:** Acceptable — deploy Phase 1 with remediation commitment for Phase 2 preparation

## 4. Positive Security Observations

1. **Correct filter ordering**: `@Order(1)` on RateLimitFilter runs after Spring Security (`-100`), so SecurityContext is properly populated for authenticated user detection.
2. **Proper anonymous user detection**: Uses `!"anonymousUser".equals(auth.getName())` rather than just checking `auth != null && auth.isAuthenticated()`, which correctly handles Spring Security's anonymous authentication token.
3. **OwnershipVerifier null safety**: Handles null authentication and null principal gracefully, preventing NPEs in authorization checks.
4. **Consistent equals/hashCode pattern**: 11 of 13 entities correctly implement the Hibernate-safe `id != null && id.equals(that.id)` pattern with `getClass().hashCode()`, following Vlad Mihalcea's recommendations.
5. **Tiered rate limiting by context**: Auth endpoints get the strictest limit (10/min), preventing credential stuffing, while authenticated users get the most generous limit (120/min). Key isolation (IP vs username) is correctly applied per tier.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — socialLinks validation | Stored XSS vector, exploitable the moment author profiles get an edit endpoint | Quick Win | Backend |
| 2 | #2 — imageUrl scheme validation | Same class of vulnerability, simpler fix (add @Pattern) | Quick Win | Backend |
| 3 | #5 — ReadPost/SavedPost equals/hashCode | Data integrity issue, trivial fix, should match other entity conventions | Quick Win | Backend |
| 4 | #3 — Notification message @Size | Defense-in-depth, 1-line fix | Quick Win | Backend |
| 5 | #4 — Cache eviction documentation | Acceptable risk for Phase 1, document and monitor | Quick Win | Backend/DevOps |
