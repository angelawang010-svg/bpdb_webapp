# Security Audit: Phase 2A Content & CRUD Implementation Plan

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-03-15
**Target:** `docs/plans/phase2a/2026-03-01-phase2a-content-crud-implementation.md` (v3.0)
**Scope:** White-box review of the implementation plan, cross-referenced against existing codebase security architecture (SecurityConfig, RateLimitFilter, OwnershipVerifier, GlobalExceptionHandler, entity classes).

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry points (planned):**
- Category CRUD: `GET/POST/PUT/DELETE /api/v1/categories` (GET public, mutations admin-only)
- Tag CRUD: `GET/POST /api/v1/tags` (GET public, POST admin-only)
- Post CRUD: `GET/POST/PUT/DELETE /api/v1/posts`, `POST/DELETE /api/v1/posts/{id}/save`
- Comments: `GET/POST /api/v1/posts/{id}/comments`, `DELETE /api/v1/comments/{id}`
- Likes: `POST/DELETE /api/v1/posts/{postId}/likes`
- Authors: `GET /api/v1/authors`, `GET /api/v1/authors/{id}`

**Trust boundaries:**
- User input → Spring Validation (`@Valid`) → Service layer → JPA/Hibernate → PostgreSQL stored procedures/constraints
- Authentication: Spring Security session-based, CSRF via cookie
- Authorization: `@PreAuthorize` role checks + `OwnershipVerifier` for resource-level checks
- Rate limiting: Global `RateLimitFilter` (Caffeine cache, per-JVM)

**Sensitive data flows:**
- Post content (Markdown, potentially user-generated), comment content, premium content access
- User identity (accountId) via Authentication principal
- Audit trail (PostUpdateLog with actor attribution)

**Existing protections (confirmed in codebase):**
- BCrypt(12) passwords, session fixation protection, max 1 concurrent session
- CSRF with CookieCsrfTokenRepository
- GlobalExceptionHandler that re-throws Spring Security exceptions (no swallowing)
- OwnershipVerifier with admin bypass

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: Stored XSS via Raw Markdown Content — Deferred Client-Side Sanitization

**Vulnerability:** Stored XSS — A03 (Injection) / A03 overlap (Input Validation & Output Encoding)
**Severity:** High
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Plan, Cross-Cutting Conventions, line 32
- Related: `CreatePostRequest.content` (Task 3, line 473), `CreateCommentRequest.content` (Task 6)

**Risk & Exploit Path:**
The plan explicitly states: *"The backend does not sanitize HTML — server-side sanitization would mangle Markdown syntax. Defense against XSS is the renderer's responsibility."* This is a deliberate architectural decision that shifts the entire XSS defense to the frontend. If any consumer of this API (mobile app, third-party integration, different frontend) fails to use a safe Markdown renderer, stored XSS is trivially exploitable. An attacker submits post content containing `<img src=x onerror=alert(document.cookie)>` or `[link](javascript:void(document.cookie))` — this is stored verbatim and served to all readers.

**Evidence / Trace:**
```
Source: CreatePostRequest.content (user input, up to 100,000 chars)  ← UNTRUSTED
Transform: None — stored raw in blog_post.content
Sink: Any API consumer rendering the content as HTML  ← VULNERABLE if renderer is unsafe
```

**Remediation:**
- **Primary fix:** Add server-side Markdown-to-HTML rendering with a strict HTML sanitizer (e.g., OWASP Java HTML Sanitizer or Jsoup with an allowlist policy) applied *after* Markdown rendering. Return both `contentRaw` (Markdown) and `contentHtml` (sanitized HTML) in `PostDetailResponse`. This eliminates reliance on every client implementing safe rendering.
- **Defense-in-depth:** Set `Content-Security-Policy` response header with strict `script-src` directive. Add `X-Content-Type-Options: nosniff` to prevent MIME sniffing.
- **Minimum acceptable alternative:** If server-side sanitization is truly deferred, document this as a **hard security requirement** for all API consumers and add it to the API contract/OpenAPI spec with a prominent warning. The plan currently buries it in a cross-cutting convention.

