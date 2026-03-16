# Security Audit: Phase 3 Front-End Implementation Plan

**Audited File:** `docs/plans/phase3/2026-03-01-phase3-frontend-implementation.md` (v3.0)
**Audit Date:** 2026-03-16
**Auditor:** LCSA (automated security review)
**Scope:** Architecture, code snippets, and security patterns specified in the Phase 3 React SPA implementation plan. This is a plan-level review — no running code was audited.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry points:** 18 React routes — public (home, login, register, post detail, authors) and protected (profile, saved posts, notifications, create/edit post, admin dashboard).

**Trust boundaries:**
1. User browser → React SPA (client-side)
2. React SPA → Vite dev proxy → Spring Boot REST API (`/api/v1`)
3. Spring Boot → PostgreSQL (out of scope — Phase 2)

**Authentication architecture:** Cookie-based sessions via Spring Security. CSRF protection via `XSRF-TOKEN` cookie (read by JS) + `X-XSRF-TOKEN` header. Client-side auth state managed by `AuthContext`. Route-level authorization via `ProtectedRoute` with `requiredRoles` array.

**Sensitive data flows:** Credentials (login/register forms → API), session cookies, CSRF tokens, user PII (profile), post content (Markdown), notification content.

**Technology stack protections:**
- `react-markdown` + `rehype-sanitize` for Markdown rendering (XSS mitigation)
- `withCredentials: true` on Axios (cookie-based auth)
- `validateUser()` for runtime API response validation
- Spring Security CSRF (backend — assumed from plan context)

---

## Pass 2 & 3: Findings

---

### Finding #1: `socialLinks` Rendered Without URL Protocol Validation — Potential `javascript:` XSS

**Vulnerability:** Stored XSS via malicious URL protocol — A03 (Injection)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: Plan Task 2 — `frontend/src/types/user.ts` (type definition)
- Related: Plan Task 15 — `AuthorPage.tsx` (rendering)

**Risk & Exploit Path:**
`AuthorProfile.socialLinks` is typed as `Record<string, string>` — an open-ended key-value map. Task 15 says `AuthorPage` renders "social links." If these are rendered as `<a href={url}>`, an author (or admin via API) who sets a social link value to `javascript:alert(document.cookie)` achieves stored XSS in every visitor's browser.

Preconditions: The back-end must accept arbitrary strings in `socialLinks` (no protocol validation). The front-end must render them as `<a href>` without filtering.

**Evidence / Trace:**
```typescript
// Task 2 — type definition
export interface AuthorProfile {
  // ...
  socialLinks: Record<string, string>;  // ← No URL validation specified
}

// Task 15 — rendering (inferred, not shown in plan)
// Likely: <a href={socialLinks.twitter}>{key}</a>  ← VULNERABLE if javascript: protocol
```

**Remediation:**
- **Primary fix:** Validate URLs before rendering. Only allow `https:` and `http:` protocols:
  ```typescript
  function isSafeUrl(url: string): boolean {
    try {
      const parsed = new URL(url);
      return ['http:', 'https:'].includes(parsed.protocol);
    } catch {
      return false;
    }
  }
  // Render: {isSafeUrl(url) && <a href={url}>...}
  ```
- **Architectural improvement:** Add URL protocol validation on the back-end `AuthorProfile` endpoint as well (defense-in-depth).

---

### Finding #2: `profilePicUrl` Rendered Without URL Validation

**Vulnerability:** Content injection / tracking pixel via arbitrary image URL — A03 (Injection)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2 — `frontend/src/types/user.ts`
- Related: Plan Task 12 — `ProfileCard.tsx` (rendering)

