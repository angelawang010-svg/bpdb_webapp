# BlogPlatformDB — Project Summary

---

# Part 1: Technical Summary (For Developers and Engineers)

## 1. Original State of BlogPlatformDB

BlogPlatformDB was a **SQL Server database project** consisting of raw SQL scripts organized into five categories:

### Schema (17 tables)
- **User management:** `UserAccount` (with role_id FK to a Role table using numeric IDs: 100=Admin, 200=Author, 300=User), `UserProfile`, `AuthorProfile`
- **Content:** `BlogPosts` (with `is_premium`, `is_deleted` soft-delete flag, unique title constraint), `Categories`, `Tags`, `PostTags` (join table), `Images`
- **Engagement:** `Comments` (self-referencing for threading via `parent_comment_id`), `Likes`, `ReadPost` (tracks which users read which posts), `SavedPosts`
- **Platform:** `Subscriber`, `Payment`, `PostUpdateLog`
- **Auth:** `Role` (lookup table with numeric IDs)

All tables used SQL Server conventions: `nvarchar`, `bit` for booleans, `int IDENTITY` for primary keys, `datetime` for timestamps, clustered primary keys, and nonclustered indexes.

### Database Objects (beyond tables)
- **8 Views:** Aggregation queries (authors with 2+ posts, posts with author/like/comment counts, comments with replies, subscriber emails, tag post counts, user payment/VIP status, user comment/like counts)
- **8 Scalar/Table-Valued Functions:** `Check_Account_Exists`, `Get_Like_Count_By_Post`, `Get_Post_Count_By_Category`, `Has_User_Read_Post`, `Get_Comment_And_Like_Count`, `Get_Most_Like_Post_By_Category`, `Get_Posts_By_Author`, `Get_Tags_By_Post`
- **5 Stored Procedures:** `SP_Add_Comment` (with read-before-comment enforcement), `SP_Upgrade_User_To_VIP`, `SP_Create_Post_Notifications`, `SP_Backup_All_DB`, `SP_Backup_Database`
- **4 DML Triggers:** `BlogPostsUpdateLog` (audit on update), `BlogPosts_Delete_Log`, `BlogPosts_Insert_Log`, `Notify_Subscribers_On_New_Post`
- **Test scripts** for each function and procedure

### What Did NOT Exist
- No application layer (no API, no backend service)
- No frontend
- No authentication/authorization beyond database roles
- No deployment infrastructure
- No containerization
- The project was purely a database schema with business logic embedded in stored procedures and triggers

## 2. Migration Goals

Transform the SQL Server database project into a **full-stack modern web application**:

1. **Replace SQL Server with PostgreSQL 16** — greenfield deployment (no data migration needed; the SQL Server project is the schema/logic reference only)
2. **Extract business logic from stored procedures into Java services** — every SP, function, trigger, and view is migrated to equivalent Spring Boot service methods with explicit test coverage validating behavioral equivalence
3. **Build a REST API** with 30+ endpoints covering all CRUD operations, auth, admin functions, notifications, and file uploads
4. **Build a React SPA frontend** connected to the API
5. **Deploy locally on a personal Mac** via Docker Compose with Nginx, backups, and monitoring (VPS deployment deferred to Phase 5+)
6. **Target scale:** A few thousand users

## 3. Architectural Design Decisions

### 3.1 Decisions Made Through Iterative Review

The design document went through **7 versions** driven by 3 critical design reviews and 1 security audit. Key decisions and their evolution:

| Decision | Outcome | How It Was Decided |
|----------|---------|-------------------|
| Session storage | Redis-backed (Spring Session Data Redis) | Review v1 suggested JDBC-backed sessions. Design chose Redis. Review v2 challenged this as over-engineered. Decision: keep Redis — simplifies future AWS migration to ElastiCache. |
| Payment processing | Deferred (501 stub) | Review v2 identified that the payment system had no actual gateway. Decision: stub the VIP upgrade endpoint, defer Stripe integration to Phase 5+. |
| Content format | Raw Markdown | Review v2 flagged that content format was unspecified. Decision: store Markdown, render client-side with `react-markdown` + `rehype-sanitize`. |
| Notifications | Async (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`) with batch `saveAll()` | Review v1 identified synchronous notification creation as a scalability bottleneck. |
| Deployment target | Local Mac with Docker Compose (VPS/AWS deferred) | Design v3 replaced AWS with VPS. Later revised to local Mac deployment for Phase 4; VPS and AWS deferred to Phase 5+. |
| Soft delete implementation | Hibernate `@FilterDef`/`@Filter` (not `@Where`) | Review v2 identified that `@Where` prevents admin access to deleted content. Replaced with toggleable filter. |
| Password reset | Moved from "Deferred" to Phase 2 | Review v3 argued password reset is a baseline auth requirement, not optional. |
| Comment nesting | Capped at depth 3 | Review v3 identified unbounded recursion risk. Service walks parent chain and re-parents if depth exceeds limit. |
| IDOR prevention | Centralized `OwnershipVerifier` utility | Security audit found 6+ endpoints with unspecified ownership checks. |
| Account lockout | Redis-backed, per-account + per-IP composite key, 5 failures = 15-min lock | Security audit of design found no lockout. Phase 1B security audit refined to composite key to prevent targeted DoS. |

### 3.2 Core Architectural Patterns

- **Monorepo:** `backend/` (Spring Boot) + `frontend/` (React) in one repository
- **Package-by-feature:** `com.blogplatform.{post,comment,auth,user,notification,...}` — each package contains entity, repository, service, controller, DTOs
- **Layered architecture:** Controller → Service → Repository → PostgreSQL
- **Session-based authentication:** No JWT. Redis-backed HTTP sessions with Spring Security filter chain (session fixation protection, CSRF via `CookieCsrfTokenRepository`, CORS)
- **API versioning:** All endpoints under `/api/v1/`
- **TDD throughout:** Failing test first, then implementation. Unit tests (Mockito) + integration tests (Testcontainers with PostgreSQL + Redis)
- **Standardized responses:** All endpoints return `ApiResponse<T>` wrapper (`{success, data, message, timestamp}`)

### 3.3 XSS Prevention Strategy (Defense-in-Depth)

1. Blog posts: Markdown stored raw → rendered by `react-markdown` + `rehype-sanitize` (strips dangerous tags)
2. Comments: Plain text only (250 chars max), HTML rejected at backend
3. Nginx: `Content-Security-Policy: script-src 'self'` header
4. Images: `X-Content-Type-Options: nosniff` on `/uploads/*`
5. Never use `dangerouslySetInnerHTML`

## 4. Phased Implementation Plan

### Phase 1: Foundation (16 tasks across 3 sub-phases)

**Phase 1A — Project Setup & Infrastructure (Tasks 1-5)** — COMPLETED
- Gradle Spring Boot project (Java 21, Spring Boot 3.4.3)
- Docker Compose (PostgreSQL 16 + Redis 7, localhost-only ports, `requirepass` on Redis)
- Flyway V1 migration: 18 tables with all indexes, constraints, `TIMESTAMPTZ`, `CHECK` on role column, `ON DELETE CASCADE` on join tables
- Flyway V2 seed data (5 categories) + repeatable search vector trigger (strips Markdown before tsvector indexing)
- `DevDataSeeder` (`@Profile("dev")`) for admin user (not in versioned migration)
- Common layer: `ApiResponse`/`PagedResponse` records, 4 exception classes, `GlobalExceptionHandler` (9 handlers including Spring Security re-throw), `CreatedAtEntity`/`AuditableEntity` mapped superclasses, `BaseIntegrationTest` with Testcontainers

**Phase 1B — Auth System (Tasks 6-11)** — IN PROGRESS
- JPA entities: `UserAccount`, `UserProfile`, `Role` enum
- `CustomUserDetailsService`, `SecurityConfig` (session-based, CSRF cookie, CORS externalized, max 1 concurrent session)
- `AuthController`: register, login, logout, me
- `LoginAttemptService`: Redis-backed brute-force protection with composite key (`username:ip`), Lua script for atomic increment+expire
- BCrypt cost factor 12, password max length 128, timing side-channel mitigation (valid dummy hash)
- Input validation: username pattern `^[a-zA-Z0-9_-]+$`, email normalization to lowercase

**Phase 1C — Rate Limiting, Entities & Verification (Tasks 12-16)**
- `RateLimitFilter` (Bucket4j + Caffeine cache, tiered: 10/min auth endpoints, 60/min anonymous, 120/min authenticated)
- `OwnershipVerifier` utility for IDOR prevention
- 16 JPA entity classes (all remaining entities)
- Full auth flow integration test
- Smoke test / verification

### Phase 2: Full REST API (23 tasks across 2 sub-phases)

**Phase 2A — Content & CRUD (Tasks 1-10)**
- Category CRUD (admin-only write, `@PreAuthorize`)
- Tag CRUD (admin-only create)
- Post DTOs, `PostRepository` with full-text search (`tsvector @@ plainto_tsquery`), category/tag/author filtering
- `PostService`: create (publishes `NewPostEvent`), update (captures old values in `PostUpdateLog`), soft-delete, read tracking, premium access gate
- `PostEntityListener` (`@PostPersist` audit logging)
- `PostController`: full CRUD + save/unsave bookmarks, pagination (max page size 100)
- `CommentService`: threading with depth limit 3, read-before-comment enforcement, parent validation
- `CommentController`: threaded response structure (recursive `CommentResponse`)
- Like/unlike (idempotent toggle)
- Author profiles with post counts

**Phase 2B — Platform Features (Tasks 11-23)**
- Subscription subscribe/unsubscribe
- Async notification system (`@Async` + `@TransactionalEventListener`, batch `saveAll()`, error logging)
- Notification CRUD (account_id extracted from SecurityContext, never from request)
- User profile endpoints + saved posts (ownership via `@ownershipVerifier`)
- Password reset (32-byte random tokens, SHA-256 hashed storage, 30-min expiry, constant-time response)
- Email verification (same token spec)
- Image upload (Apache Tika magic byte validation, UUID filenames, 5MB max, 100MB per-user quota)
- VIP upgrade stub (501 Not Implemented)
- Admin endpoints (view/restore soft-deleted posts, role assignment)
- Scheduled cleanup jobs (90-day notification retention, 1-year ReadPost retention)
- `LoginAttemptService` (Redis-backed account lockout)
- `EmailService` interface + dev logging implementation
- SpringDoc OpenAPI configuration
- Full integration test: complete post lifecycle end-to-end

### Phase 3: Frontend (18 tasks)
- Vite + React 18 + TypeScript + Tailwind CSS
- TypeScript types mirroring backend DTOs
- Axios client with CSRF token interception and 401 redirect
- `AuthContext` + `useAuth` hook
- React Router v6 with `ProtectedRoute` (role-based)
- Login/Register pages (React Hook Form with validation)
- Home page: post feed with filters (category, tag, author, search), pagination
- Post detail: Markdown rendering (`react-markdown` + `rehype-sanitize`), comments (threaded), likes, save
- Post editor: two-column Markdown textarea + live preview
- User profile + saved posts pages
- Notifications page (30-second polling with exponential backoff)
- Admin dashboard (categories, tags, users, deleted posts)
- Author listing and profile pages
- Common UI components, utility functions
- MSW mock server for component tests
- Cypress E2E tests

### Phase 4: Local Production Deployment — Mac (8 tasks)
- Multi-stage Dockerfiles (backend: temurin:21-jdk build → temurin:21-jre run, non-root user; frontend: node:20-alpine build → nginx:alpine serve)
- Production Docker Compose: 4 containers (Nginx, Spring Boot, PostgreSQL, Redis) with healthchecks, restart policies, network segmentation, resource limits
- Nginx: HTTP (localhost), security headers (CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy), rate limiting, API proxy, actuator blocking, SPA routing, upload serving, gzip, asset caching
- `.env`-driven configuration (24-char minimum passwords)
- Backup script (pg_dump, tiered retention: 7 daily / 4 weekly / 3 monthly, macOS launchd scheduler)
- Local smoke test

### Phase 5+ (Deferred / Future)
- VPS deployment (HTTPS via Let's Encrypt, UFW firewall, fail2ban, SSH hardening, Slack monitoring alerts, backup verification)
- AWS cloud migration (ElastiCache, RDS, S3, ECS/EKS, CloudFront)
- Stripe Checkout for VIP payments
- OAuth2 / 2FA
- Account deletion (GDPR)
- Real-time notifications (SSE or WebSocket)
- Content moderation
- Email service production implementation (Mailgun/SendGrid)

## 5. Key Technical Details

### 5.1 Database Schema Migration (SQL Server → PostgreSQL)

| SQL Server | PostgreSQL |
|-----------|-----------|
| `int IDENTITY(1,1)` | `BIGSERIAL` |
| `nvarchar(n)` | `VARCHAR(n)` |
| `nvarchar(max)` | `TEXT` |
| `bit` | `BOOLEAN` |
| `datetime` | `TIMESTAMPTZ` |
| `int` role_id (100/200/300) | `VARCHAR(20)` role with `CHECK (role IN ('USER','AUTHOR','ADMIN'))` |
| Clustered PK | Standard PK (PostgreSQL has no clustered index concept) |
| `GETDATE()` | `NOW()` |
| New: `search_vector TSVECTOR` on `blog_post` | Full-text search with GIN index |
| New: `password_reset_token`, `email_verification_token` tables | Token-based auth flows |

### 5.2 Stored Procedure → Java Service Migration

| SQL Server SP/Trigger | Java Equivalent |
|-----------------------|----------------|
| `SP_Add_Comment` (read-before-comment, validation) | `CommentService.addComment()` — checks ReadPost exists, validates parent on same post, enforces depth limit 3 |
| `SP_Upgrade_User_To_VIP` | Stubbed (501) — deferred to Phase 5+ with Stripe |
| `SP_Create_Post_Notifications` | `NotificationService.notifySubscribers()` — `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`, batch `saveAll()` |
| `SP_Backup_All_DB` / `SP_Backup_Database` | `scripts/backup.sh` (pg_dump + gzip + retention) |
| `TR_BlogPostsUpdateLog` (update trigger) | `PostService.updatePost()` — loads old values before applying changes, writes `PostUpdateLog` |
| `TR_BlogPosts_Insert_Log` | `PostEntityListener.@PostPersist` |
| `TR_BlogPosts_Delete_Log` | `PostService.deletePost()` — soft delete (`is_deleted = true`) |
| `TR_Notify_Subscribers_On_New_Post` | `NewPostEvent` + `NotificationService` event listener |

### 5.3 Security Architecture

- **Authentication:** Session-based (not JWT). Redis-backed sessions via Spring Session Data Redis. Session fixation protection (new session on login). Max 1 concurrent session per user.
- **CSRF:** `CookieCsrfTokenRepository.withHttpOnlyFalse()` — cookie-based CSRF with Axios interceptor reading `XSRF-TOKEN` cookie and setting `X-XSRF-TOKEN` header.
- **Password handling:** BCrypt work factor 12. Max 128 chars input. Timing side-channel mitigation for non-existent users (dummy hash comparison).
- **Rate limiting:** Bucket4j with Caffeine cache (100K entries, 5-min expiry). Three tiers: auth endpoints (10/min/IP), anonymous (60/min/IP), authenticated (120/min/username).
- **Account lockout:** Redis-backed. Composite key `login:failures:{username}:{ip}`. 5 failures = 15-min lock. Atomic increment+expire via Lua script.
- **Token security:** Password reset and email verification tokens: 32 bytes cryptographically random (SecureRandom), URL-safe base64 encoded, stored as SHA-256 hashes, 30-minute expiry, single-use.
- **Image upload:** Apache Tika magic byte validation (not just Content-Type header), UUID-based filenames (prevents path traversal), 5MB max file size, 100MB per-user quota, JPEG/PNG/WebP only.
- **Ownership verification:** Centralized `OwnershipVerifier` utility used via `@PreAuthorize("@ownershipVerifier.isOwnerOrAdmin(#id, authentication)")` on all owner-only endpoints.
- **Infrastructure:** Docker network segmentation (frontend/backend isolation), container resource limits, Redis `requirepass`, actuator restricted to `/health` only, Swagger gated behind dev profile. VPS hardening (UFW, fail2ban, SSH key-only auth) deferred to Phase 5+.

### 5.4 Security Audit Scores

| Component | Pre-Remediation SQS | Post-Remediation SQS |
|-----------|---------------------|---------------------|
| Design Document (v6.0) | 42/100 | ~85+ (estimated) |
| Phase 1A Plan (v1.2) | 49/100 | ~85+ (after applying fixes) |
| Phase 1B Plan (v1.1) | 38/100 | 94/100 |
| Phase 1C Plan (v1.2) | 78/100 | ~90+ (2 medium findings remaining) |

### 5.5 Testing Strategy

- **Unit tests:** JUnit 5 + Mockito. Service-level tests for all business logic. TDD approach.
- **Integration tests:** Spring Boot `@SpringBootTest` + Testcontainers (PostgreSQL 16 + Redis 7). Full HTTP round-trip via MockMvc.
- **Frontend unit tests:** Vitest + React Testing Library + MSW (Mock Service Worker)
- **E2E tests:** Cypress covering critical user flows (auth, post lifecycle, admin operations)
- **SP validation tests:** Each stored procedure's business rules have explicit test cases in the Java service tests, validating behavioral equivalence.

---

# Part 2: Non-Technical Summary (For Stakeholders and Non-Engineers)

## What Is BlogPlatformDB?

BlogPlatformDB is a blog platform — think of it like a simplified version of Medium or WordPress. Users can register accounts, authors can write and publish blog posts, readers can browse posts, leave comments, like content, and subscribe to notifications about new posts. There are also admin tools for managing categories, tags, and user roles.

## Where Did We Start?

The project started as **a database only** — essentially a blueprint for how to store all the blog data (users, posts, comments, likes, etc.) built using Microsoft SQL Server. It also included some automated rules embedded directly in the database:

- When someone posts a comment, the database checked that they had actually read the article first
- When a new blog post was published, the database automatically notified all subscribers
- When a post was edited, the database automatically kept a record of what changed

Think of it like having a filing cabinet with very detailed folders and some mechanical sorting mechanisms built in — but no office building, no front desk, no way for anyone to actually walk in and use it.

**There was no website, no app, no way for anyone to interact with the data.** Just the filing cabinet.

## What Are We Building?

We are transforming that filing cabinet into a fully functional office building:

1. **A back office (the API/backend):** This is the "brain" of the application. It handles all the logic — who can do what, how posts are created, how comments work, how notifications are sent. Built with Java and Spring Boot (an industry-standard framework used by companies like Netflix, Amazon, and LinkedIn).

2. **A front desk / storefront (the website/frontend):** This is what users actually see and interact with in their web browser. Built with React (the same technology Facebook, Instagram, and Airbnb use for their interfaces).

3. **A new filing system (the database):** We moved from Microsoft SQL Server to PostgreSQL — a free, open-source database that is just as capable but costs nothing to license. The data structure stays essentially the same; we are just translating it to a new system.

4. **A building with security (the deployment):** The whole thing runs on your personal Mac via Docker containers (standardized software packages) with locks on the doors (authentication, rate limiting), and automatic backups. It can later be moved to a rented server for internet access.

## Why Are We Doing This?

| Before | After |
|--------|-------|
| Database scripts only — no one can use it | A working website anyone can visit |
| Business rules trapped inside the database | Business rules in the application where they are easier to test, change, and maintain |
| Microsoft SQL Server (licensing costs) | PostgreSQL (free, open-source) |
| No security beyond database passwords | Multi-layered security: encrypted connections, rate limiting, brute-force protection, XSS prevention |
| No deployment plan | Runs locally via Docker Compose with automated backups (VPS deployment optional) |

## How Is the Work Organized?

The project is divided into 4 main phases, like building a house:

### Phase 1: Foundation (Completed / In Progress)
*Analogy: Laying the foundation, plumbing, and electrical wiring*

- Set up the project structure and development tools
- Created the database tables in PostgreSQL (translating from SQL Server)
- Built the login/registration system with security protections:
  - Passwords are encrypted (like a safe — even if someone broke in, they could not read passwords)
  - Accounts lock after 5 failed login attempts (like a bank PIN)
  - Automated rate limiting prevents someone from overwhelming the system with requests (like a bouncer at a club)
- **Status:** Phase 1A complete (5 tasks). Phase 1B in progress. Phase 1C planned.

### Phase 2: All the Features (23 tasks planned)
*Analogy: Building all the rooms, installing fixtures*

- Blog post creation, editing, and deletion (with a "trash bin" so deleted posts can be recovered)
- Comment system with threaded replies (like Reddit's comment chains, limited to 3 levels deep)
- Like/unlike posts
- Search (full-text search that understands English — searching "running" also finds "run" and "runs")
- Notifications when new posts are published
- User profiles
- Password reset via email
- Image upload (with safety checks to prevent malicious files)
- Admin tools: manage categories/tags, promote users to author role, recover deleted posts

### Phase 3: The User Interface (18 tasks planned)
*Analogy: Interior design, painting, signage*

- The actual website users see: home page, post pages, login/register forms
- Markdown editor for authors (like writing in a simplified formatting language — the same way GitHub and Slack let you format text with asterisks and hashtags)
- Live preview while writing posts
- Notification bell with unread count
- Admin dashboard
- Mobile-friendly design

### Phase 4: Running Locally (8 tasks planned)
*Analogy: Setting up and running the building on your own property*

- Package everything into Docker containers (standardized shipping containers for software — ensures it runs the same everywhere)
- Run the full application on your Mac via Docker Compose
- Automated daily backups with tiered retention (7 daily, 4 weekly, 3 monthly)
- Security: network isolation between containers, rate limiting, resource limits

## How Much Will It Cost to Run?

- **Local deployment (Phase 4):** $0/month — runs on your personal Mac via Docker Desktop (free for personal use)
- **Database:** $0 (PostgreSQL is free)
- **VPS deployment (Phase 5+, optional):** ~$6-12/month if you want the application accessible on the internet
- **AWS cloud (Phase 5+, optional):** ~$50-80/month if you outgrow a single server

## Quality Assurance and Security

The project has undergone an unusually rigorous review process for its size:

- **3 rounds of critical design review** by a senior engineering perspective, identifying and resolving 18+ critical issues and 25+ minor issues before any code was written
- **4 security audits** (1 on the design, 3 on implementation plans) identifying and remediating vulnerabilities across authentication, authorization, data validation, and infrastructure
- **Every implementation plan** goes through critical review and security audit before coding begins
- **Test-driven development:** Tests are written before the code they test, ensuring nothing is built without verification

### Security Measures in Plain Language

| Threat | Protection |
|--------|-----------|
| Someone guesses passwords | Passwords encrypted with BCrypt (would take billions of years to crack). Account locks after 5 wrong attempts. |
| Someone floods the site with requests | Rate limiting: anonymous users get 60 requests/minute, logged-in users get 120. Auth endpoints limited to 10/minute. |
| Someone tries to inject malicious code | Blog content is sanitized before display. Comments are plain text only. Security headers prevent script execution. |
| Someone uploads a dangerous file | Files are verified by their actual content (not just the filename), renamed with random IDs, and size-limited. |
| Server crashes and data is lost | Daily automated backups, kept for 7 days. Weekly copies kept for 30 days. Monthly copies kept for 90 days. Monthly verification that backups work. |
| Someone finds an old password reset link | Reset links expire in 30 minutes. They can only be used once. The actual token is never stored — only a fingerprint of it. |

## What Is Not Included (Deferred to Future)

These features were explicitly evaluated and deferred because they are not needed at launch:

- **Real payment processing** (VIP upgrade button exists but returns "not yet available")
- **Social login** (Google, GitHub, etc.)
- **Two-factor authentication** (2FA)
- **Account deletion** (relevant for GDPR if EU users are expected)
- **Real-time notifications** (currently polls every 30 seconds; could upgrade to instant push)
- **AWS cloud migration** (for when the platform outgrows a single server)
- **Content moderation tools** (flagging, auto-moderation)

---

*This summary was produced on 2026-03-06 from 22 planning documents covering the design, critical reviews, security audits, and phased implementation plans.*