**References:**
- CWE-79: Improper Neutralization of Input During Web Page Generation
- OWASP XSS Prevention Cheat Sheet

---

### Finding #2: Full-Text Search SQL Injection Risk in Native Query

**Vulnerability:** SQL Injection — A03 (Injection)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan, Task 3, lines 548–554
- `PostRepository.searchByText()` native query

**Risk & Exploit Path:**
The native query uses `plainto_tsquery('english', :query)` with a Spring `@Param` binding. Spring Data JPA parameterizes `@Param` values in native queries, so the parameter itself is safe from classic SQL injection. However, `plainto_tsquery` is forgiving with input — the risk here is more subtle: if the query parameter is used elsewhere in a dynamically constructed query (e.g., for highlighting, logging, or if a future developer changes `plainto_tsquery` to `to_tsquery` which accepts operators), injection becomes possible.

**Evidence / Trace:**
```java
@Query(value = "SELECT * FROM blog_post WHERE search_vector @@ plainto_tsquery('english', :query) ...",
       nativeQuery = true)
Page<BlogPost> searchByText(@Param("query") String query, Pageable pageable);
// :query is parameterized via PreparedStatement — safe as written  ← OK
// Risk: plainto_tsquery → to_tsquery migration would expose operator injection  ← FUTURE RISK
```

**Remediation:**
- **Primary fix:** Add a `@Size(max = 200)` constraint on the search query parameter at the controller level to limit input size. Add a comment in the repository documenting that `plainto_tsquery` is intentional for safety (strips operators).
- **Defense-in-depth:** If `to_tsquery` is ever needed (for advanced search), sanitize the input to strip tsquery operators (`&`, `|`, `!`, `<->`, parentheses) or use `websearch_to_tsquery` (PostgreSQL 11+) which is user-input-safe.

**References:**
- CWE-89: SQL Injection
- PostgreSQL Full-Text Search documentation

---

### Finding #3: Missing Authorization on Comment Read Endpoint — Information Disclosure for Deleted Posts

**Vulnerability:** Broken Access Control — A01
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Plan, Task 7, line 781
- `GET /api/v1/posts/{id}/comments` — described as "public"

**Risk & Exploit Path:**
The comments endpoint is public and takes a post ID. If a post is soft-deleted (`is_deleted = true`), the post itself is hidden from listings and returns 404 on direct access (via `enableDeletedFilter()`). However, the plan does not specify that the comments endpoint should verify the post is not deleted before returning comments. An attacker could enumerate post IDs and retrieve comments for deleted posts, potentially leaking content that was intentionally removed.

**Evidence / Trace:**
```
GET /api/v1/posts/42/comments  ← post 42 is soft-deleted
CommentController fetches comments by postId  ← no deleted-post check specified
Returns comments for a deleted post  ← INFORMATION DISCLOSURE
```

**Remediation:**
- **Primary fix:** In `CommentController.getComments()` (or `CommentService`), verify the post exists and is not deleted before returning comments. Reuse `PostService.getPost()` or add a `postRepository.existsByIdAndIsDeletedFalse(postId)` check.

---

### Finding #4: IDOR via Sequential/Predictable IDs on Save/Unsave Endpoints

**Vulnerability:** Broken Access Control — A01
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Task 5, lines 669–670
- `POST /api/v1/posts/{id}/save`, `DELETE /api/v1/posts/{id}/save`

**Risk & Exploit Path:**
The save/unsave endpoints use auto-increment post IDs. While the operation itself is scoped to the authenticated user (save *their* bookmark), an attacker can enumerate all post IDs to discover which posts exist, including premium posts or posts in unpublished states. This is a minor information disclosure — the attacker learns post existence but not content.

**Evidence / Trace:**
```
POST /api/v1/posts/1/save → 200 (post exists)
POST /api/v1/posts/999/save → 404 (post doesn't exist)
// Attacker enumerates valid post IDs  ← ENUMERATION
```