**Risk & Exploit Path:**
`UserProfile.profilePicUrl` is `string | null`. If rendered as `<img src={profilePicUrl}>`, a user who sets their profile picture URL to an attacker-controlled server can track every visitor who views their profile (IP, timing). More critically, if rendered in a context where `onerror` attributes are possible (unlikely with React's JSX, but worth noting), it could enable XSS. In React, `<img src={url} />` is safe from attribute injection, so the primary risk is tracking/SSRF, not XSS.

**Remediation:**
- **Primary fix:** Validate that `profilePicUrl` is either null or matches an allowlisted domain (e.g., the app's own upload endpoint). If user-uploaded avatars go through the backend, enforce this at the API level.
- **Defense-in-depth:** Set a restrictive `Content-Security-Policy` `img-src` directive.

---

### Finding #3: CSRF Priming Failure Silently Swallowed — All Subsequent Mutations Will Fail

**Vulnerability:** Security control bypass / availability degradation — A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 4 — `frontend/src/api/client.ts`, `primeCsrfToken()`
- Related: Plan Task 6 — `AuthContext.tsx`, `Promise.all` initialization

**Risk & Exploit Path:**
`primeCsrfToken()` catches all errors silently. If the `/auth/csrf` endpoint is unreachable (network issue, backend down, misconfigured proxy), the CSRF cookie is never set. Every subsequent POST/PUT/DELETE will receive a 403 from Spring Security. Users see login/register failures with no indication that CSRF priming failed. This is not exploitable by an attacker but creates a confusing failure mode that could lead developers to disable CSRF protection as a "fix."

**Evidence / Trace:**
```typescript
export async function primeCsrfToken(): Promise<void> {
  try {
    await client.get('/auth/csrf');
  } catch {
    // Non-fatal — CSRF cookie may already exist from a prior session.  ← VULNERABLE
    // Silent failure means no CSRF cookie → all mutations fail with 403
  }
}
```

**Remediation:**
- **Primary fix:** Check whether the CSRF cookie exists after the priming attempt. If it doesn't and the request failed, log a warning and optionally show a user-facing error:
  ```typescript
  export async function primeCsrfToken(): Promise<void> {
    try {
      await client.get('/auth/csrf');
    } catch (err) {
      console.warn('CSRF priming failed:', err);
    }
    // Verify cookie was set
    const hasCsrf = document.cookie.includes('XSRF-TOKEN=');
    if (!hasCsrf) {
      console.error('CSRF cookie not set after priming — mutations will fail');
    }
  }
  ```
- **Defense-in-depth:** Add a health-check or connectivity indicator in the UI.

---

### Finding #4: Comment Content Rendering Method Unspecified — Potential Stored XSS

**Vulnerability:** Stored XSS if comments rendered as HTML/Markdown — A03 (Injection)
**Severity:** High (if Markdown) / Informational (if plain text)
**Confidence:** Low — Requires Verification
**Attack Complexity:** Low

**Location:**
- File: Plan Task 10 — `CommentItem.tsx`, `CommentForm.tsx`

**Risk & Exploit Path:**
The plan explicitly specifies `react-markdown` + `rehype-sanitize` for **post content** (Task 10, Task 11) but does not specify how **comment content** is rendered. Comments are typed as `content: string` with a 250-char limit. If comments are rendered via `react-markdown` (or worse, `dangerouslySetInnerHTML`), an attacker can inject malicious Markdown/HTML in comments visible to all readers.

If comments are rendered as plain text (React's default `{comment.content}` in JSX), this is not a vulnerability.

**Remediation:**
- **Primary fix:** Explicitly specify in the plan that comment `content` is rendered as **plain text** (React's default JSX text interpolation), OR if Markdown is desired, explicitly require `react-markdown` + `rehype-sanitize` for comment rendering as well.
- Add a test case: render a comment containing `<script>alert(1)</script>` and verify the script tag is not in the DOM.

---

### Finding #5: No Content Security Policy (CSP) Specified

**Vulnerability:** Missing defense-in-depth — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: Plan-wide — no task addresses CSP headers

**Risk & Exploit Path:**
The plan does not mention Content Security Policy headers. While `react-markdown` + `rehype-sanitize` mitigates XSS in Markdown rendering, CSP provides a critical second layer. Without CSP, any XSS that bypasses sanitization (e.g., a `rehype-sanitize` bug) can load external scripts, exfiltrate data, or inject iframes freely.

**Remediation:**
- **Primary fix:** Add a CSP meta tag in `index.html` or configure CSP headers via the Vite dev server / production reverse proxy (Phase 4):
  ```
  Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https:; connect-src 'self'
  ```
- **Architectural improvement:** This is best addressed in Phase 4 (deployment) via Nginx headers, but a `<meta>` tag in `index.html` provides immediate protection during development.

---

### Finding #6: Client-Side Role Authorization Without Explicit Backend Enforcement Verification

**Vulnerability:** Potential broken access control — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Low — Requires Verification
**Attack Complexity:** Low

**Location:**
- File: Plan Task 7 — `ProtectedRoute.tsx`
- Related: Plan Task 14 — `AdminDashboard.tsx`

**Risk & Exploit Path:**
`ProtectedRoute` checks `user.role` client-side to gate access to admin and author routes. Client-side checks are trivially bypassed (modify localStorage, replay API calls directly). The plan states "Back-end prerequisite: Phase 2 must be complete" but does not explicitly verify that the back-end enforces role-based authorization on admin endpoints (`/api/v1/admin/*`, `/api/v1/categories`, etc.) and author-only endpoints (`POST /api/v1/posts`).

If the back-end relies on the front-end for role enforcement, any user can call admin APIs directly.

**Remediation:**
- **Primary fix:** Add a verification step (e.g., in Task 14 or Task 18) that confirms back-end endpoints return 403 for unauthorized roles. Example Cypress test:
  ```typescript
  it('non-admin cannot access admin endpoints', () => {
    cy.loginAs('regularuser');
    cy.request({ url: '/api/v1/admin/users', failOnStatusCode: false })
      .its('status').should('eq', 403);
  });
  ```
- This is likely already handled by Phase 2's Spring Security configuration, but the plan should explicitly verify it.

---

### Finding #7: Notification Polling Continues Generating 401s After Session Expiry

**Vulnerability:** Information leakage / unnecessary server load — A07 (Authentication)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** N/A

**Location:**
- File: Plan Task 13 — `useNotifications.ts`

**Risk & Exploit Path:**
`useNotifications` polls every 30 seconds with `retry: (failureCount) => failureCount < 3`. When a session expires, each poll returns 401, triggering the `onUnauthorized` callback which sets `user` to `null`. However, if the component remains mounted during the brief window before `ProtectedRoute` redirects, up to 3 retries with exponential backoff fire. More importantly, `refetchInterval: 30_000` will restart polling after the retry cycle, generating repeated 401s.

The 401 interceptor calls `setUser(null)` which should trigger `ProtectedRoute` to redirect, unmounting the component and stopping the query. This likely self-corrects quickly, but the plan should explicitly address this.

**Remediation:**
- **Primary fix:** Add `enabled: !!user` to the `useQuery` options so polling only runs when authenticated:
  ```typescript
  export function useNotifications() {
    const { user } = useAuth();
    return useQuery({
      queryKey: ['notifications'],
      queryFn: () => notificationsApi.getNotifications(),
      enabled: !!user,  // ← Stop polling when logged out
      refetchInterval: 30_000,
      // ...
    });
  }
  ```

---

### Finding #8: E2E Test Uses Weak Hardcoded Password

**Vulnerability:** Weak test credentials — A07 (Authentication)
**Severity:** Informational
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: Plan Task 17 — `cypress/e2e/auth.cy.ts`

**Risk & Exploit Path:**
The E2E test uses `Password1` as a test password. While this is a test-only concern, if the same credential pattern leaks into staging/production seed data or if the test runs against a shared environment, it becomes a real credential.

**Remediation:**
- Use a stronger test password (e.g., `E2eT3st!Pass2026`) and ensure test accounts are cleaned up after test runs.

---

## 1. Executive Summary

The Phase 3 front-end implementation plan demonstrates **strong security awareness** for a React SPA. The architecture correctly uses `react-markdown` + `rehype-sanitize` for Markdown rendering, implements CSRF protection via the standard Spring Security cookie-to-header pattern, validates API responses at runtime, and uses a well-designed callback-based 401 handling pattern that avoids infinite redirect loops.

The most concerning pattern is the **unspecified rendering method for user-generated content** beyond post bodies — specifically comment content (Finding #4) and social links / profile URLs (Findings #1, #2). While React's default JSX rendering escapes text content, the plan should be explicit about these choices to prevent a future implementer from introducing `dangerouslySetInnerHTML` or rendering untrusted URLs as `href` values.

No critical vulnerabilities were identified. The findings are primarily **defense-in-depth gaps and ambiguities** that should be resolved before implementation to prevent security regressions.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `socialLinks` without URL protocol validation | A03 | Medium | Medium | 1 | FIX |
| 2 | `profilePicUrl` without URL validation | A03 | Low | Medium | 1 | FIX |
| 3 | CSRF priming failure silently swallowed | A05 | Medium | High | 1 | FIX |
| 4 | Comment rendering method unspecified | A03 | High* | Low | 1 | VERIFY |
| 5 | No CSP specified | A05 | Low | Confirmed | 1 | FIX |
| 6 | Client-side role checks without backend verification | A01 | Medium | Low | 1 | VERIFY |
| 7 | Notification polling after session expiry | A07 | Low | High | 1 | FIX |
| 8 | Weak E2E test password | A07 | Info | Confirmed | 1 | FIX |

\* Finding #4 severity is High if comments are rendered as Markdown/HTML, Informational if plain text.

## 3. Security Quality Score (SQS)

**Calculation (starting at 100):**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 socialLinks XSS | Medium | −8 |
| #2 profilePicUrl | Low | −2 |
| #3 CSRF silent failure | Medium | −8 |
| #4 Comment rendering | High (worst case) | −20 |
| #5 No CSP | Low | −2 |
| #6 Client role checks | Medium | −8 |
| #7 Notification polling | Low | −2 |
| #8 Test password | Info | −1 |

**Total deductions:** −51 (worst case with Finding #4 as High) or −31 (if Finding #4 is Informational)

**Final SQS:** 69/100 (worst case) or 89/100 (best case, if comments are plain text)
**Hard gates triggered:** No (no confirmed Critical findings, no hardcoded secrets in application code)
**Posture:** **Acceptable** (worst case) to **Strong** (best case) — depends on Finding #4 resolution

> **Recommendation:** Resolve Finding #4 ambiguity before implementation. If comments are plain text, the plan's security posture is **Strong**. If comments use Markdown rendering, add `rehype-sanitize` and the score remains Acceptable pending the fix.

## 4. Positive Security Observations

1. **Markdown sanitization via `rehype-sanitize`** — The plan explicitly mandates `react-markdown` + `rehype-sanitize` for post content and explicitly prohibits `dangerouslySetInnerHTML`. This is the correct approach.

2. **CSRF implementation follows Spring Security best practices** — The cookie-to-header pattern with `XSRF-TOKEN` / `X-XSRF-TOKEN`, combined with a dedicated priming endpoint, correctly handles Spring Security 6's deferred CSRF token loading.

3. **Callback-based 401 handling prevents redirect loops** — The `setOnUnauthorized` pattern decouples session detection from redirect logic, with `ProtectedRoute` as the single redirect owner. This is a well-thought-out design that avoids a common SPA pitfall.

4. **Runtime API response validation** — `validateUser()` catches backend contract drift at the trust boundary, particularly for the security-critical `role` field. This prevents type-confusion attacks where a malformed API response could grant unintended permissions.

5. **Optimistic updates with rollback** — The `useLike`/`useSave` hooks properly cancel in-flight queries, cache previous state, and roll back on error — preventing race conditions and inconsistent UI state.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #4 — Comment rendering | Ambiguity with highest potential severity. If unresolved, an implementer may introduce stored XSS. One sentence in the plan fixes it. | Quick Win | Frontend |
| 2 | #1 — socialLinks URL validation | Concrete stored XSS vector via `javascript:` URLs. Requires a small utility function. | Quick Win | Frontend |
| 3 | #3 — CSRF priming error handling | Silent failure leads to all mutations failing with no user feedback. Add cookie existence check. | Quick Win | Frontend |
| 4 | #6 — Backend auth verification | Add E2E tests confirming backend 403s for unauthorized roles. May already be covered by Phase 2 but should be explicitly verified. | Moderate | Frontend + Backend |
| 5 | #5 — CSP headers | Best addressed in Phase 4 (Nginx config) but a `<meta>` tag can be added now. | Quick Win | Frontend / DevOps |
