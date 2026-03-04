# Critical Design Review: BlogPlatformDB Java Migration (v5.0)

**Reviewed document:** `docs/plans/2026-02-27-java-migration-design.md`
**Review version:** 3
**Date:** 2026-02-28

**Note:** Reviews v1 and v2 covered v3.0 and v4.0 respectively. All issues from both prior reviews have been addressed in v5.0 (payment stub with 501, Markdown content format specified, XSS prevention strategy, async notification error handling, `@Filter` for soft deletes, Vite proxy, audit logging fix, page size limits, log level config, deployment downtime correction). This review focuses on new concerns and residual risks in the v5.0 design.

---

## 1. Overall Assessment

**Strengths:** The v5.0 design is remarkably mature for a project at this stage. The two rounds of critical review have hardened the architecture significantly: the payment stub is properly deferred with a clear Stripe Checkout path, the Markdown pipeline is well-specified with a coherent XSS strategy, the async notification system has proper error handling and batch semantics, and the soft-delete mechanism now correctly supports admin restore. The document is internally consistent, well-organized, and demonstrates disciplined YAGNI thinking throughout. The VPS-first deployment strategy remains pragmatically sound.

**Major concerns:** Three strategic issues remain: (1) the application has no password reset flow and no email verification — meaning a user who forgets their password is permanently locked out, and there is no way to verify email ownership, which enables trivial account squatting; (2) the notification system's scalability ceiling is lower than assumed — polling plus unbounded notification table growth will degrade the notification experience well before the application "outgrows" the VPS; and (3) the `ReadPost` tracking for the "must read before commenting" rule creates a silent data growth problem and has an unclear UX implication for premium content access changes.

---

## 2. Critical Issues

- **No password reset means permanent account lockout for users who forget their password.**
  Password reset is listed under "Deferred Features (YAGNI)" alongside 2FA and OAuth. However, 2FA and OAuth are additive features — the application functions without them. Password reset is a **baseline authentication requirement** — without it, any user who forgets their password has no recovery path. They cannot re-register because the username and email are unique-constrained. The only recovery is manual database intervention by an admin. For a public-facing application with "a few thousand users," this will generate an immediate support burden.
  *Impact:* User lockout with no self-service recovery; admin burden; users perceive the application as unfinished.
  *Suggestion:* Move password reset from "Deferred" to Phase 2 (or late Phase 1). It doesn't require email verification infrastructure — a simple time-limited, single-use reset token sent to the registered email (via a transactional email service like Mailgun, Postmark, or even a free-tier SendGrid account with 100 emails/day) is sufficient. The endpoint is `POST /api/v1/auth/forgot-password` (accepts email, sends token) and `POST /api/v1/auth/reset-password` (accepts token + new password). This is a few hours of work and eliminates a guaranteed support burden.

- **Unbounded notification table growth will degrade query performance over time.**
  Every new post creates one `Notification` row per active subscriber. With 1,000 subscribers and 5 posts/day, that's 5,000 rows/day, 150,000 rows/month, 1.8 million rows/year. The query `findByAccountIdOrderByCreatedAtDesc` with pagination will remain fast for individual users, but the table itself has no retention policy, no archival strategy, and no index beyond the implicit one on `account_id`. More importantly, the "mark all as read" endpoint (`PUT /api/v1/notifications/read-all`) issues an unbounded `UPDATE` — for a user with thousands of accumulated notifications, this is a long-running write transaction that holds row locks.
  *Impact:* Gradual storage growth, increasingly expensive "mark all as read" operations, no mechanism to clean up stale notifications.
  *Suggestion:* (1) Add a composite index on `(account_id, is_read, created_at)` to support efficient filtering of unread notifications. (2) Define a retention policy — e.g., auto-delete read notifications older than 90 days via a scheduled Flyway-managed or cron-based cleanup job. (3) For "mark all as read," scope the UPDATE with a `WHERE created_at > (now - 90 days)` or limit it to unread notifications only (`WHERE is_read = false`), preventing unbounded writes.

- **No email verification enables trivial account squatting and complicates future email-dependent features.**
  Any user can register with any email address, including someone else's. This means: (a) a malicious user can register `ceo@company.com` and squat on that email/username combination, (b) the future password reset feature (suggested above) cannot safely send tokens to unverified emails without risk of sending reset links to the wrong person, and (c) the future Stripe Checkout integration needs a verified email for receipt delivery. Email verification is listed as "Deferred" but it's a dependency for both password reset and payment processing.
  *Impact:* Account squatting, inability to safely implement password reset or payments without first adding email verification retroactively.
  *Suggestion:* If full email verification is too heavy for Phase 1, add a `email_verified BOOLEAN DEFAULT false` column now and enforce verification before allowing password reset or VIP upgrade (Phase 5+). This is a schema-forward decision that avoids a disruptive migration later. Verification itself can be implemented alongside password reset in Phase 2 using the same transactional email infrastructure.

