# Critical Design Review: BlogPlatformDB Java Migration (v4.0)

**Reviewed document:** `docs/plans/2026-02-27-java-migration-design.md`
**Review version:** 2
**Date:** 2026-02-28

**Note:** Review v1 covered the v3.0 design. All six critical issues from that review have been addressed in v4.0 (greenfield declaration, Redis sessions, async notifications, SP validation checklist, rate limiting, image upload constraints). This review focuses on new concerns and issues introduced or exposed by the v4.0 changes.

---

## 1. Overall Assessment

**Strengths:** The v4.0 design is substantially improved. The async notification architecture, rate limiting strategy, image upload constraints, and stored procedure validation checklist all demonstrate disciplined engineering. The VPS-first approach remains pragmatic, and the greenfield declaration eliminates the data migration risk entirely. The document is thorough, internally consistent, and well-organized for its scale.

**Major concerns:** The design now has two strategic gaps that could cause significant production pain: (1) the payment system is architecturally incomplete — it records payments but has no actual payment gateway, meaning it either cannot process real money or implicitly trusts client-submitted payment data, which is a critical security flaw; and (2) the blog content format is never specified (plain text? Markdown? HTML?), which is a foundational decision that affects the editor, rendering pipeline, full-text search accuracy, storage size, and XSS attack surface. Additionally, the choice of Redis over JDBC for session storage on a single VPS introduces unnecessary infrastructure complexity that contradicts the project's pragmatic philosophy.

---

## 2. Critical Issues