**Remediation:**
- **Primary fix:** Ensure save/unsave checks that the post is not deleted before allowing the operation. Consider returning 404 uniformly for both "not found" and "deleted" cases (already implied by soft-delete filter).
- **Defense-in-depth:** This is low-severity given posts are publicly listable. No immediate action required, but note for future if posts gain draft/private states.

---

### Finding #5: Rate Limiting Gap on Spam-Sensitive Endpoints

**Vulnerability:** Security Misconfiguration — A05
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Cross-Cutting Conventions, line 42
- Comment and Like endpoints (Tasks 6-8)

**Risk & Exploit Path:**
The plan explicitly acknowledges this: *"Per-endpoint rate limiting: Deferred to a future phase."* The global rate limit is 120 req/min for authenticated users. An attacker with a valid account can post 120 comments per minute or spam likes/unlikes at 2 requests/second. For a blog platform, this enables:
- Comment spam flooding posts
- Like/unlike toggling to manipulate counts or generate notification spam
- Resource exhaustion on the comment tree-building algorithm (many top-level comments)

**Evidence / Trace:**
```
Global limit: 120 req/min per authenticated user
POST /api/v1/posts/{id}/comments — no per-endpoint limit
Attacker: 120 comments/minute on a single post  ← SPAM
```

**Remediation:**
- **Primary fix:** Add per-endpoint rate limits for comment creation (e.g., 10/min per user) and like operations (e.g., 30/min per user). This can be done with a simple annotation-based approach or by extending the existing `RateLimitFilter`.
- **Minimum acceptable:** Document this as a known risk with a committed timeline. The plan already acknowledges this but provides no timeline.

---

### Finding #6: Comment Depth Re-Parenting Algorithm — Potential Inconsistency with Concurrent Writes

**Vulnerability:** Business Logic / Race Condition — OWASP A08 (Software and Data Integrity)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: Plan, Task 6, lines 749–759
- Comment depth calculation and re-parenting algorithm

**Risk & Exploit Path:**
The depth calculation walks up the in-memory parent chain loaded via `findByIdWithParentChain`. If two users simultaneously reply to the same depth-3 comment, both calculate depth independently. This is not a security vulnerability per se — both will be correctly re-parented — but if the parent comment is deleted (soft-deleted) between the load and save, the new comment references a deleted parent. The `"[deleted]"` placeholder handles display, but the data model allows orphan-like states.

**Remediation:**
- **Primary fix:** The current design handles this acceptably — soft delete preserves rows, and the placeholder rendering covers deleted parents. No action needed beyond awareness.
- **Note:** This is informational — flagged for completeness, not as a blocking issue.

---

### Finding #7: Missing Input Validation on Path Variables

**Vulnerability:** Input Validation — A03 overlap
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Tasks 1-9 (all controllers)
- `@PathVariable Long id` parameters

**Risk & Exploit Path:**
Path variables like `@PathVariable Long id` are auto-parsed by Spring. Non-numeric values return 400 (MethodArgumentTypeMismatchException). Negative values or zero are passed through to JPA, which returns empty results (no entity found), leading to 404. This is handled correctly by the framework. However, the plan does not specify any validation on path variables, and extremely large values (e.g., `Long.MAX_VALUE`) could be used for enumeration probing.

**Remediation:**
- **Primary fix:** Spring's default behavior is adequate. No action needed. Flagged for completeness.

---

### Finding #8: Audit Log Lacks IP Address and User-Agent

**Vulnerability:** Insufficient Logging & Monitoring — A09
**Severity:** Low
**Confidence:** High
**Attack Complexity:** N/A

**Location:**
- File: Plan, Task 4, lines 617–624
- `PostUpdateLog` with `updatedBy` column

**Risk & Exploit Path:**
The audit log records who made changes (`updated_by`) but not from where (IP address, user-agent). In a security incident (e.g., compromised account modifying posts), the audit trail lacks forensic data to distinguish legitimate from malicious activity or correlate with other logs.

