# Critical Design Review: BlogPlatformDB Java Migration (v3.0)

**Reviewed document:** `docs/plans/2026-02-27-java-migration-design.md`
**Review version:** 1
**Date:** 2026-02-28

---

## 1. Overall Assessment

**Strengths:** The design is well-organized, demonstrates disciplined scope control (YAGNI section, deferred features), and makes pragmatic technology choices. The VPS-first deployment with AWS as a future option is a smart cost-conscious strategy. The monorepo structure, session-based auth for a single server, and phased rollout are all appropriate for the stated scale.

**Major concerns:** The design is sound for a greenfield blog platform, but as a *migration* document it has significant strategic gaps. It does not address how existing data moves from SQL Server to PostgreSQL, how to validate behavioral equivalence after migration, or how to handle the transition period. The architecture also has several hidden coupling and scalability bottleneck risks that could become painful before reaching the "few thousand users" target.

---

## 2. Critical Issues

- **No data migration strategy exists.**
  The document covers schema migration (Flyway for PostgreSQL) but is completely silent on how existing SQL Server data gets into PostgreSQL. For a migration project, this is the highest-risk omission. Data migration often surfaces schema incompatibilities, encoding issues, and data quality problems that invalidate design assumptions.
  *Impact:* Could block the entire launch or cause data loss.
  *Suggestion:* Add a dedicated section covering: export tooling (bcp/SSIS to CSV, then PostgreSQL COPY or pgloader), data transformation rules (e.g., role IDs to enum strings, BIT to BOOLEAN), validation queries to confirm row counts and referential integrity post-migration, and a rollback plan if migration fails.

- **In-memory sessions are a single point of fragility.**
  Sessions stored in Spring Boot's default HttpSession are lost on every container restart, redeploy, or crash. The document acknowledges this only in the AWS section (Section 11). For a production VPS deployment (Phase 4), every `docker compose up -d` will force-logout all users with no warning.
  *Impact:* Poor user experience in production; operational risk during deployments.
  *Suggestion:* Use Spring Session with JDBC (PostgreSQL-backed sessions) from Phase 1. This costs nearly nothing since PostgreSQL is already running, requires no additional infrastructure, and eliminates the problem entirely. Reserve Redis/ElastiCache for the AWS phase.

- **Synchronous subscriber notification is a scalability bottleneck and the first component likely to fail.**
  `PostService.createPost()` calls `NotificationService.notifySubscribers()` synchronously. With N active subscribers, creating a post triggers N database INSERTs inside the same transaction. At a few hundred subscribers, this makes post creation slow. At a few thousand, it risks transaction timeouts and blocks the author's UI.
  *Impact:* Post creation latency degrades linearly with subscriber count; potential transaction failures.
  *Suggestion:* Decouple notification creation from post creation. At minimum, use Spring's `@Async` with `@TransactionalEventListener(phase = AFTER_COMMIT)` so notifications are created in a background thread after the post transaction commits. This is a one-annotation change that eliminates the coupling without requiring a message queue.

- **No behavioral equivalence validation plan.**
  The document maps SQL objects to Java equivalents but provides no strategy for verifying that the migrated application behaves identically to the original. Stored procedures encode specific business rules (VIP pricing tiers, comment validation sequences, notification filtering) that must be preserved exactly.
  *Impact:* Silent business logic regressions that go undetected until production.
  *Suggestion:* Define a set of "golden path" test scenarios derived from each stored procedure's logic. Run them against the SQL Server database to capture expected outputs, then use those as acceptance tests for the Spring Boot services. This is distinct from unit/integration testing — it specifically validates migration correctness.

- **No rate limiting strategy beyond auth endpoints.**
  Rate limiting is mentioned only for auth endpoints (10 requests/minute/IP). Public endpoints like `GET /api/posts` and `GET /api/posts/{id}` have no protection. A single bot or scraper can hammer the API and overload the database.
  *Impact:* Denial-of-service risk on a single-server deployment with no CDN or WAF in front.
  *Suggestion:* Add global rate limiting (e.g., Bucket4j or a servlet filter) with tiered limits: stricter for anonymous users, more generous for authenticated users. On a VPS without a load balancer, this is the only layer of protection.

- **Image upload to local filesystem has no size limits, validation, or abuse prevention.**
  `ImageService` stores uploads on the local filesystem. The document does not mention: maximum file size, allowed MIME types, filename sanitization, storage quota, or what happens when disk fills up. On a VPS with 40-80 GB SSD, uncontrolled uploads are an existential risk.
  *Impact:* Disk exhaustion takes down the entire server (database, application, and all).
  *Suggestion:* Define maximum file size (e.g., 5 MB), allowed types (JPEG, PNG, WebP), per-user upload quotas, and a storage monitoring alert. Spring Boot's `spring.servlet.multipart.max-file-size` handles the size limit. Add a health check that alerts when disk usage exceeds 70%.

---

## 3. Alternative Architectural Challenge