- **Payment processing has no real payment gateway integration.**
  `PaymentService.upgradeToVip()` creates a `Payment` record with `amount`, `payment_method`, and `transaction_id`, but the design never mentions Stripe, PayPal, or any payment processor. As written, the service either (a) trusts the client to submit truthful payment details (the client sends an amount and method, and the server records it — no money changes hands), or (b) is a placeholder that will need significant rearchitecting later. Option (a) is a critical security flaw — any authenticated user can "pay" $0 or fabricate a transaction ID. Option (b) means the VIP upgrade feature is not implementable as designed.
  *Impact:* Either a security vulnerability (fake payments) or a blocked feature (VIP upgrades don't work).
  *Suggestion:* Either (1) integrate a real payment gateway (Stripe Checkout is the simplest — redirect to Stripe, receive a webhook confirmation, then set VIP flags server-side in the webhook handler), or (2) explicitly defer VIP payments to a future phase and remove the payment endpoints from Phases 1-4. Do not ship a payment endpoint that records client-submitted payment data without server-side verification against a payment processor.

- **Blog post content format is unspecified — this is a foundational architectural decision.**
  The `BlogPost.content` field is `TEXT` but the design never states whether content is plain text, Markdown, or raw HTML. This single decision cascades through the entire stack: the front-end editor (textarea vs. Markdown editor vs. rich text/WYSIWYG), the rendering pipeline (render as-is vs. parse Markdown vs. sanitize HTML), the full-text search indexing (`tsvector` on raw Markdown/HTML will index formatting syntax as search terms), XSS prevention (storing raw HTML from users is dangerous; Markdown is safer but still needs sanitization on render), and storage efficiency (HTML is 2-5x larger than equivalent Markdown).
  *Impact:* Without this decision, the front-end team cannot build the post editor, the search index will produce poor results, and the XSS strategy is undefined.
  *Suggestion:* Specify Markdown as the content format. Store raw Markdown in the database, render to sanitized HTML on the front-end (using a library like `react-markdown` with `rehype-sanitize`). For `tsvector` indexing, strip Markdown syntax before indexing (a Flyway trigger can do this). This gives authors a clean writing experience, keeps storage compact, avoids storing raw HTML, and makes search results relevant.

- **Redis for sessions on a single VPS is over-engineered — contradicts the project's pragmatic philosophy.**
  The v1 review suggested JDBC-backed sessions (PostgreSQL), but the design chose Redis instead, adding a fourth Docker container (~50 MB RAM), a new point of failure, and an additional service to monitor and back up. For a single-server deployment serving a few thousand users, PostgreSQL-backed sessions via Spring Session JDBC are simpler (one dependency, one table, zero new infrastructure), equally persistent across restarts, and already battle-tested. Redis is the right choice for the AWS multi-container phase (ElastiCache), but on a single VPS, the PostgreSQL database is already running and underutilized.
  *Impact:* Increased operational complexity, additional failure mode (if Redis crashes, all users are logged out despite PostgreSQL being healthy), and increased memory footprint on a small VPS.
  *Suggestion:* Use Spring Session JDBC (PostgreSQL-backed) for Phases 1-4. Switch to Redis (ElastiCache) only in Phase 5 when horizontal scaling requires a shared external session store. This aligns with the project's YAGNI philosophy and reduces the VPS container count from 4 to 3.

- **No XSS prevention strategy for user-generated content.**
  Phase 4 mentions "input sanitization for XSS prevention" as a bullet point but provides no actual strategy. React escapes JSX by default, but this only protects against XSS in component text. If blog post content or comments are rendered using `dangerouslySetInnerHTML` (which is required if content is HTML or rendered Markdown), React's default escaping is bypassed entirely. The design also doesn't specify output encoding, Content-Security-Policy headers, or a sanitization library.
  *Impact:* Stored XSS via blog posts or comments — an attacker posts content with embedded `<script>` tags, and every reader executes the payload.
  *Suggestion:* (1) Never use `dangerouslySetInnerHTML` on unsanitized content. (2) If using Markdown, sanitize the rendered HTML with `DOMPurify` or `rehype-sanitize` on the front-end. (3) Add a `Content-Security-Policy` header via Nginx that disallows inline scripts (`script-src 'self'`). (4) On the back-end, validate that comment text contains no HTML tags (comments are plain text, 250 chars, no reason to allow markup).

- **`@Async` notifications have no error handling, retry, or observability.**
  Making notifications async via `@TransactionalEventListener` + `@Async` is the right decoupling approach, but the design doesn't address failure modes. If the async thread throws an exception (database connection exhausted, constraint violation, OutOfMemoryError), notifications are silently lost. There is no retry mechanism, no dead-letter logging, and no way to detect that notifications weren't created. With potentially thousands of subscriber notifications per post, partial failures are likely.
  *Impact:* Silent notification loss with no way to detect or recover.
  *Suggestion:* (1) Add a try-catch in `notifySubscribers()` that logs failures (post ID, subscriber count, error) at ERROR level. (2) Consider batch-inserting notifications (one `saveAll()` call instead of N individual saves) to reduce the chance of partial failure. (3) If a batch fails, log the post ID so an admin can manually trigger re-notification. This doesn't require a message queue — just defensive coding and logging.

---

## 3. Alternative Architectural Challenge

**Alternative: Defer the SPA — Build an API-first Back-End with a Minimal Server-Rendered Admin UI**

Instead of building the full React SPA in Phase 3, build only the Spring Boot API (Phases 1-2) and a minimal Thymeleaf-based admin interface for content management. Expose the API publicly and let the front-end be built later (or by a different team) once the API is stable and validated.

- **Pro:** Cuts the initial delivery scope roughly in half. The API can be validated independently via Swagger/OpenAPI, integration tests, and direct API consumers (mobile apps, third-party clients). The admin UI covers the most critical workflow (authors creating and managing posts) without the complexity of a full SPA. The React front-end can be built in a separate phase once the API contract is proven stable, reducing rework risk.

- **Con:** No public-facing web UI at launch — users can't browse posts in a browser without the React app (unless a minimal read-only Thymeleaf view is added). This delays the "working product" milestone and may not be acceptable if a web UI is required for the initial launch. Also splits the front-end work into two efforts (admin Thymeleaf + public React).

---

## 4. Minor Issues & Improvements

- **`@Where(clause = "is_deleted = false")` is a Hibernate-specific anti-pattern for soft delete.** This annotation makes soft-deleted posts invisible at the ORM level — including for admin queries that need to view or restore deleted content. If an admin dashboard needs a "deleted posts" view (which is implied by soft delete being chosen over hard delete), `@Where` must be bypassed via native queries. Consider using a default Spring Data JPA `Specification` or a custom `@FilterDef`/`@Filter` that can be toggled, rather than a global `@Where` that cannot be disabled per-query.

- **`SameSite` cookie attribute is not specified for session cookies.** Modern browsers default `SameSite` to `Lax`, which blocks cookies on cross-origin POST requests. During development, the React dev server (localhost:5173) and Spring Boot (localhost:8080) are different origins. Login POST requests from the React dev server may not send the session cookie back, causing authentication to silently fail. The design should specify `SameSite=Lax` for production (same origin via Nginx) and `SameSite=None; Secure` for development (cross-origin), or configure Vite to proxy API requests to avoid cross-origin issues entirely.

- **No email or notification channel for critical alerts.** Monitoring relies on Docker health checks, log viewing, and UptimeRobot pings. If the database fills up, backups fail, or disk hits 70%, the cron job "alerts" — but to where? No email, Slack webhook, or PagerDuty integration is specified. A simple solution: pipe cron job output to a free email relay (e.g., `msmtp` + Gmail) or a free Slack incoming webhook.

- **`PostEntityListener` capturing old values via `@PreUpdate` is fragile.** JPA entity listeners don't have direct access to the previous state of an entity. The design says `@PreUpdate` captures old values before Hibernate flushes, but `@PreUpdate` receives the entity in its *already-modified* state (the setter was already called). To capture old values, you need either (a) Hibernate's `@DynamicUpdate` with an Envers-style approach, (b) a Hibernate `Interceptor`/`EventListener` that reads from the persistence context's original snapshot, or (c) loading the old entity from the database in the service layer before applying changes. The current design will silently record the *new* values as both old and new.

- **No API pagination defaults or maximum page size.** `GET /api/v1/posts?size=10000` would return all posts in a single response, bypassing the pagination intent. Define a maximum page size (e.g., 100) enforced server-side, and document the defaults (page=0, size=20) in the API contract.

- **Docker Compose update command claims "zero-downtime with `--no-deps`" but this is incorrect.** The deployment section shows `docker compose up -d` and parenthetically mentions `--no-deps`, but `--no-deps` only prevents recreating dependent containers — it does not provide zero-downtime for the container being recreated. The Spring Boot container will still be stopped and restarted, causing a brief outage. The known limitations section correctly identifies this, but the deployment section contradicts it. Remove the "zero-downtime" claim from the deployment commands.

- **No explicit log level configuration.** The design mentions "structured JSON logs" but doesn't specify log levels for production (e.g., `INFO` for application, `WARN` for Hibernate SQL, `ERROR` for framework internals). Without this, the default Spring Boot logging will flood production logs with DEBUG-level Hibernate output. Specify: `logging.level.root=WARN`, `logging.level.com.blogplatform=INFO`, `logging.level.org.hibernate.SQL=WARN` in `application-prod.yml`.

---

## 5. Questions for Clarification

1. **Is VIP upgrade intended to process real payments at launch, or is it a placeholder?** If real payments, the design needs a payment gateway integration (Stripe, PayPal). If placeholder, the payment endpoints should be deferred or clearly marked as stubs to avoid shipping a fake payment system.

2. **What format will blog post content be stored in?** Plain text, Markdown, or HTML? This must be decided before the front-end editor or the full-text search index can be designed correctly.

3. **Should admins be able to view and restore soft-deleted posts?** If yes, the `@Where` annotation approach needs to be replaced with a toggleable filter. If soft delete is purely for hiding posts from public view with no admin recovery, `@Where` is acceptable.

4. **What happens when an author exceeds the 100 MB image upload quota?** Can they delete old images to reclaim quota? Is there an admin override? The constraint is defined but the user-facing behavior (error message, quota display, reclamation) is not.

---

## 6. Final Recommendation

**Approve with changes.**

The architecture is sound and the v4.0 improvements show strong iterative refinement. Three changes should be made before implementation begins:

1. **Resolve the payment system** — either integrate Stripe (recommended) or explicitly defer VIP payments and remove the payment endpoints from Phases 1-4. Do not ship a payment endpoint that accepts unverified client-submitted payment data.
2. **Specify Markdown as the content format** — this unblocks the front-end editor, search indexing, and XSS strategy decisions.
3. **Add XSS prevention specifics** — at minimum, specify `DOMPurify`/`rehype-sanitize` for rendered content, `Content-Security-Policy` header in Nginx, and plain-text enforcement for comments.

The Redis-vs-JDBC session debate and the `@PreUpdate` listener issue are important but can be resolved during Phase 1 implementation without changing the overall design.