**Remediation:**
- **Primary fix:** Add `ip_address VARCHAR(45)` and `user_agent VARCHAR(500)` columns to `post_update_log`. Populate from `HttpServletRequest` in the service layer (pass through from controller or use `RequestContextHolder`).
- **Acceptable deferral:** If this is a future concern, document it. The current plan at least has actor attribution, which is the most critical element.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks

**Chain 1: Comment Spam + Missing Rate Limit → Denial of Service on Comment Tree Building**
Finding #5 (no per-endpoint rate limit) + the comment tree algorithm (3 DB queries per page load) means an attacker can create thousands of top-level comments, degrading page load performance for all users viewing that post. The paginated top-level query mitigates this partially, but the sheer volume of data still grows.

**Chain 2: XSS via Markdown + Comment System → Account Takeover**
Finding #1 (stored XSS in content) applies to both posts *and* comments. If a frontend renders comment content as HTML without sanitization, an attacker can inject JavaScript in a 250-character comment payload. Combined with session-based authentication (cookies), this enables session hijacking. The `HttpOnly` flag on session cookies mitigates cookie theft, but DOM manipulation and phishing within the page remain possible.

### Implicit Trust Assumptions

1. **Frontend is trusted to sanitize Markdown** — This is the most significant trust assumption in the plan. The backend explicitly refuses to sanitize HTML, delegating this entirely to consumers.
2. **OwnershipVerifier trusts `Authentication.getName()` as accountId** — This is safe given Spring Security's authentication chain, but any misconfiguration in the authentication provider could lead to privilege escalation.
3. **`plainto_tsquery` is trusted to be safe** — Correct assumption, but fragile if changed to `to_tsquery`.

### Defense-in-Depth Gaps

1. **No Content-Security-Policy header specified** — The existing SecurityConfig does not appear to set CSP headers. If XSS occurs, there is no browser-level mitigation.
2. **No output encoding at the API level** — JSON serialization by Jackson handles encoding for JSON context, but if any endpoint returns HTML (error pages, redirects), encoding must be verified.

### Deployment Context

Not in scope for this plan (no Dockerfiles or IaC changes in Phase 2A). The existing `RateLimitFilter` uses per-JVM Caffeine cache, meaning rate limits are not shared across instances in a scaled deployment — this is a known limitation noted in the filter's code.

---

## 1. Executive Summary

The Phase 2A implementation plan demonstrates strong security engineering fundamentals. It explicitly addresses TOCTOU race conditions with `DataIntegrityViolationException` catches, uses ownership verification for IDOR prevention, implements idempotent operations via native upserts, and soft-deletes to preserve data integrity. The plan builds on a solid Phase 1 foundation that includes BCrypt hashing, CSRF protection, session management, and global rate limiting.