**Alternative: Server-Side Rendered (SSR) Monolith with HTMX**

Instead of a Spring Boot REST API + React SPA, build a traditional Spring Boot MVC application using Thymeleaf templates enhanced with HTMX for dynamic interactions.

- **Pro:** Dramatically simpler architecture. Eliminates the entire front-end build pipeline (Vite, TypeScript, React Query, Axios, npm), removes CORS/CSRF complexity (same-origin by default), halves the testing surface, and reduces deployment to a single artifact. For a blog platform — which is fundamentally a content-delivery application — server-side rendering is the natural fit and provides better SEO out of the box.

- **Con:** Less interactive UI. Features like real-time comment threading, optimistic like updates, and rich post editing are harder to build and feel less polished. Developer hiring pool is also smaller for Thymeleaf/HTMX compared to React. If the application evolves toward a highly interactive SPA (e.g., real-time collaboration, complex dashboards), the SSR approach would need significant rearchitecting.

---

## 4. Minor Issues & Improvements

- **CSRF token delivery mechanism is fragile.** The document says Spring Security sends the CSRF token via a `XSRF-TOKEN` cookie, and Axios reads it. This works with the default `CookieCsrfTokenRepository.withHttpOnlyFalse()`, but the document doesn't mention the `withHttpOnlyFalse()` requirement. If omitted, the cookie is HttpOnly and JavaScript cannot read it, silently breaking all POST/PUT/DELETE requests.

- **No API versioning strategy.** All endpoints are under `/api/`. If breaking changes are needed later, there's no path prefix (`/api/v1/`) to allow gradual migration. Adding versioning after launch is painful. Consider `/api/v1/` from the start — it costs nothing and provides a clean upgrade path.

- **Full-text search is mentioned but not specified.** `GET /api/posts?search=spring+boot` implies full-text search on title and content, but the document doesn't specify whether this uses PostgreSQL's `tsvector/tsquery`, `ILIKE`, or `pg_trgm`. The choice significantly affects performance and relevance quality. `ILIKE` will not scale; `tsvector` requires GIN indexes and schema changes.

- **No graceful degradation for the notification polling.** The front-end uses React Query polling for notifications. If the server is slow or down, polling continues hammering the endpoint. Consider exponential backoff on error, or at minimum, a reasonable polling interval (the document doesn't specify one).

- **Docker Compose for production is a single point of failure.** Docker Compose is designed for development and single-host deployments. It has no built-in health-check-based restart orchestration, no rolling updates, and `docker compose up -d` causes brief downtime. This is acceptable at this scale but should be explicitly documented as a known limitation, with Docker Swarm or a simple blue-green script as a future improvement path.

- **Backup retention is too short.** 7-day retention with daily backups means you cannot recover from a data corruption bug that goes unnoticed for more than a week. Consider keeping weekly backups for 30 days and monthly backups for 90 days. The storage cost on a VPS is negligible.

- **No mention of database connection pooling configuration.** The document mentions HikariCP (Spring Boot default) but doesn't specify pool size. On a 2 vCPU VPS, the default HikariCP pool of 10 connections is reasonable, but with synchronous notifications creating N inserts per post, a post creation could monopolize the pool. This reinforces the need to make notifications async.

---

## 5. Questions for Clarification

1. **Is there existing production data in SQL Server that needs to be migrated, or is this a greenfield deployment with a new schema?** This fundamentally changes the project scope and risk profile.

2. **What is the expected subscriber count at launch?** If it's more than ~100, the synchronous notification design will cause noticeable latency on post creation.

3. **Is the "read before comment" requirement a hard business rule or a soft preference?** It adds complexity to both the API and front-end (must track read state, handle race conditions). If it's a preference, a simpler "must be logged in" check might suffice.

4. **Who operates the VPS?** The deployment plan assumes SSH access, Docker knowledge, and comfort with Nginx/Certbot. If the operator is non-technical, a PaaS (Railway, Render, Fly.io) might be more appropriate despite slightly higher cost.

5. **Is there a content moderation strategy?** The document allows any authenticated user to comment and any author to post, with only admin-level delete. For a public-facing platform, some form of moderation (flagging, auto-moderation, or approval queues) is typically needed.

---

## 6. Final Recommendation

**Approve with changes.**

The core architecture is sound and well-suited to the stated requirements. The tech stack choices are modern and appropriate. The phased plan is realistic.

However, three changes should be made before implementation begins:

1. **Add a data migration section** — even if it's "no existing data, greenfield only," that needs to be stated explicitly.
2. **Switch to JDBC-backed sessions** — the cost is trivial (one dependency, one table) and eliminates the session-loss-on-redeploy problem from day one.
3. **Make subscriber notifications async** — this is the first bottleneck that will hit at scale and the fix is a one-annotation change (`@Async` + `@TransactionalEventListener`).

The remaining issues (rate limiting, image upload limits, full-text search strategy) can be addressed during implementation without changing the overall design.