- **Comment threading has no depth limit — deeply nested threads will cause performance and rendering issues.**
  The `Comment` entity has a self-referencing `@ManyToOne` on `parent_comment_id` with no restriction on nesting depth. The front-end `CommentList.tsx` renders threads recursively. Without a depth limit, a pathological (or even organic) chain of replies can produce: (a) a recursive query that loads the entire thread tree eagerly via JPA, causing N+1 query problems or excessive memory usage, and (b) a deeply indented front-end render that becomes unreadable on mobile. Most platforms cap reply depth at 2-3 levels (Reddit shows the full tree but caps indentation; GitHub limits to 1 level; Hacker News limits indentation to ~10 levels).
  *Impact:* Performance degradation on deeply threaded posts; poor mobile UX; potential stack overflow on extreme recursion.
  *Suggestion:* Cap comment nesting at 2-3 levels. When a user replies to a comment at max depth, the reply is attached to the deepest allowed parent (flattening further nesting). Implement this in `CommentService.addComment()` by walking up the parent chain and rejecting or re-parenting if depth exceeds the limit. This is simple, defensive, and avoids a class of issues entirely.

- **The `ReadPost` table will grow unboundedly and its purpose creates UX friction.**
  Every post view by every user creates a `ReadPost` row (composite key: `account_id + post_id`). This is used for the "must read before commenting" business rule. With 1,000 users reading 10 posts/day, that's 10,000 rows/day. Unlike notifications, `ReadPost` rows are never cleaned up and serve no purpose after the user has commented. More importantly, the UX rule itself is unusual and may frustrate users — if a user navigates directly to the comment section (via a link), they cannot comment until they "read" the post, but the design doesn't specify what constitutes "reading" (opening the page? scrolling to the bottom? a timer?). The current implementation appears to mark as read on `GET /api/v1/posts/{id}`, which is simply opening the post — making the rule trivially satisfied and arguably pointless as a quality gate.
  *Impact:* Unbounded table growth for minimal functional value; confusing UX if the rule is ever tightened.
  *Suggestion:* Keep the `ReadPost` tracking (it's useful for analytics — "most read posts"), but reconsider whether the "must read before commenting" rule adds genuine value. If it stays, document explicitly that opening the post page satisfies the requirement. Consider adding a retention policy (e.g., `ReadPost` entries older than 1 year can be pruned, since the commenting gate is only relevant at the time of commenting).

---

## 3. Alternative Architectural Challenge

**Alternative: Replace polling notifications with Server-Sent Events (SSE) and eliminate the Notification table bottleneck.**

Instead of storing notifications as database rows and polling every 30 seconds, use Spring's `SseEmitter` to push notifications directly to connected clients in real-time. When a new post is created, `NotificationService` iterates over connected SSE streams and pushes the notification event. For users who are offline, a small bounded notification queue (last 50 unread per user) is stored in Redis (which is already running for sessions). This eliminates the unbounded `Notification` table, removes the polling overhead (30-second intervals from every logged-in client), and provides instant delivery.

**Pro:** Real-time delivery, no polling overhead, eliminates the unbounded notification table growth problem, uses existing Redis infrastructure for offline buffering.

**Con:** SSE connections are long-lived and consume a thread per connection (unless using Spring WebFlux, which is a larger architectural change); on a single VPS with a few thousand users, this could exhaust the Tomcat thread pool. Also adds complexity compared to the current simple polling model. The current design's polling approach is operationally simpler and sufficient for the expected scale.

---

## 4. Minor Issues & Improvements

- **SSH access hardening not specified.** The firewall section opens port 22 but doesn't mention key-based authentication, `PasswordAuthentication no`, or `fail2ban`. For a public-facing VPS, password-based SSH is the most common attack vector. *Suggestion:* Add a one-liner: "SSH access: key-based only, password authentication disabled, fail2ban installed."

- **No CORS preflight caching.** Every cross-origin POST/PUT/DELETE from the React dev server triggers an OPTIONS preflight request. Without `Access-Control-Max-Age`, the browser sends a preflight before every mutation request, doubling development-mode latency. *Suggestion:* Set `Access-Control-Max-Age: 3600` in the CORS configuration to cache preflight results for 1 hour during development.

- **`UserProfile` as a separate entity from `UserAccount` adds complexity without clear benefit.** The two entities are `@OneToOne` with cascade ALL, meaning they are always created, loaded, and saved together. This 1:1 split creates an extra JOIN on every user query and an extra table to manage. The split appears to be carried over from the SQL Server schema without re-evaluation. *Suggestion:* Consider merging `UserProfile` fields directly into `UserAccount`. If the split is intentional (e.g., to separate authentication data from display data for security), document the rationale explicitly.

- **No request body size limit beyond the 5 MB image upload.** The `spring.servlet.multipart.max-file-size=5MB` applies to multipart uploads, but regular JSON request bodies (post content, comments) have no explicit size limit. A malicious user could submit a multi-megabyte Markdown post body. Spring Boot's default `max-request-size` is effectively unlimited for non-multipart requests (limited only by available memory). *Suggestion:* Add `server.tomcat.max-http-form-post-size` and consider a custom request size filter or a `@Size` annotation on the `content` field of `CreatePostRequest` (e.g., `@Size(max = 100000)` for ~100 KB of Markdown, which is far more than any reasonable blog post).

- **`Subscriber.expiration_date` semantics are unclear.** Subscribers have an `expiration_date` that is nullable (null = never expires). But the design doesn't explain what a "subscription" is in the non-payment context. If VIP payments are deferred, what creates a subscriber with an expiration date? Is subscription free and permanent (null expiration)? If so, why does the field exist? This ambiguity will confuse implementers. *Suggestion:* Clarify that subscription to notifications is free and permanent (expiration_date is always null in Phases 1-4). The field exists for future Phase 5+ scenarios where subscriptions might be tied to VIP status or time-limited trials.

- **Backup verification is manual-only.** The stored procedure validation checklist says "Manual: restore from backup to a test database, verify data integrity" for backup validation. For a production system, untested backups are nearly as risky as no backups. *Suggestion:* Add a monthly automated backup verification step to the cron job setup: restore the latest backup to a temporary Docker PostgreSQL container, run a basic integrity check (row counts on key tables), then destroy the container. This can be a simple shell script.

- **No Flyway baseline or versioning convention documented.** The design mentions Flyway migrations but doesn't specify the naming convention (e.g., `V1__create_user_tables.sql`), the baseline version, or how many migrations are expected for the initial schema. For a greenfield project, a single `V1__initial_schema.sql` is typical, but the Markdown-stripping trigger requires a separate repeatable migration (`R__search_vector_trigger.sql`). *Suggestion:* Document the expected Flyway migration structure: `V1__initial_schema.sql` (all tables, indexes, constraints), `V2__seed_data.sql` (default categories, admin user), `R__search_vector_trigger.sql` (repeatable trigger for tsvector updates).

---

## 5. Questions for Clarification

1. **Is the "must read before commenting" rule a hard business requirement, or is it inherited from the SQL Server stored procedure and open to re-evaluation?** It adds table growth and UX friction for debatable value.

2. **What is the intended user registration flow for the AUTHOR role?** The design shows `Role` as an enum (ADMIN, AUTHOR, USER) but doesn't explain how a user becomes an AUTHOR. Is it admin-assigned? Self-service? Registration-time selection? This affects the admin dashboard scope and the `AuthController` registration logic.

3. **What happens to a user's content (posts, comments) when their account is deleted or deactivated?** The design has no account deletion or deactivation mechanism. GDPR (if EU users are expected) requires a right-to-erasure path. Even without GDPR, users may want to delete their accounts.

4. **Is the 250-character comment limit a firm requirement?** This is quite restrictive — for context, a tweet is 280 characters. If comments are meant to be substantive responses to blog posts, 250 characters may be too short. Consider whether this limit was inherited from the SQL Server schema or is an intentional design choice.

---

## 6. Final Recommendation

**Approve with changes.**

The v5.0 design is production-ready in its architectural choices and has been thoroughly hardened by two rounds of review. The remaining critical issues are not architectural flaws — they are missing baseline features (password reset, notification retention) and unbounded growth risks (ReadPost, Notification tables) that will cause operational pain within the first few months of production use. The comment threading depth limit is a defensive measure that prevents a class of edge-case failures.

**Key changes required before implementation begins:**

1. Move password reset from "Deferred" to Phase 2 — this is a baseline authentication requirement, not an optional feature.
2. Add a notification retention policy and a composite index on `(account_id, is_read, created_at)`.
3. Cap comment nesting depth at 2-3 levels in `CommentService`.
4. Add `@Size(max = 100000)` on blog post content to prevent oversized submissions.
5. Clarify how AUTHOR role is assigned and document the `Subscriber.expiration_date` semantics.