The most significant security concern is the deliberate decision to store raw Markdown without server-side HTML sanitization (Finding #1). While the rationale is technically sound (sanitizing HTML mangles Markdown), it creates a systemic dependency on every API consumer implementing safe rendering — a fragile assumption that violates defense-in-depth principles. This should be addressed before any production deployment.

The remaining findings are lower severity: a future-proofing concern on full-text search (Finding #2), a gap in comment endpoint authorization for deleted posts (Finding #3), acknowledged lack of per-endpoint rate limiting (Finding #5), and an audit logging enhancement (Finding #8). None of these are blocking for development, but Findings #1, #3, and #5 should be resolved before production.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Stored XSS via raw Markdown — no server-side sanitization | A03 | High | Medium | 2 (posts + comments) | REVIEW |
| 2 | Full-text search native query — future injection risk | A03 | Medium | Medium | 1 | REVIEW |
| 3 | Comments endpoint doesn't check if post is deleted | A01 | Medium | Medium | 1 | FIX |
| 4 | Post ID enumeration via save/unsave | A01 | Low | High | 1 | ACCEPT |
| 5 | No per-endpoint rate limiting on spam-sensitive endpoints | A05 | Medium | High | 3 (comments, likes) | REVIEW |
| 6 | Comment re-parenting race condition (informational) | A08 | Low | Medium | 1 | ACCEPT |
| 7 | No path variable validation (handled by framework) | A03 | Low | High | 9 (all controllers) | ACCEPT |
| 8 | Audit log missing IP/user-agent | A09 | Low | High | 1 | REVIEW |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 1 | −20 |
| Medium | 3 | −24 |
| Low | 4 (grouped) | −2 |

**Final SQS:** 54/100
**Hard gates triggered:** No
**Posture:** Unacceptable — the High-severity XSS finding combined with three Medium findings puts the score below 70. However, this is a *plan* review, not a deployed system. The plan can be amended to address these findings before implementation.

**Important context:** This score reflects the plan *as written*. If Finding #1 is addressed by adding server-side sanitization and Finding #3 is fixed with a deleted-post check, the score rises to 82/100 (Acceptable). If Finding #5 is also addressed, the score reaches 90/100 (Strong).

## 4. Positive Security Observations

1. **TOCTOU race condition handling** — The plan explicitly requires both pre-check (`existsByName`) and `DataIntegrityViolationException` catch on all uniqueness operations. This is above-average security awareness for an implementation plan.
2. **Idempotent operations via native upserts** — `markAsRead()` and `likePost()` use `INSERT ... ON CONFLICT DO NOTHING`, preventing duplicate insert errors and making operations safely repeatable.
3. **Ownership verification with admin bypass** — Consistent use of `OwnershipVerifier.verify()` for all mutation operations (post update/delete, comment delete) with explicit IDOR prevention tests planned.
4. **Soft-delete preserving data integrity** — Both posts and comments use soft-delete, preserving thread structure and preventing cascading data loss. The `"[deleted]"` placeholder pattern for comments is well-designed.
5. **Audit trail with actor attribution** — `PostUpdateLog` captures both old and new values plus `updated_by`, providing a forensic trail for content changes.

## 5. Prioritized Remediation Roadmap

### 1. Finding #1: Stored XSS via Raw Markdown
- **Why prioritized:** High severity, low attack complexity, affects all content rendering, systemic risk
- **Estimated effort:** Moderate — add OWASP Java HTML Sanitizer dependency, create a `MarkdownSanitizer` utility, apply in service layer
- **Suggested owner:** Backend team
- **Action:** Amend plan to add server-side sanitization or, at minimum, add a sanitized HTML field alongside raw Markdown

### 2. Finding #3: Comments Endpoint Doesn't Check Post Deletion
- **Why prioritized:** Medium severity, trivial fix, information disclosure
- **Estimated effort:** Quick Win — add one check in CommentService/Controller
- **Suggested owner:** Backend team
- **Action:** Add `postRepository.existsByIdAndIsDeletedFalse(postId)` check to comment retrieval

### 3. Finding #5: Per-Endpoint Rate Limiting
- **Why prioritized:** Medium severity, high confidence, enables spam and resource abuse
- **Estimated effort:** Moderate — extend RateLimitFilter or add annotation-based limits
- **Suggested owner:** Backend team
- **Action:** Add rate limits for comment creation (10/min) and like operations (30/min) — can be deferred to Phase 2B but should have a committed timeline

### 4. Finding #2: Full-Text Search Input Constraints
- **Why prioritized:** Future-proofing against injection if tsquery function changes
- **Estimated effort:** Quick Win — add `@Size(max=200)` on search parameter, add code comment
- **Suggested owner:** Backend team

### 5. Finding #8: Audit Log Enhancement
- **Why prioritized:** Forensic capability gap, useful for incident response
- **Estimated effort:** Quick Win — add columns to migration, populate from request context
- **Suggested owner:** Backend team
- **Action:** Can be deferred to Phase 2B without security risk if timeline is committed
