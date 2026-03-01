# BlogPlatformDB — Java Migration Design

**Version:** 7.0
**Last Updated:** 2026-03-01
**Status:** Approved

---

## Change Log

| Version | Date       | Author | Summary | Details |
|---------|------------|--------|---------|---------|
| 1.0 | 2026-02-27 | Angela + Claude | Initial design | Back-end only design with Spring Boot, PostgreSQL, REST API. Covered 6 sections: project structure, entity model, API endpoints, business logic migration, security & auth, testing strategy. |
| 2.0 | 2026-02-27 | Angela + Claude | Major expansion + front-end | Added React + TypeScript front-end (Vite, Tailwind, React Query). Expanded all 6 original sections with detailed field-level entity descriptions, DTO listings, response format examples. Added: overall architecture diagram with request flow, monorepo structure, front-end file structure with components/pages/hooks, authentication flow diagram, Spring Security filter chain, CSRF/CORS details, testing pyramid with front-end tests (Vitest, React Testing Library, MSW, Cypress), 5 implementation phases, AWS cloud migration plan with architecture diagram and cost estimates, deferred features list. Added changelog and versioning. |
| 3.0 | 2026-02-27 | Angela + Claude | Deployment strategy change | Replaced AWS as primary deployment target with VPS (self-hosted). Rationale: AWS is overkill for a few thousand users (~$50-80/month vs ~$6-12/month for a VPS). Added detailed VPS deployment section covering Docker, Nginx, SSL, backups, monitoring, and firewall. Demoted AWS to a future growth option (Phase 5 → optional). Updated implementation phases to reflect VPS as Phase 4 deployment target. Added Nginx architecture diagram and deployment commands. |
| 4.0 | 2026-02-28 | Angela + Claude | Critical review response | Applied changes from critical design review (v1). **Critical fixes:** (1) Added greenfield deployment declaration — no SQL Server data migration needed, schema only. (2) Replaced in-memory sessions with Redis-backed sessions (Spring Session Data Redis); added Redis to Docker Compose stack. (3) Made subscriber notifications async via `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` to eliminate post-creation bottleneck. (4) Added stored procedure validation checklist mapping each SP to Java equivalent and covering test cases. (5) Added global rate limiting with Bucket4j — tiered: anonymous 60 req/min, authenticated 120 req/min, auth endpoints 10 req/min. (6) Added image upload constraints: 5 MB max, JPEG/PNG/WebP only, filename sanitization, 100 MB per-user quota, disk alert at 70%. **Minor fixes:** (7) Documented `CookieCsrfTokenRepository.withHttpOnlyFalse()` requirement for CSRF. (8) Changed all API endpoints from `/api/` to `/api/v1/` for future versioning. (9) Specified PostgreSQL `tsvector/tsquery` with GIN indexes for full-text search. (10) Added 30-second notification polling interval with exponential backoff on error. (11) Documented Docker Compose single-point-of-failure as known limitation. (12) Extended backup retention: 7 daily + 4 weekly + 3 monthly. (13) Documented HikariCP connection pool defaults. (14) Added PaaS alternatives note for non-technical VPS operators. |
| 5.0 | 2026-02-28 | Angela + Claude | Critical review response (v2) | Applied changes from critical design review (v2). All six issues from review v1 were confirmed addressed in v4.0. This version resolves five new critical issues and seven minor issues raised against v4.0. **Critical fixes:** (1) Deferred VIP payment processing — `POST /api/v1/users/{id}/upgrade-vip` endpoint and the `payment/` package are now explicitly marked as stubs; no real payment gateway is wired in Phases 1–4. VIP payments moved to Deferred Features with a Stripe Checkout note for Phase 5+. Eliminates the security risk of accepting client-submitted, server-unverified payment data. (2) Specified Markdown as the blog post content format — raw Markdown stored in `content TEXT` column, rendered to sanitized HTML on the front-end via `react-markdown` + `rehype-sanitize`; Markdown syntax stripped before `tsvector` indexing via Flyway trigger. Added Content Format section. (3) Kept Redis session storage (review recommended JDBC for single-VPS simplicity; overruled — Redis is already configured, operational cost is acceptable, and it simplifies the future AWS ElastiCache migration to a connection-string swap). (4) Added full XSS prevention strategy: `rehype-sanitize` for rendered Markdown, Nginx `Content-Security-Policy: script-src 'self'` header, back-end rejection of any HTML in comment text, prohibition on raw `dangerouslySetInnerHTML`. Replaced the vague "input sanitization" Phase 4 bullet with a detailed XSS Prevention section. (5) Added error handling and observability to `@Async` notifications: try-catch with ERROR-level logging (post ID, subscriber count, error), batch `saveAll()` replacing N individual inserts, failure logging by post ID for manual re-notification. **Minor fixes:** (6) Replaced `@Where(clause = "is_deleted = false")` on `BlogPost` with Hibernate `@FilterDef` / `@Filter` to support admin view and restore of soft-deleted posts; added `GET /api/v1/admin/posts/deleted` endpoint to admin section. (7) Added Vite proxy configuration to route `/api` requests to `localhost:8080` during development, eliminating cross-origin session cookie issues without requiring `SameSite=None`. (8) Specified Slack incoming webhook as the alert destination for monitoring cron jobs (disk usage, backup failures). (9) Fixed `@PreUpdate` audit logging: service layer loads the existing post before applying changes, passes captured old values to `PostUpdateLog` — replacing the broken entity listener approach where `@PreUpdate` receives the already-modified entity. (10) Added server-side maximum page size of 100 and documented defaults (page=0, size=20) via `PageableHandlerMethodArgumentResolver`. (11) Removed the incorrect "zero-downtime with `--no-deps`" claim from the deployment commands section; the Known Limitations section already correctly documents the brief restart downtime. (12) Added explicit log level configuration to `application-prod.yml`: `root=WARN`, `com.blogplatform=INFO`, `org.hibernate.SQL=WARN` to prevent Hibernate SQL flooding of production logs. |
| 6.0 | 2026-03-01 | Angela + Claude | Critical review response (v3) | Applied changes from critical design review (v3). All issues from reviews v1 and v2 were confirmed addressed in v5.0. This version resolves five critical issues, seven minor issues, and four clarification questions raised against v5.0. **Critical fixes:** (1) Moved password reset from Deferred to Phase 2 — baseline authentication requirement, not optional. Two new endpoints: `POST /api/v1/auth/forgot-password` and `POST /api/v1/auth/reset-password` using time-limited single-use tokens via transactional email service. (2) Added notification retention policy: composite index on `(account_id, is_read, created_at)`, auto-delete read notifications older than 90 days via scheduled cleanup job, scoped "mark all as read" to `WHERE is_read = false`. (3) Added `email_verified BOOLEAN DEFAULT false` column to UserAccount schema; email verification implemented in Phase 2 alongside password reset using same transactional email infrastructure. Enforced before password reset and VIP upgrade. (4) Capped comment nesting depth at 3 levels — `CommentService.addComment()` walks parent chain and re-parents if depth exceeds limit. (5) Documented ReadPost "must read before commenting" semantics: opening the post page satisfies the requirement (original SP behavior). Added 1-year retention policy for ReadPost entries. **Minor fixes:** (6) Added SSH hardening note: key-based auth only, password authentication disabled, fail2ban installed. (7) Added `Access-Control-Max-Age: 3600` to CORS configuration for preflight caching. (8) Documented UserProfile/UserAccount split rationale: separates auth data from display data, preserves original SQL Server schema design. (9) Added `@Size(max = 100000)` on blog post `content` field to prevent oversized submissions (~100 KB Markdown limit). (10) Clarified `Subscriber.expiration_date` semantics: always null in Phases 1–4 (free, permanent subscriptions); field exists for future Phase 5+ VIP-tied or time-limited scenarios. (11) Added monthly automated backup verification: restore latest backup to temporary Docker PostgreSQL container, run integrity checks, destroy container. (12) Documented Flyway migration naming convention: `V1__initial_schema.sql`, `V2__seed_data.sql`, `R__search_vector_trigger.sql`. **Clarification resolutions:** (13) AUTHOR role is admin-assigned only via admin dashboard. (14) Account deletion deferred — documented as known gap in Deferred Features. (15) 250-character comment limit confirmed as intentional business rule from original SQL Server SP. |
| 7.0 | 2026-03-01 | Angela + Claude | Security audit response | Applied remediations from security audit (v1). Addresses 8 findings (1 High, 4 Medium, 3 Low). **High:** (1) IDOR — added Ownership Verification subsection specifying `OwnershipVerifier` service and `@PreAuthorize` pattern for all "Owner only" endpoints. **Medium:** (2) Image upload — added magic byte validation via Apache Tika and Nginx `X-Content-Type-Options: nosniff` on `/uploads/*`. (3) Password reset tokens — specified 32-byte cryptographically random tokens, 30-minute expiry, SHA-256 hashed storage, constant-time response to prevent user enumeration; same for email verification tokens. (4) Account lockout — added per-account failed login tracking in Redis: lock after 5 consecutive failures for 15 minutes, reset on success. (5) Redis authentication — added `requirepass` configuration in Docker Compose. **Low:** (6) Credential management — documented `.env`/`.env.example` gitignore rules, placeholder-only example, 24-char minimum DB password, full secrets inventory. (7) Actuator — restricted to health endpoint only via `management.endpoints.web.exposure.include=health` + Nginx block. (8) Notifications — explicitly stated controller must extract `account_id` from security context, never from request parameters. |

---

## Overview

Migrate the BlogPlatformDB SQL Server database project into a full-stack modern web application. The back-end is a Spring Boot REST API with PostgreSQL. The front-end is a React single-page application with TypeScript. The application runs on a self-hosted VPS (Virtual Private Server) for a few thousand users, with Docker and Nginx handling containerization and traffic routing. AWS cloud migration is documented as a future growth option if the application outgrows a single server.

**Data migration scope:** This is a **greenfield deployment** — there is no existing production data in SQL Server that needs to be migrated. The SQL Server project serves as the schema and business logic reference. Flyway migrations will create the PostgreSQL schema from scratch. No data export, transformation, or migration tooling is required.

## Content Format

Blog post content is stored as **raw Markdown** in the `content TEXT` column. This is a foundational decision that affects the editor, rendering pipeline, search indexing, and XSS strategy.

| Layer | Decision |
|---|---|
| Storage | Raw Markdown string in PostgreSQL `TEXT` column — the database treats it as plain text |
| Front-end rendering | `react-markdown` converts Markdown to HTML in the browser; `rehype-sanitize` strips dangerous tags before DOM injection |
| Post editor | Markdown textarea with a live preview pane (not a WYSIWYG editor) |
| Full-text search | A Flyway-managed PostgreSQL trigger strips Markdown syntax (removes `##`, `**`, `_`, backticks, links) before populating the `search_vector tsvector` column, so search indexes clean prose rather than formatting symbols |
| XSS | Markdown is safer than raw HTML as input, and `rehype-sanitize` provides a second layer on render; see XSS Prevention section |

**Rationale:** Storing raw Markdown keeps the database format-agnostic, storage compact (vs HTML which is 2–5× larger), and the source recoverable. Rendering is free (client CPU) and cacheable. This is the approach used by GitHub, GitLab, and most modern content platforms.

---

## Tech Stack

### Back-End

| Component        | Choice                          | Why                                                    |
|------------------|---------------------------------|--------------------------------------------------------|
| Framework        | Spring Boot 3.x                 | Industry standard, massive ecosystem                   |
| Language         | Java 21 (LTS)                   | Latest long-term support, modern features              |
| Build tool       | Gradle (Groovy DSL)             | Cleaner config than Maven, faster builds               |
| Database         | PostgreSQL 16                   | Free, feature-rich, native JSON and full-text search   |
| ORM              | Spring Data JPA (Hibernate)     | Reduces boilerplate, type-safe queries                 |
| DB migrations    | Flyway                          | Versioned, repeatable schema migrations                |
| Authentication   | Spring Security (session-based) | Cookie-based sessions, backed by Redis                 |
| Session store    | Spring Session Data Redis       | Sessions persist across restarts and redeploys         |
| Rate limiting    | Bucket4j                        | Global rate limiting with tiered limits per user type  |
| Validation       | Jakarta Bean Validation         | Declarative constraints via annotations                |
| API docs         | SpringDoc OpenAPI (Swagger)     | Auto-generated interactive API documentation           |
| Containerization | Docker + Docker Compose         | Consistent environments, easy local dev setup          |

### Front-End

| Component        | Choice                          | Why                                                    |
|------------------|---------------------------------|--------------------------------------------------------|
| Framework        | React 18                        | Most popular UI library, huge ecosystem                |
| Language         | TypeScript                      | Type safety catches bugs at compile time               |
| Build tool       | Vite                            | Fast dev server and builds, modern default for React   |
| Routing          | React Router v6                 | Standard routing for React SPAs                        |
| State management | React Query (TanStack Query)    | Server state management, caching, auto-refetching      |
| HTTP client      | Axios                           | Clean API for HTTP requests, interceptors for auth     |
| Styling          | Tailwind CSS                    | Utility-first CSS, fast to prototype, consistent look  |
| Forms            | React Hook Form                 | Performant form handling with validation               |
| Markdown editor  | react-markdown + rehype-sanitize | Render stored Markdown to sanitized HTML; sanitize strips dangerous tags before DOM injection |

---

## 1. Overall Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        Client                           │
│                                                         │
│   React + TypeScript SPA (served as static files)       │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│   │  Pages   │ │Components│ │  Hooks   │ │ Services │  │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
│                        │                                │
│                   HTTP/REST                              │
└────────────────────────┼────────────────────────────────┘
                         │
┌────────────────────────┼────────────────────────────────┐
│                   API Gateway                            │
│              (Spring Security Filter Chain)              │
│    ┌──────────────┐  ┌──────────┐  ┌──────────────┐     │
│    │Session Filter│  │CSRF Check│  │Rate Limiting │     │
│    └──────────────┘  └──────────┘  └──────────────┘     │
└────────────────────────┼────────────────────────────────┘
                         │
┌────────────────────────┼────────────────────────────────┐
│                  Spring Boot Application                 │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │                  Controllers                     │    │
│  │  REST endpoints, request validation, responses   │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         │                                │
│  ┌──────────────────────┴──────────────────────────┐    │
│  │                   Services                       │    │
│  │  Business logic, transactions, orchestration     │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         │                                │
│  ┌──────────────────────┴──────────────────────────┐    │
│  │                 Repositories                     │    │
│  │  Data access, custom queries, JPA operations     │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         │                                │
└─────────────────────────┼───────────────────────────────┘
                          │
┌─────────────────────────┼───────────────────────────────┐
│                    PostgreSQL                            │
│                                                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │  Tables  │ │ Indexes  │ │Constraints│ │  JSON    │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Request Flow

1. User interacts with React UI in the browser
2. React sends HTTP request to Spring Boot API (`/api/v1/*`)
3. Spring Security filter chain checks session cookie, CSRF token, and rate limits
4. Controller receives the request, validates input via Bean Validation
5. Controller calls the appropriate service method
6. Service executes business logic, coordinates across repositories
7. Repository reads/writes PostgreSQL via JPA/Hibernate
8. Response flows back: Repository → Service → Controller → JSON → React → UI

### Monorepo Structure

Both front-end and back-end live in one repository:

```
blog-platform/
├── backend/                          (Spring Boot application)
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/
├── frontend/                         (React application)
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── src/
├── docker-compose.yml                (PostgreSQL + Redis + full stack for local dev)
├── Dockerfile.backend
├── Dockerfile.frontend
└── docs/
```

---

## 2. Back-End Structure

### Package Layout (Package-by-Feature)

```
backend/src/main/java/com/blogplatform/
├── BlogPlatformApplication.java
├── config/
│   ├── SecurityConfig.java           Session config, CSRF, CORS, auth entry points
│   ├── WebConfig.java                CORS mappings for React dev server
│   ├── AuditConfig.java              Enables JPA auditing (@CreatedDate, etc.)
│   ├── RateLimitConfig.java          Global rate limiting with Bucket4j (tiered by user type)
│   └── RedisConfig.java              Redis connection for Spring Session
├── user/
│   ├── UserAccount.java              Entity: account_id, username, email, password_hash,
│   │                                   role, is_vip, vip_start_date, vip_end_date,
│   │                                   two_factor_enabled, email_verified, created_at
│   ├── UserProfile.java              Entity: profile_id, first_name, last_name, bio,
│   │                                   profile_pic_url, last_login, login_count
│   │                                   Note: separate entity from UserAccount to separate
│   │                                   auth data from display data (preserves original
│   │                                   SQL Server schema design)
│   ├── Role.java                     Enum: ADMIN, AUTHOR, USER
│   ├── UserController.java           GET /api/v1/users/{id}, PUT /api/v1/users/{id},
│   │                                   GET /api/v1/users/{id}/saved-posts,
│   │                                   POST /api/v1/users/{id}/upgrade-vip
│   ├── UserService.java              Profile CRUD, VIP upgrade orchestration
│   ├── UserRepository.java           JPA repository, existsByUsername(), existsByEmail()
│   └── dto/
│       ├── UserProfileResponse.java  Public profile data (no password, no internal IDs)
│       ├── UpdateProfileRequest.java Validated input for profile updates
│       └── VipUpgradeRequest.java    Payment details for VIP upgrade
├── auth/
│   ├── AuthController.java           POST /api/v1/auth/register, /login, /logout,
│   │                                   GET /api/v1/auth/me (current user),
│   │                                   POST /api/v1/auth/forgot-password,
│   │                                   POST /api/v1/auth/reset-password,
│   │                                   POST /api/v1/auth/verify-email
│   ├── AuthService.java              Registration, password hashing, session creation,
│   │                                   password reset tokens, email verification tokens
│   ├── PasswordResetToken.java       Entity: token_hash (unique, SHA-256), account_id FK,
│   │                                   expires_at, used (boolean). Single-use, 30-minute expiry.
│   │                                   Token: 32 bytes cryptographically random, URL-safe base64.
│   │                                   Stored as SHA-256 hash; plaintext sent to user via email only.
│   ├── EmailVerificationToken.java   Entity: token_hash (unique, SHA-256), account_id FK,
│   │                                   expires_at, used (boolean). Single-use, 30-minute expiry.
│   │                                   Same token spec as PasswordResetToken.
│   └── dto/
│       ├── RegisterRequest.java      username, email, password (validated)
│       ├── LoginRequest.java         username, password
│       ├── AuthResponse.java         user info + role returned after login
│       ├── ForgotPasswordRequest.java email
│       └── ResetPasswordRequest.java  token, new_password
├── author/
│   ├── AuthorProfile.java            Entity: author_id, biography, social_links (JSON),
│   │                                   expertise, account_id FK
│   ├── AuthorController.java         GET /api/v1/authors, GET /api/v1/authors/{id}
│   ├── AuthorService.java            Author listing, profile with post aggregation
│   └── AuthorRepository.java
├── post/
│   ├── BlogPost.java                 Entity: post_id, title, content, author_id FK,
│   │                                   category_id FK, is_premium, is_deleted,
│   │                                   created_at, updated_at
│   ├── PostUpdateLog.java            Entity: log_id, post_id, old_title, new_title,
│   │                                   old_content, new_content, updated_at
│   ├── ReadPost.java                 Entity: account_id + post_id composite key
│   ├── SavedPost.java                Entity: account_id + post_id, saved_at
│   ├── PostController.java           Full CRUD + save/unsave + list with filters
│   ├── PostService.java              CRUD, soft delete, premium access check,
│   │                                   read tracking, subscriber notification
│   ├── PostRepository.java           Pagination, filtering, custom count queries
│   ├── PostUpdateLogRepository.java
│   ├── ReadPostRepository.java
│   ├── SavedPostRepository.java
│   ├── PostEntityListener.java       @PostPersist → writes PostUpdateLog on create only.
│   │                                   Update logging handled in PostService (see Section 6)
│   └── dto/
│       ├── PostListResponse.java     Summary: title, author, category, like/comment counts
│       ├── PostDetailResponse.java   Full post with author, tags, like count
│       ├── CreatePostRequest.java    title, content (@Size(max=100000) ~100KB Markdown),
│       │                               category_id, tag_ids, is_premium
│       └── UpdatePostRequest.java    Same fields, all optional
├── comment/
│   ├── Comment.java                  Entity: comment_id, content, account_id FK,
│   │                                   post_id FK, parent_comment_id (self-ref),
│   │                                   created_at
│   ├── CommentController.java        GET /api/v1/posts/{id}/comments,
│   │                                   POST /api/v1/posts/{id}/comments,
│   │                                   DELETE /api/v1/comments/{id}
│   ├── CommentService.java           Validates read-before-comment, 250-char limit,
│   │                                   builds threaded response, enforces max nesting
│   │                                   depth of 3 levels (re-parents deeper replies)
│   ├── CommentRepository.java        findByPostIdAndParentCommentIsNull() for top-level
│   └── dto/
│       ├── CommentResponse.java      Nested structure with replies list
│       └── CreateCommentRequest.java content, parent_comment_id (optional)
├── like/
│   ├── Like.java                     Entity: like_id, account_id FK, post_id FK,
│   │                                   created_at. Unique(account_id, post_id)
│   ├── LikeController.java          POST + DELETE /api/v1/posts/{id}/likes
│   ├── LikeService.java             Toggle logic, prevents duplicates
│   └── LikeRepository.java          countByPostId(), existsByAccountIdAndPostId()
├── tag/
│   ├── Tag.java                      Entity: tag_id, tag_name (unique)
│   ├── TagController.java           GET /api/v1/tags, POST /api/v1/tags (admin)
│   ├── TagService.java
│   └── TagRepository.java           findByTagNameIn() for bulk lookup
├── category/
│   ├── Category.java                 Entity: category_id, category_name (unique),
│   │                                   description
│   ├── CategoryController.java      GET /api/v1/categories, POST /api/v1/categories (admin)
│   ├── CategoryService.java
│   └── CategoryRepository.java
├── subscription/
│   ├── Subscriber.java               Entity: subscriber_id, account_id FK (unique),
│   │                                   subscribed_at, expiration_date (always null in
│   │                                   Phases 1–4: subscriptions are free and permanent;
│   │                                   field exists for Phase 5+ VIP-tied scenarios)
│   ├── SubscriptionController.java  POST + DELETE /api/v1/subscriptions
│   ├── SubscriptionService.java     Subscribe/unsubscribe, expiration check
│   └── SubscriberRepository.java    findAllActiveSubscribers()
├── payment/                          ⚠️  STUB — deferred to Phase 5+. No real payment gateway
│   │                                   is wired in Phases 1–4. The endpoint exists but returns
│   │                                   501 Not Implemented. See Deferred Features.
│   ├── Payment.java                  Entity: payment_id, account_id FK, amount,
│   │                                   payment_method (enum), transaction_id (unique),
│   │                                   payment_date
│   ├── PaymentMethod.java           Enum: CREDIT_CARD, PAYPAL, BANK_TRANSFER
│   ├── PaymentController.java       POST /api/v1/users/{id}/upgrade-vip → 501 stub
│   ├── PaymentService.java          Stub: throws NotImplementedException
│   └── PaymentRepository.java
├── notification/
│   ├── Notification.java             Entity: notification_id, account_id FK, message,
│   │                                   is_read, created_at
│   ├── NotificationController.java  GET /api/v1/notifications,
│   │                                   PUT /api/v1/notifications/{id}/read
│   ├── NotificationService.java     notifySubscribers() (@Async, event-driven): batch saveAll(),
│   │                                   try-catch with ERROR logging (post ID, count, error),
│   │                                   post ID logged on batch failure for manual re-notification.
│   │                                   markAsRead(). cleanupOldNotifications(): scheduled job
│   │                                   deletes read notifications older than 90 days.
│   └── NotificationRepository.java  findByAccountIdOrderByCreatedAtDesc().
│                                      Composite index on (account_id, is_read, created_at)
├── image/
│   ├── Image.java                    Entity: image_id, post_id FK, image_url,
│   │                                   alt_text, uploaded_at
│   ├── ImageController.java         POST /api/v1/posts/{id}/images,
│   │                                   DELETE /api/v1/images/{id}
│   ├── ImageService.java            Upload to local filesystem (→ S3 in cloud phase).
│   │                                   Constraints: 5 MB max, JPEG/PNG/WebP only,
│   │                                   filename sanitization, 100 MB per-user quota
│   └── ImageRepository.java
└── common/
    ├── exception/
    │   ├── GlobalExceptionHandler.java   @RestControllerAdvice, maps exceptions to
    │   │                                   HTTP status codes with consistent JSON
    │   ├── ResourceNotFoundException.java  → 404
    │   ├── UnauthorizedException.java      → 401
    │   ├── ForbiddenException.java         → 403
    │   └── BadRequestException.java        → 400
    ├── dto/
    │   ├── ApiResponse.java              Wrapper: { success, data, message, timestamp }
    │   └── PagedResponse.java            Wrapper: { content, page, size, totalElements,
    │                                       totalPages, last }
    └── audit/
        └── AuditableEntity.java          Base class with @CreatedDate, @LastModifiedDate
```

---

## 3. Front-End Structure

```
frontend/src/
├── main.tsx                          App entry point
├── App.tsx                           Root component, routing setup
├── api/
│   ├── client.ts                     Axios instance with base URL, interceptors
│   ├── auth.ts                       login(), register(), logout(), getCurrentUser()
│   ├── posts.ts                      getPosts(), getPost(), createPost(), etc.
│   ├── comments.ts                   getComments(), addComment(), deleteComment()
│   ├── likes.ts                      likePost(), unlikePost()
│   ├── users.ts                      getProfile(), updateProfile(), upgradeToVip()
│   ├── categories.ts                 getCategories()
│   ├── tags.ts                       getTags()
│   ├── notifications.ts              getNotifications(), markAsRead()
│   └── subscriptions.ts              subscribe(), unsubscribe()
├── components/
│   ├── layout/
│   │   ├── Header.tsx                Nav bar, user menu, notifications bell
│   │   ├── Footer.tsx
│   │   └── Layout.tsx                Page wrapper with header/footer
│   ├── posts/
│   │   ├── PostCard.tsx              Post preview card for listing pages
│   │   ├── PostDetail.tsx            Full post view — renders Markdown via react-markdown +
│   │                               rehype-sanitize (never raw dangerouslySetInnerHTML)
│   │   ├── PostForm.tsx              Create/edit post form (authors) — Markdown textarea with
│   │                               live preview pane using react-markdown + rehype-sanitize
│   │   ├── PostFilters.tsx           Category, tag, author filter controls
│   │   └── PremiumBadge.tsx          VIP-only content indicator
│   ├── comments/
│   │   ├── CommentList.tsx           Threaded comment display
│   │   ├── CommentItem.tsx           Single comment with reply button
│   │   └── CommentForm.tsx           Add comment textarea
│   ├── auth/
│   │   ├── LoginForm.tsx
│   │   ├── RegisterForm.tsx
│   │   └── ProtectedRoute.tsx        Redirects unauthenticated users to login
│   ├── users/
│   │   ├── ProfileCard.tsx           User profile display
│   │   └── ProfileEditForm.tsx
│   └── common/
│       ├── Pagination.tsx
│       ├── LoadingSpinner.tsx
│       ├── ErrorMessage.tsx
│       └── ConfirmDialog.tsx
├── hooks/
│   ├── useAuth.ts                    Auth context: current user, login state
│   ├── usePosts.ts                   React Query hooks for post CRUD
│   ├── useComments.ts                React Query hooks for comments
│   └── useNotifications.ts           React Query hooks + polling (30s interval, exponential backoff on error)
├── pages/
│   ├── HomePage.tsx                  Post feed with filters and pagination
│   ├── PostPage.tsx                  Single post view + comments + likes
│   ├── CreatePostPage.tsx            Post editor (authors only)
│   ├── EditPostPage.tsx              Edit existing post (authors only)
│   ├── LoginPage.tsx
│   ├── RegisterPage.tsx
│   ├── ProfilePage.tsx               User's own profile
│   ├── AuthorPage.tsx                Public author profile + their posts
│   ├── SavedPostsPage.tsx            User's bookmarked posts
│   ├── NotificationsPage.tsx         Notification list
│   └── AdminDashboard.tsx            Category/tag management (admin only)
├── context/
│   └── AuthContext.tsx               React context for auth state across the app
├── types/
│   ├── post.ts                       TypeScript interfaces: Post, PostSummary, etc.
│   ├── user.ts                       User, UserProfile, AuthorProfile
│   ├── comment.ts                    Comment (recursive for threading)
│   ├── notification.ts               Notification
│   └── api.ts                        ApiResponse<T>, PagedResponse<T>
└── utils/
    ├── formatDate.ts                 Date formatting helpers
    └── truncateText.ts               Text truncation for post previews
```

### Key Front-End Patterns

- **React Query** manages all server state — handles caching, background refetching, and loading/error states automatically
- **Axios interceptors** handle 401 responses globally (redirect to login)
- **AuthContext** provides current user state to all components without prop drilling
- **ProtectedRoute** component wraps pages that require authentication
- **TypeScript interfaces** mirror the back-end DTOs for type safety across the stack
- **Vite proxy** configured in `vite.config.ts` to forward `/api` requests to `http://localhost:8080` during development — eliminates cross-origin cookie issues without requiring `SameSite=None` or CORS credential gymnastics. In production, Nginx handles the same proxying.

---

## 4. Entity / Data Model

### Entity Relationship Summary

```
UserAccount ──1:1──> UserProfile
UserAccount ──N:1──> Role (enum)
UserAccount ──1:1──> AuthorProfile (optional, for AUTHOR role)
UserAccount ──1:1──> Subscriber (optional)
UserAccount ──1:N──> Payment
UserAccount ──1:N──> Notification
UserAccount ──N:M──> BlogPost (via ReadPost)
UserAccount ──N:M──> BlogPost (via SavedPost)
UserAccount ──1:N──> Comment
UserAccount ──1:N──> Like

BlogPost ──N:1──> UserAccount (author)
BlogPost ──N:1──> Category
BlogPost ──N:M──> Tag (via post_tags join table)
BlogPost ──1:N──> Comment
BlogPost ──1:N──> Like
BlogPost ──1:N──> Image
BlogPost ──1:N──> PostUpdateLog

Comment ──N:1──> Comment (parent, self-referencing for threading)
```

### SQL Server → PostgreSQL Migration Mapping

| SQL Server Feature | PostgreSQL Equivalent |
|---|---|
| `IDENTITY(seed, increment)` | `SERIAL` or `BIGSERIAL` (auto-increment) |
| `BIT` | `BOOLEAN` |
| `NVARCHAR(n)` | `VARCHAR(n)` |
| `NVARCHAR(MAX)` | `TEXT` |
| `DATETIME` | `TIMESTAMP` |
| `GETDATE()` | `NOW()` |
| `UNIQUEIDENTIFIER` | `UUID` |
| T-SQL stored procedures | Java service methods |
| T-SQL triggers | JPA entity listeners |
| T-SQL functions | Spring Data repository queries |

### JPA Migration Patterns

| SQL Server Concept | Java/JPA Equivalent | Example |
|---|---|---|
| `IDENTITY` | `@GeneratedValue(strategy = IDENTITY)` | Auto-increment primary keys |
| `CHECK` constraints | Bean Validation annotations | `@Size(max=250)`, `@Positive`, `@PastOrPresent` |
| `DEFAULT getdate()` | `@CreatedDate`, `@LastModifiedDate` | JPA Auditing handles timestamps |
| `UNIQUE` index | `@Column(unique = true)` or `@Table(uniqueConstraints)` | username, email, tag_name |
| Foreign keys | `@ManyToOne`, `@OneToMany`, `@ManyToMany` | JPA manages relationships and cascades |
| Nonclustered indexes | `@Table(indexes = @Index(...))` | Indexes on foreign keys for query performance |
| Views | Repository `@Query` methods returning DTO projections | Custom JPQL or native SQL queries |
| Triggers | `@EntityListeners` with `@PostPersist`, `@PostUpdate` | PostUpdateLog written on entity events |
| Stored procedures | `@Service` methods with `@Transactional` | Business logic in Java, not SQL |

### Entities by Feature

#### User & Auth

| SQL Server Table | Java Entity | Key Mappings |
|---|---|---|
| UserAccount | `UserAccount.java` | password → BCrypt hash, role → enum, is_vip/vip_start/vip_end preserved, `email_verified BOOLEAN DEFAULT false` |
| UserProfile | `UserProfile.java` | `@OneToOne` with UserAccount, cascade ALL. Separate entity to isolate auth data from display data (preserves original SQL Server schema design). |
| Role | `Role.java` (enum) | ADMIN, AUTHOR, USER — no table needed, stored as string in UserAccount |

#### Content

| SQL Server Table | Java Entity | Key Mappings |
|---|---|---|
| BlogPosts | `BlogPost.java` | Extends AuditableEntity, is_deleted for soft delete, `@ManyToOne` category, `@ManyToMany` tags |
| Categories | `Category.java` | category_name unique, `@OneToMany` posts |
| Tags | `Tag.java` | tag_name unique, `@ManyToMany` posts (mappedBy) |
| PostTags | No entity | JPA manages join table via `@ManyToMany` annotation |
| Images | `Image.java` | `@ManyToOne` BlogPost, image_url + alt_text |
| PostUpdateLog | `PostUpdateLog.java` | Written by PostEntityListener, immutable audit trail |

#### Engagement

| SQL Server Table | Java Entity | Key Mappings |
|---|---|---|
| Comments | `Comment.java` | `@ManyToOne` self-reference (parent_comment_id), `@Size(max=250)` on content, max nesting depth: 3 levels |
| Likes | `Like.java` | `@Table(uniqueConstraints)` on (account_id, post_id) |
| ReadPost | `ReadPost.java` | Composite key (account_id, post_id) via `@IdClass` |
| SavedPosts | `SavedPost.java` | Composite key (account_id, post_id) via `@IdClass` |

#### Monetization & Communication

| SQL Server Table | Java Entity | Key Mappings |
|---|---|---|
| AuthorProfile | `AuthorProfile.java` | social_links as `@JdbcTypeCode(SqlTypes.JSON)` for native PostgreSQL JSON |
| Payment | `Payment.java` | payment_method → PaymentMethod enum, `@Positive` on amount, transaction_id unique |
| Subscriber | `Subscriber.java` | `@OneToOne` with UserAccount, expiration_date nullable (always null in Phases 1–4: subscriptions are free and permanent; field exists for Phase 5+ VIP-tied or time-limited scenarios) |
| Notifications | `Notification.java` | Created by NotificationService, is_read boolean. Composite index on `(account_id, is_read, created_at)`. Retention: read notifications older than 90 days auto-deleted by scheduled cleanup job. |

---

## 5. API Endpoints

### Auth
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/v1/auth/register` | Public | Create account (username, email, password). Sends verification email. |
| POST | `/api/v1/auth/login` | Public | Authenticate, create session, return user info |
| POST | `/api/v1/auth/logout` | Authenticated | Destroy session, invalidate cookie |
| GET | `/api/v1/auth/me` | Authenticated | Return current user info (used by React on page load) |
| POST | `/api/v1/auth/forgot-password` | Public | Accept email, send time-limited reset token via transactional email. Always returns the same 200 response ("If an account exists, a reset email was sent") regardless of whether the email exists — prevents user enumeration |
| POST | `/api/v1/auth/reset-password` | Public | Accept token + new password, reset if token valid and email verified |
| POST | `/api/v1/auth/verify-email` | Public | Accept verification token, set `email_verified = true` |

### Users
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/users/{id}` | Authenticated | Get user public profile |
| PUT | `/api/v1/users/{id}` | Owner only | Update own profile (first_name, last_name, bio, pic) |
| GET | `/api/v1/users/{id}/saved-posts` | Owner only | List saved/bookmarked posts (paginated) |
| POST | `/api/v1/users/{id}/upgrade-vip` | Owner only | **STUB — returns 501.** Payment gateway deferred to Phase 5+. |

### Posts
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/posts` | Public | List posts (paginated, filterable) |
| GET | `/api/v1/posts/{id}` | Public* | Get single post (marks as read). *Premium → VIP only |
| POST | `/api/v1/posts` | AUTHOR, ADMIN | Create new post |
| PUT | `/api/v1/posts/{id}` | Owner, ADMIN | Update post |
| DELETE | `/api/v1/posts/{id}` | Owner, ADMIN | Soft-delete post (sets is_deleted = true) |
| POST | `/api/v1/posts/{id}/save` | Authenticated | Bookmark a post |
| DELETE | `/api/v1/posts/{id}/save` | Authenticated | Remove bookmark |

**Query parameters for GET /api/v1/posts:**
- `?page=0&size=20` — pagination (default page=0, size=20; maximum size=100 enforced server-side via `PageableHandlerMethodArgumentResolver`)
- `?category=5` — filter by category ID
- `?tag=java` — filter by tag name
- `?author=3` — filter by author ID
- `?search=spring+boot` — full-text search on title and content using PostgreSQL `tsvector/tsquery` with GIN indexes for performance and relevance ranking

### Comments
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/posts/{postId}/comments` | Public | Get threaded comments for a post |
| POST | `/api/v1/posts/{postId}/comments` | Authenticated | Add comment (must have read the post first) |
| DELETE | `/api/v1/comments/{id}` | Owner, ADMIN | Delete a comment |

### Likes
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/v1/posts/{postId}/likes` | Authenticated | Like a post (idempotent, no error if already liked) |
| DELETE | `/api/v1/posts/{postId}/likes` | Authenticated | Unlike a post |

### Admin
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/admin/posts/deleted` | ADMIN | List soft-deleted posts (filter disabled, shows is_deleted=true posts) |
| PUT | `/api/v1/admin/posts/{id}/restore` | ADMIN | Restore a soft-deleted post (sets is_deleted=false) |
| PUT | `/api/v1/admin/users/{id}/role` | ADMIN | Assign role to user (e.g., promote USER to AUTHOR) |

### Categories
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/categories` | Public | List all categories |
| POST | `/api/v1/categories` | ADMIN | Create a category |
| PUT | `/api/v1/categories/{id}` | ADMIN | Update a category |
| DELETE | `/api/v1/categories/{id}` | ADMIN | Delete a category |

### Tags
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/tags` | Public | List all tags |
| POST | `/api/v1/tags` | ADMIN | Create a tag |

### Authors
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/authors` | Public | List all authors with post counts |
| GET | `/api/v1/authors/{id}` | Public | Get author profile + their posts (paginated) |

### Subscriptions
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/v1/subscriptions` | Authenticated | Subscribe to new post notifications |
| DELETE | `/api/v1/subscriptions` | Authenticated | Unsubscribe |

### Notifications
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/v1/notifications` | Authenticated | Get own notifications (paginated, newest first). Controller extracts `account_id` from security context and passes to repository — never accepts `account_id` as a request parameter |
| PUT | `/api/v1/notifications/{id}/read` | Owner only | Mark a notification as read |
| PUT | `/api/v1/notifications/read-all` | Authenticated | Mark all unread notifications as read (scoped: `WHERE is_read = false`) |

### Images
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/v1/posts/{postId}/images` | AUTHOR, ADMIN | Upload image for a post |
| DELETE | `/api/v1/images/{id}` | Owner, ADMIN | Delete an image |

### Response Format

All endpoints return a consistent JSON structure:

**Success:**
```json
{
  "success": true,
  "data": { ... },
  "message": "Post created successfully",
  "timestamp": "2026-02-27T14:30:00Z"
}
```

**Error:**
```json
{
  "success": false,
  "data": null,
  "message": "You must read a post before commenting on it",
  "timestamp": "2026-02-27T14:30:00Z"
}
```

**Paginated:**
```json
{
  "success": true,
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 142,
    "totalPages": 8,
    "last": false
  },
  "message": null,
  "timestamp": "2026-02-27T14:30:00Z"
}
```

---

## 6. Business Logic Migration

### Stored Procedures → Service Methods

| SQL Procedure | Java Service Method | Migration Details |
|---|---|---|
| `SP_Add_Comment` | `CommentService.addComment()` | 1. Check ReadPost exists for (user, post) → 403 if not (opening the post page satisfies this — original SP behavior). 2. Validate content ≤ 250 chars via `@Size`. 3. If parent_comment_id provided, verify parent exists and belongs to same post. 4. Enforce max nesting depth of 3 levels: walk up parent chain, if depth exceeds 3, re-parent to the deepest allowed ancestor. 5. Save Comment entity. |
| `SP_Upgrade_User_To_VIP` | `PaymentService.upgradeToVip()` | ⚠️ **STUB in Phases 1–4** — returns 501. Future: Stripe webhook confirmation → set VIP flags server-side. No client-submitted payment data accepted. |
| `SP_Create_Post_Notifications` | `NotificationService.notifySubscribers()` | Triggered asynchronously via `@TransactionalEventListener(phase = AFTER_COMMIT)` after PostService.createPost() commits. Runs in a background thread (`@Async`). Queries all active subscribers (expiration_date null or > now). Creates a Notification for each: "New post: {title} by {author}". Decoupled from the post creation transaction to prevent subscriber count from affecting post creation latency. |
| `SP_Backup_All_DB` | Not migrated | Database backups handled by pg_dump cron job on the server (or AWS RDS automated backups in cloud phase). |
| `SP_Backup_Database` | Not migrated | Same as above. |

### Triggers → JPA Event Listeners & Service Logic

| SQL Trigger | Java Equivalent | Migration Details |
|---|---|---|
| `TR_BlogPosts_Insert_Log` | `PostEntityListener.postPersist()` | `@PostPersist`: creates a PostUpdateLog entry with the new post's title and content. |
| `TR_BlogPosts_Update_Log` | `PostService.updatePost()` | Service layer loads the existing post from the database before applying changes, captures old title/content, applies updates, then writes a `PostUpdateLog` with both old and new values. **Note:** `@PreUpdate` is not used for this — JPA entity listeners receive the entity in its already-modified state and cannot reliably capture old values without reading from the database first. |
| `TR_BlogPosts_Delete_Log` | `PostService.deletePost()` | Service method sets `is_deleted = true` and `updated_at = now`. The post remains in the database. A Hibernate `@FilterDef` / `@Filter` named `activePostsFilter` (clause: `is_deleted = false`) is enabled by default for all public queries. Admin queries explicitly disable the filter to expose soft-deleted posts for the admin restore endpoint. This replaces the former `@Where` annotation, which was a global, non-toggleable filter that prevented admin access to deleted posts. |
| `TR_Notify_Subscribers_On_New_Post` | `PostService.createPost()` publishes `NewPostEvent` | After the post transaction commits, Spring's `@TransactionalEventListener` triggers `NotificationService.notifySubscribers()` asynchronously in a background thread. Decoupled from the post creation transaction — post creation is fast regardless of subscriber count. |

### Functions → Repository Queries

| SQL Function | Java Equivalent | Implementation |
|---|---|---|
| `SF_Get_Like_Count_By_Post` | `LikeRepository.countByPostId(Long postId)` | Spring Data derived query — no SQL needed |
| `SF_Get_Post_Count_By_Category` | `PostRepository.countByCategoryId(Long categoryId)` | Spring Data derived query |
| `SF_Check_Account_Exists` | `UserRepository.existsByUsername(String username)` | Spring Data derived query, returns boolean |
| `TF_Get_Most_Like_Post_By_Category` | `PostRepository.findMostLikedByCategory(Long categoryId)` | Custom `@Query` with JOIN and COUNT, ORDER BY count DESC, LIMIT 1 |
| `TF_Get_Posts_By_Author` | `PostRepository.findByAuthorId(Long authorId, Pageable pageable)` | Spring Data derived query with pagination |
| `TF_Get_Tags_By_Post` | `blogPost.getTags()` | Loaded automatically via `@ManyToMany` relationship |
| `TF_Get_Comment_And_Like_Count` | `PostRepository.findPostWithCounts(Long postId)` | Custom `@Query` with COUNT subqueries, returns DTO projection |

### Views → Repository Query Methods

| SQL View | Repository Method | Implementation |
|---|---|---|
| `View_BlogPosts_With_Author_Likes_Comments` | `PostRepository.findAllWithAuthorAndCounts(Pageable)` | JPQL JOIN + COUNT subqueries → PostListResponse DTO |
| `View_Authors_With_More_Than_Two_Posts` | `AuthorRepository.findAuthorsWithMinPosts(int minPosts)` | `@Query("SELECT a FROM AuthorProfile a WHERE SIZE(a.posts) > :min")` |
| `View_Premium_Posts` | `PostRepository.findByIsPremiumTrue(Pageable)` | Spring Data derived query |
| `View_Recent_Comments` | `CommentRepository.findRecentComments(Pageable)` | `findAllByOrderByCreatedAtDesc(Pageable)` |
| Other views | Similar repository methods | Each view maps to a query method with appropriate DTO projection |

### Stored Procedure Validation Checklist

Each stored procedure's business rules must be covered by specific test cases to ensure migration correctness.

| SQL Stored Procedure | Java Equivalent | Business Rules to Validate | Test Cases |
|---|---|---|---|
| `SP_Add_Comment` | `CommentService.addComment()` | 1. Post must exist and not be deleted. 2. User must have read the post (opening the post page satisfies this). 3. Comment text must not be empty. 4. Comment text ≤ 250 chars. 5. Parent comment must exist and belong to same post. 6. Max nesting depth: 3 levels — deeper replies re-parented to deepest allowed ancestor. | Unit: mock ReadPost lookup → reject if not read. Unit: content > 250 chars → validation error. Unit: reply at depth 4 → re-parented to depth 3. Integration: full comment flow with threading. |
| `SP_Upgrade_User_To_VIP` | `PaymentService.upgradeToVip()` | ⚠️ **STUB in Phases 1–4.** Returns 501. Full business rules deferred to Phase 5+ with Stripe integration. | Stub test: endpoint returns 501. Phase 5+: webhook sets VIP flags, duplicate webhook idempotency. |
| `SP_Create_Post_Notifications` | `NotificationService.notifySubscribers()` | 1. Only active subscribers notified (expiration_date null or > now). 2. Expired subscribers skipped. 3. Notification message includes post title and author name. 4. Runs async — does not block post creation. 5. All notifications batch-inserted via single `saveAll()`. 6. Any exception caught, logged at ERROR level with post ID and subscriber count. Post ID logged to allow manual re-notification. | Unit: N active + M expired subscribers → N notifications created. Unit: batch failure → ERROR logged with post ID. Integration: create post → verify notifications exist after async processing. |
| `SP_Backup_All_DB` / `SP_Backup_Database` | pg_dump cron job | Not migrated to Java. Validated by ops: verify pg_dump runs on schedule, backups are restorable. | Monthly automated verification: restore latest backup to temporary Docker PostgreSQL container, run integrity checks (row counts on key tables), destroy container. |

### Full-Text Search Strategy

Full-text search on `GET /api/v1/posts?search=` uses PostgreSQL's native full-text search:

- **Implementation:** `tsvector` column on `BlogPost` (combining title and content), queried with `tsquery`
- **Index:** GIN index on the `tsvector` column for fast lookups
- **Flyway migration:** Adds a `search_vector` column of type `tsvector`, a GIN index, and a trigger to keep it updated on INSERT/UPDATE. The trigger strips Markdown syntax (removes `##`, `**`, `_`, backtick fences, and link syntax) before building the `tsvector` so that formatting characters do not appear in search indexes or results
- **Repository:** Custom `@Query` using `plainto_tsquery()` for user-friendly search input
- **Relevance:** Results ranked by `ts_rank()` for relevance ordering
- **Why not ILIKE:** `ILIKE` requires a sequential scan and does not scale. `tsvector/tsquery` with GIN indexes provides sub-millisecond lookups regardless of table size.

### Image Upload Constraints

| Constraint | Value | Implementation |
|---|---|---|
| Maximum file size | 5 MB | `spring.servlet.multipart.max-file-size=5MB` in application.yml |
| Allowed MIME types | JPEG, PNG, WebP | Validated in `ImageService` before saving — reject others with 400. Validation checks both `Content-Type` header and file magic bytes (file signature) using Apache Tika to prevent content-type spoofing and polyglot file attacks |
| Filename sanitization | Strip path separators, special chars, use UUID-based filenames | `ImageService` generates `{uuid}.{ext}` to prevent path traversal |
| Per-user storage quota | 100 MB | `ImageService` tracks cumulative upload size per user, rejects when exceeded |
| Disk usage alert | 70% threshold | Cron job or health check alerts when VPS disk usage exceeds 70% |

### Rate Limiting Strategy

Global rate limiting using Bucket4j, applied via a servlet filter in the Spring Security filter chain.

| User Type | Limit | Scope |
|---|---|---|
| Anonymous (no session) | 60 requests/minute | Per IP address |
| Authenticated user | 120 requests/minute | Per user account |
| Auth endpoints (`/api/v1/auth/**`) | 10 requests/minute | Per IP address (prevents brute force) |

- Bucket4j integrates with Spring Boot and supports in-memory token buckets
- Rate limit headers (`X-Rate-Limit-Remaining`, `Retry-After`) included in responses
- Exceeded limits return `429 Too Many Requests`

**Per-account login lockout** (defense-in-depth against distributed brute force):
- Track consecutive failed login attempts per username in Redis (key: `login:failures:{username}`, TTL: 15 minutes)
- After 5 consecutive failures, lock the account for 15 minutes — return `423 Locked` with a message indicating temporary lockout
- Reset the failure counter on successful login
- Log all failed login attempts with username and IP address for anomaly detection

### Connection Pool Configuration

HikariCP (Spring Boot default) manages the database connection pool.

| Setting | Value | Rationale |
|---|---|---|
| `maximumPoolSize` | 10 (default) | Appropriate for a 2 vCPU VPS. Rule of thumb: `(2 * CPU cores) + disk spindles` |
| `minimumIdle` | 10 (same as max) | HikariCP recommendation: keep pool full to avoid connection creation latency |
| `connectionTimeout` | 30000 ms | Default. Time to wait for a connection from the pool before throwing an exception |
| `idleTimeout` | 600000 ms | Default. Connections idle longer than this are retired |

Async notifications reduce pool contention — notification INSERTs no longer happen inside the post creation transaction.

---

## 7. Authentication & Security

### Authentication Architecture

```
Browser                     Spring Boot
  │                            │
  │  POST /api/v1/auth/login      │
  │  { username, password }    │
  │ ──────────────────────────>│
  │                            │  1. AuthService.login()
  │                            │  2. Load UserAccount by username
  │                            │  3. BCrypt.matches(password, hash)
  │                            │  4. Create HttpSession
  │                            │  5. Store user details in session
  │  Set-Cookie: JSESSIONID    │
  │ <──────────────────────────│
  │                            │
  │  GET /api/v1/posts            │
  │  Cookie: JSESSIONID=abc    │
  │ ──────────────────────────>│
  │                            │  1. SessionFilter extracts cookie
  │                            │  2. Look up session in Redis
  │                            │  3. Load SecurityContext (user + roles)
  │                            │  4. Proceed to controller
  │  200 OK + posts data       │
  │ <──────────────────────────│
```

### Spring Security Configuration

```
SecurityFilterChain:
  1. CORS filter          → Allow requests from React dev server (localhost:5173)
  2. CSRF filter          → Validate CSRF token on state-changing requests
  3. Session filter       → Extract session from JSESSIONID cookie
  4. Authentication       → Load user from session into SecurityContext
  5. Authorization        → Check @PreAuthorize rules on the endpoint
  6. Rate limit filter    → Bucket4j global rate limiting (tiered: anon 60/min, auth 120/min, login 10/min)
```

### Password Security

- Hashed with **BCrypt** (work factor 12)
- Never stored or transmitted in plaintext
- Password requirements enforced by Bean Validation:
  - Minimum 8 characters
  - At least one uppercase, one lowercase, one digit

### CSRF Protection

- Spring Security generates a CSRF token per session
- Token sent to React via a cookie (`XSRF-TOKEN`) using `CookieCsrfTokenRepository.withHttpOnlyFalse()` — the `withHttpOnlyFalse()` is required so JavaScript can read the cookie; without it, the cookie is HttpOnly and Axios cannot access it, silently breaking all POST/PUT/DELETE requests
- React's Axios interceptor reads the cookie and sends the token in `X-XSRF-TOKEN` header
- All POST/PUT/DELETE requests validated against this token

### CORS Configuration

- Development: allow `http://localhost:5173` (Vite dev server)
- Production: allow only the actual domain
- Credentials (cookies) allowed in cross-origin requests
- `Access-Control-Max-Age: 3600` — caches preflight (OPTIONS) results for 1 hour, preventing duplicate preflight requests on every mutation

### Session Management

- Sessions stored in **Redis** via Spring Session Data Redis
- Redis runs as a Docker container alongside the application (included in Docker Compose)
- Redis configured with `requirepass` — password stored in `.env`, referenced via `spring.data.redis.password` in `application.yml`
- Session timeout: 30 minutes of inactivity
- Session fixation protection: create new session on login
- Single session per user (optional, can be relaxed)
- Sessions persist across application restarts and redeploys — no user-visible disruption during deployments
- Direct upgrade path to AWS ElastiCache Redis when migrating to cloud (same protocol, change connection string only)

### Authorization Rules

| Endpoint Pattern | Rule |
|---|---|
| `POST /api/v1/auth/**` | Public (permitAll) — includes register, login, forgot-password, reset-password, verify-email |
| `GET /api/v1/posts`, `GET /api/v1/posts/{id}` | Public |
| `GET /api/v1/categories`, `GET /api/v1/tags` | Public |
| `GET /api/v1/authors`, `GET /api/v1/authors/{id}` | Public |
| `POST /api/v1/posts` | `hasRole('AUTHOR')` or `hasRole('ADMIN')` |
| `PUT/DELETE /api/v1/posts/{id}` | Owner or `hasRole('ADMIN')` |
| `POST /api/v1/posts/{id}/comments` | Authenticated + must have read the post |
| `POST/DELETE /api/v1/categories`, `/api/v1/tags` | `hasRole('ADMIN')` |
| `PUT /api/v1/users/{id}` | Owner only |
| Everything else | Authenticated |

### Ownership Verification (IDOR Prevention)

All endpoints marked "Owner only" or "Owner, ADMIN" must verify that the authenticated user owns the target resource. This is enforced via a shared `OwnershipVerifier` service to prevent per-endpoint inconsistencies.

**Pattern:**
- `OwnershipVerifier.verify(resourceOwnerId, authentication)` — throws `AccessDeniedException` if the authenticated user's `account_id` does not match the resource owner and the user does not have `ROLE_ADMIN`
- Controllers extract the current user's `account_id` from `SecurityContextHolder.getContext().getAuthentication()` — never from request parameters or path variables
- Use `@PreAuthorize("@ownershipVerifier.isOwnerOrAdmin(#id, authentication)")` on controller methods where possible

**Affected endpoints:**
- `PUT /api/v1/users/{id}` — compare `{id}` against authenticated user's `account_id`
- `GET /api/v1/users/{id}/saved-posts` — compare `{id}` against authenticated user's `account_id`
- `POST /api/v1/users/{id}/upgrade-vip` — compare `{id}` against authenticated user's `account_id`
- `PUT/DELETE /api/v1/posts/{id}` — load post, compare `post.account_id` against authenticated user
- `DELETE /api/v1/comments/{id}` — load comment, compare `comment.account_id` against authenticated user
- `DELETE /api/v1/images/{id}` — load image, compare `image.account_id` against authenticated user
- `PUT /api/v1/notifications/{id}/read` — load notification, compare `notification.account_id` against authenticated user

### Role Assignment

- **USER** — default role on registration
- **AUTHOR** — admin-assigned only via admin dashboard (`PUT /api/v1/admin/users/{id}/role`). Users cannot self-promote to AUTHOR.
- **ADMIN** — admin-assigned only (initial admin created via seed data)

### Deferred Security Features (YAGNI)

- 2FA — `two_factor_enabled` column kept in schema but not implemented
- OAuth / social login — not needed for initial launch

---

## 8. Testing Strategy

### Testing Pyramid

```
          ╱╲
         ╱  ╲         E2E Tests (few)
        ╱ E2E╲        Cypress: critical user flows
       ╱──────╲
      ╱        ╲      Integration Tests (moderate)
     ╱Integration╲   Testcontainers + MockMvc: API + DB
    ╱──────────────╲
   ╱                ╲  Unit Tests (many)
  ╱   Unit Tests     ╲ JUnit + Mockito: services, utilities
 ╱────────────────────╲
```

### Back-End Testing

#### Unit Tests (JUnit 5 + Mockito)

Test service methods in isolation by mocking repositories and other dependencies.

| Service | Key Unit Tests |
|---|---|
| AuthService | Register with duplicate username → exception. Hash password on register. |
| PostService | Create post → calls notifySubscribers. Soft-delete sets is_deleted. Premium post access denied for non-VIP. |
| CommentService | Reject comment if user hasn't read post. Reject comment over 250 chars. Validate parent comment belongs to same post. |
| LikeService | Prevent duplicate likes. Unlike non-existent like → no error. |
| PaymentService | ⚠️ Stub in Phases 1–4: upgradeToVip returns 501. |
| NotificationService | notifySubscribers creates N notifications for N active subscribers. Skip expired subscribers. |

#### Integration Tests (Testcontainers + Spring Boot Test)

Spin up a real PostgreSQL container and test the full stack from controller to database.

| Area | Key Integration Tests |
|---|---|
| Auth flow | Register → login → access protected endpoint → logout → rejected |
| Post CRUD | Create → read (marked as read) → update (log created) → soft delete (not in listings) |
| Comment threading | Create post → read it → comment → reply to comment → verify thread structure |
| VIP upgrade | ⚠️ Stub test: POST upgrade-vip → 501. Full flow deferred to Phase 5+. |
| Soft delete + admin restore | Delete post (is_deleted=true) → not in public listing → admin GET deleted posts → visible → admin restore → visible in public listing |
| Pagination | Create 25 posts → request page 0 size 10 → verify 10 results, totalPages = 3 |
| Filtering | Create posts in categories → filter by category → correct results |
| Security | Access AUTHOR endpoint as USER → 403. Access own profile → 200. Access other's profile edit → 403. |

#### Test Configuration

- `application-test.yml` profile for test-specific settings
- Testcontainers auto-starts PostgreSQL 16 container
- Each test class gets a fresh database (Flyway migrations applied automatically)
- `@Transactional` on test classes for automatic rollback between tests

### Front-End Testing

| Tool | Purpose | What We Test |
|---|---|---|
| Vitest | Unit testing (fast, Vite-native) | Utility functions, hooks, component rendering |
| React Testing Library | Component testing | User interactions, form validation, conditional rendering |
| MSW (Mock Service Worker) | API mocking | Mock back-end responses for component tests |

| Component | Key Tests |
|---|---|
| LoginForm | Submits credentials, shows error on failure, redirects on success |
| PostCard | Renders title, author, like count. Shows premium badge for premium posts. |
| CommentForm | Disabled if post not read. Shows character count. Submits on enter. |
| ProtectedRoute | Redirects to login if not authenticated. Renders children if authenticated. |
| PostFilters | Filter changes trigger new API call. Category/tag dropdowns populate correctly. |

### End-to-End Tests (Cypress) — Phase 3 only

Run against the full stack (React + Spring Boot + PostgreSQL). Cover critical user journeys:

1. Register → Login → Browse posts → Read a post → Comment → Logout
2. Author: Login → Create post → Edit post → Delete post
3. VIP: Login → Upgrade to VIP → Access premium post

### Test Commands

```bash
# Back-end
./gradlew test                    # All back-end tests
./gradlew test --tests "*Unit*"   # Unit tests only
./gradlew test --tests "*IT*"     # Integration tests only

# Front-end
npm test                          # All front-end tests
npm run test:watch                # Watch mode during development
npm run test:e2e                  # Cypress E2E tests
```

---

## 9. Implementation Phases

### Phase 1: Foundation (Back-End Core)

Set up the project skeleton and core data layer.

- Initialize Gradle project with Spring Boot 3.x, Java 21
- Set up Docker Compose with PostgreSQL 16 and Redis 7
- Configure Flyway and write migration scripts for all 17 tables (including `search_vector` tsvector column with GIN index). Naming convention: `V1__initial_schema.sql` (all tables, indexes, constraints), `V2__seed_data.sql` (default categories, admin user), `R__search_vector_trigger.sql` (repeatable trigger for tsvector updates with Markdown stripping)
- Implement JPA entities for all tables with relationships and validation
- Configure Spring Security with session-based auth (Redis-backed sessions via Spring Session Data Redis)
- Configure Bucket4j global rate limiting (tiered: anonymous 60/min, authenticated 120/min, auth 10/min)
- Implement auth endpoints (register, login, logout, /me) under `/api/v1/`
- Implement Role enum and authorization rules
- Set up GlobalExceptionHandler and ApiResponse wrapper
- Write unit and integration tests for auth flow

**Deliverable:** Running Spring Boot app with auth, Redis sessions, rate limiting, database schema, and all entities.

### Phase 2: Core Features (Back-End API)

Build out the full REST API.

- Post CRUD with soft delete, pagination, filtering, full-text search
- PostEntityListener for audit logging (PostUpdateLog)
- Read tracking (mark posts as read on GET; "must read before commenting" rule — opening the post page satisfies this)
- Comment system with threading, read-before-comment validation, 250-char limit, max nesting depth of 3 levels
- Like/unlike functionality
- Category and tag management
- Saved posts / bookmarking
- Author profiles with JSON social links
- Subscription and async notification system (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`)
- Notification retention: composite index on `(account_id, is_read, created_at)`, scheduled cleanup of read notifications older than 90 days, "mark all as read" scoped to `WHERE is_read = false`
- ReadPost retention: scheduled cleanup of entries older than 1 year
- Password reset: `POST /api/v1/auth/forgot-password` and `POST /api/v1/auth/reset-password` using time-limited single-use tokens via transactional email service (Mailgun, Postmark, or free-tier SendGrid)
- Email verification: `POST /api/v1/auth/verify-email` using same email infrastructure. Required before password reset and VIP upgrade.
- VIP upgrade endpoint (stub — returns 501; Stripe integration deferred to Phase 5+)
- Image upload (local filesystem) with constraints: 5 MB max, JPEG/PNG/WebP only, filename sanitization, 100 MB per-user quota
- `@Size(max = 100000)` on blog post content field (~100 KB Markdown limit)
- SpringDoc OpenAPI / Swagger documentation
- Integration tests for all endpoints
- Stored procedure validation checklist: verify all SP business rules covered by test cases

**Deliverable:** Complete, tested REST API with Swagger docs. All stored procedure business rules validated.

### Phase 3: Front-End

Build the React application.

- Initialize Vite + React + TypeScript project
- Set up Tailwind CSS, React Router, React Query, Axios
- Build auth pages (login, register) + AuthContext
- Build post listing page with filters, pagination, search
- Build post detail page with comments, likes, read tracking
- Build post creation/editing for authors — Markdown textarea with live preview (react-markdown + rehype-sanitize)
- Build user profile page with edit functionality
- Build saved posts page
- Build notifications page (30-second polling interval, exponential backoff on error)
- Build admin dashboard (category/tag management)
- Implement premium content indicators and VIP access
- Component tests with Vitest + React Testing Library
- Cypress E2E tests for critical flows

**Deliverable:** Full working web application (React + Spring Boot + PostgreSQL).

### Phase 4: Production Deployment (VPS)

Deploy to a VPS and prepare for real users. See Section 10 for full deployment details.

- Environment-specific configuration (dev, staging, production)
- Production Dockerfiles for both back-end and front-end
- Docker Compose for full-stack production deployment
- Nginx reverse proxy configuration (serves React static files, proxies /api to Spring Boot)
- HTTPS via Let's Encrypt / Certbot
- UFW firewall configuration (allow only ports 80, 443, 22)
- Logging configuration (structured JSON logs, log rotation)
- Health check endpoint (`/actuator/health`) — only exposed Actuator endpoint (`management.endpoints.web.exposure.include=health` in `application-prod.yml`)
- Database backup script (pg_dump cron job: 7 daily + 4 weekly + 3 monthly retention)
- Global rate limiting already configured in Phase 1 (Bucket4j)
- XSS prevention (see XSS Prevention section below)
- Performance: connection pooling (HikariCP, default in Spring Boot), query optimization
- Basic monitoring with Docker health checks and log alerts

**Deliverable:** Production-ready application running on a VPS, accessible via HTTPS.

### Phase 5 (Optional): AWS Cloud Migration

Migrate from VPS to AWS if you outgrow a single server. See Section 11 for full details. This phase is optional and only needed if:
- You need high availability (zero downtime during server restarts)
- You need to handle significantly more than a few thousand users
- You want managed database backups with point-in-time recovery
- You want a global CDN for faster page loads worldwide

**Deliverable:** Application running on AWS with managed infrastructure.

---

## 10. VPS Deployment Plan

### Architecture on the VPS

```
VPS (~$6-12/month)
┌──────────────────────────────────────────────────────┐
│                                                      │
│  UFW Firewall (ports 80, 443, 22 only)               │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │              Docker Compose                     │  │
│  │                                                 │  │
│  │  ┌──────────────────────────────────────────┐   │  │
│  │  │            Nginx (container)              │   │  │
│  │  │                                           │   │  │
│  │  │  :80  → redirect to :443                  │   │  │
│  │  │  :443 → HTTPS (Let's Encrypt cert)        │   │  │
│  │  │                                           │   │  │
│  │  │  /           → serves React static files  │   │  │
│  │  │  /api/v1/*   → proxy to Spring Boot:8080  │   │  │
│  │  │  /uploads/*  → serves uploaded images     │   │  │
│  │  └──────────────────┬───────────────────────┘   │  │
│  │                     │                            │  │
│  │  ┌──────────────────┴───────────────────────┐   │  │
│  │  │       Spring Boot (container)             │   │  │
│  │  │                                           │   │  │
│  │  │  :8080 (internal only, not exposed)       │   │  │
│  │  │  REST API, business logic, auth           │   │  │
│  │  │  Health check: /actuator/health (only      │   │  │
│  │  │    exposed endpoint; all others disabled)  │   │  │
│  │  └──────────────────┬───────────────────────┘   │  │
│  │                     │                            │  │
│  │  ┌──────────────────┴───────────────────────┐   │  │
│  │  │       PostgreSQL 16 (container)           │   │  │
│  │  │                                           │   │  │
│  │  │  :5432 (internal only, not exposed)       │   │  │
│  │  │  Data stored in Docker volume             │   │  │
│  │  │  Daily pg_dump backups via cron           │   │  │
│  │  └──────────────────────────────────────────┘   │  │
│  │                                                 │  │
│  │  ┌──────────────────────────────────────────┐   │  │
│  │  │       Redis 7 (container)                 │   │  │
│  │  │                                           │   │  │
│  │  │  :6379 (internal only, not exposed)       │   │  │
│  │  │  Session storage (Spring Session)         │   │  │
│  │  │  requirepass enabled (password in .env)   │   │  │
│  │  │  ~50 MB RAM footprint                     │   │  │
│  │  └──────────────────────────────────────────┘   │  │
│  │                                                 │  │
│  └─────────────────────────────────────────────────┘  │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### VPS Provider (To Be Decided)

Provider will be chosen before Phase 4. Top candidates:

| Provider | Starting Price | Strengths |
|---|---|---|
| Hetzner | ~$4-7/month | Best price-to-performance, EU + US data centers |
| DigitalOcean | ~$6-12/month | Most beginner-friendly, excellent tutorials |
| Linode (Akamai) | ~$5-12/month | Reliable, good community |
| Vultr | ~$5-10/month | Global locations, competitive pricing |

**Recommended VPS specs for a few thousand users:**
- 2 vCPUs, 2-4 GB RAM, 40-80 GB SSD
- Ubuntu 22.04 LTS (or latest LTS)
- Estimated cost: $6-12/month

### What Gets Installed on the VPS

Only two things need to be installed manually:

1. **Docker + Docker Compose** — runs all application containers
2. **Certbot** — obtains and auto-renews free SSL certificates from Let's Encrypt

Everything else (Nginx, Spring Boot, PostgreSQL) runs inside Docker containers.

### Deployment Process

**Initial setup (one time):**

```bash
# 1. SSH into the VPS
ssh user@your-server-ip

# 2. Install Docker
curl -fsSL https://get.docker.com | sh

# 3. Clone the repository
git clone https://github.com/your-repo/blog-platform.git
cd blog-platform

# 4. Create .env file with production secrets
cp .env.example .env
nano .env  # Set all required secrets (see below)

# 5. Obtain SSL certificate
certbot certonly --standalone -d yourdomain.com

# 6. Start everything
docker compose -f docker-compose.prod.yml up -d
```

**Environment secrets management:**
- `.env` must be in `.gitignore` — never committed to the repository
- `.env.example` is committed with placeholder values only (e.g., `DB_PASSWORD=changeme`) — never real credentials
- Required secrets in `.env`:
  - `DB_PASSWORD` — randomly generated, minimum 24 characters
  - `REDIS_PASSWORD` — randomly generated, minimum 24 characters
  - `SESSION_SECRET` — randomly generated, minimum 32 characters
  - `DOMAIN_NAME` — the production domain
  - `EMAIL_API_KEY` — transactional email service API key (Mailgun, Postmark, or SendGrid)

**Updating the app (on each deploy):**

```bash
# 1. Pull latest code
git pull

# 2. Rebuild and restart containers (brief downtime during Spring Boot container restart — see Known Limitations)
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
```

### Nginx Configuration

Nginx serves as the single entry point. Key responsibilities:

| Responsibility | How |
|---|---|
| HTTPS termination | Reads Let's Encrypt certificate, encrypts all traffic |
| HTTP → HTTPS redirect | All port 80 requests redirected to port 443 |
| Serve React SPA | Serves static files from `/usr/share/nginx/html` |
| API reverse proxy | Forwards `/api/v1/*` requests to Spring Boot at `http://backend:8080` |
| Static file caching | Sets `Cache-Control` headers for CSS/JS/images (long cache, fingerprinted) |
| Gzip compression | Compresses text responses for faster page loads |
| Security headers | `X-Frame-Options`, `X-Content-Type-Options: nosniff`, `Strict-Transport-Security`, `Content-Security-Policy: script-src 'self'` (blocks inline script execution). `X-Content-Type-Options: nosniff` also set explicitly on the `/uploads/*` location block to prevent MIME-type sniffing of uploaded files |
| Upload content type | Serve `/uploads/*` with the validated image MIME type only (set by Spring Boot during upload validation). Add `Content-Disposition: inline` only for confirmed image types |
| Actuator blocking | Block all `/actuator/*` requests except `/actuator/health` — returns 404 for `/actuator/env`, `/actuator/beans`, etc. Defense-in-depth alongside `management.endpoints.web.exposure.include=health` in `application-prod.yml` |
| Client-side routing | Returns `index.html` for all non-API, non-file routes (React Router handles routing) |

### Database Backups

Automated daily backups using a cron job inside the PostgreSQL container or on the host:

```
Schedule: Daily at 3:00 AM
Method: pg_dump → compressed .sql.gz file
Storage: /backups/ directory on VPS + optional offsite copy
Retention:
  - Daily backups: keep last 7
  - Weekly backups (every Sunday): keep last 4 (30 days)
  - Monthly backups (1st of month): keep last 3 (90 days)
Restore: pg_restore from any backup file
```

This extended retention protects against data corruption bugs that go unnoticed for more than a week. Storage cost is negligible (compressed PostgreSQL dumps for a small database are typically a few MB each).

**Automated backup verification (monthly):** A cron job restores the latest backup to a temporary Docker PostgreSQL container, runs basic integrity checks (row counts on key tables), then destroys the container. This ensures backups are actually restorable — untested backups are nearly as risky as no backups.

### Firewall (UFW)

Only three ports open to the internet:

| Port | Protocol | Purpose |
|---|---|---|
| 22 | TCP | SSH (remote access to the server) |
| 80 | TCP | HTTP (redirects to HTTPS) |
| 443 | TCP | HTTPS (all application traffic) |

All other ports (8080 for Spring Boot, 5432 for PostgreSQL) are internal only — Docker containers communicate over an internal network that is not exposed to the internet.

**SSH hardening:** Key-based authentication only, password authentication disabled (`PasswordAuthentication no` in `sshd_config`), fail2ban installed to block brute-force attempts.

### Monitoring

Lightweight monitoring appropriate for a small deployment:

| What | How |
|---|---|
| Container health | Docker health checks (`/actuator/health` for Spring Boot) |
| Container restarts | Docker Compose `restart: unless-stopped` policy |
| Disk space | Cron job that alerts if disk usage exceeds 70% (critical for image uploads) — alert sent to Slack incoming webhook |
| Backup failure | pg_dump cron job exit code checked; failure sends alert to Slack incoming webhook |
| Alert channel | Free Slack incoming webhook — one `curl` call from any cron job; no additional infrastructure required |
| Application logs | `docker compose logs -f` for real-time viewing |
| Log persistence | Docker logging driver writes to `/var/log/` with rotation |
| Uptime | Free external monitoring service (e.g., UptimeRobot) pings the health endpoint every 5 minutes |

### XSS Prevention

User-generated content (blog posts, comments) requires a layered XSS defence strategy:

| Layer | Measure |
|---|---|
| Front-end rendering | All Markdown is rendered via `react-markdown` + `rehype-sanitize`. `rehype-sanitize` strips disallowed HTML tags (e.g., `<script>`, `<iframe>`, event handlers) before DOM injection. **Never use raw `dangerouslySetInnerHTML` on unsanitized content anywhere in the app.** |
| Content-Security-Policy | Nginx adds `Content-Security-Policy: script-src 'self'` header on all responses. This blocks inline `<script>` execution even if sanitization were bypassed, providing a second line of defence. |
| Comment validation | Back-end `CommentService` rejects any comment text containing HTML tags (`<` / `>` characters). Comments are plain text (250 chars max) — no markup is permitted or needed. |
| Post content | Content is stored as raw Markdown (never as HTML). XSS vectors in raw Markdown are neutralized at render time by `rehype-sanitize`. The back-end does not attempt to sanitize Markdown on ingest — the sanitization boundary is the front-end renderer. |

### Log Level Configuration (`application-prod.yml`)

Without explicit log levels, Spring Boot's default configuration floods production logs with DEBUG-level Hibernate SQL output (every query, every parameter binding). The following configuration is required in `application-prod.yml`:

```yaml
logging:
  level:
    root: WARN
    com.blogplatform: INFO
    org.hibernate.SQL: WARN
    org.springframework.web: WARN
```

This ensures application events are captured at INFO, framework noise is suppressed to WARN, and only unexpected errors surface prominently. Structured JSON logging (already documented) operates at this level configuration.

### Known Limitations

**Docker Compose as production orchestrator:** Docker Compose is designed for development and single-host deployments. Known limitations:
- No built-in health-check-based restart orchestration (relies on `restart: unless-stopped`)
- No rolling updates — `docker compose up -d` causes brief downtime during container recreation
- No blue-green deployment capability out of the box

These are acceptable at this scale. **Future improvement path:** Docker Swarm (minimal migration from Compose) or a simple blue-green deploy script that brings up a new container, health-checks it, then swaps Nginx upstream.

### PaaS Alternatives (For Non-Technical Operators)

If the VPS operator is not comfortable with SSH, Docker, and Nginx/Certbot, consider a PaaS (Platform as a Service) instead. These cost slightly more but eliminate server management:

| Provider | Estimated Cost | Strengths |
|---|---|---|
| Railway | ~$10-20/month | Easiest to deploy, auto-SSL, git push deploys |
| Render | ~$10-25/month | Free tier for experiments, managed PostgreSQL |
| Fly.io | ~$10-20/month | Global edge deployment, Docker-native |

The application is fully containerized (Dockerfiles exist), so migration to any PaaS is straightforward. The VPS deployment docs remain the primary reference.

### Estimated Monthly Cost

| Item | Cost |
|---|---|
| VPS (2 vCPU, 2-4 GB RAM) | $6-12 |
| Domain name | ~$1 (amortized yearly) |
| SSL certificate | Free (Let's Encrypt) |
| Monitoring (UptimeRobot) | Free tier |
| **Total** | **~$7-13/month** |

---

## 11. AWS Cloud Migration Plan (Future / Optional)

This section is a reference for if and when the application outgrows a single VPS. It is **not part of the initial launch plan**.

### When to Consider AWS

- Traffic consistently exceeds what a single VPS can handle
- You need zero-downtime deployments
- You need automated database failover
- You want a global CDN for international users

### Target AWS Architecture

```
                    ┌─────────────┐
                    │  Route 53   │  DNS
                    └──────┬──────┘
                           │
                    ┌──────┴──────┐
                    │ CloudFront  │  CDN (React static files + API caching)
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              │                         │
       ┌──────┴──────┐          ┌──────┴──────┐
       │     S3      │          │     ALB     │  Application Load Balancer
       │ (React SPA) │          └──────┬──────┘
       └─────────────┘                 │
                              ┌────────┴────────┐
                              │                  │
                       ┌──────┴──────┐   ┌──────┴──────┐
                       │   ECS/EC2   │   │   ECS/EC2   │  Spring Boot containers
                       │ (AZ-1)      │   │ (AZ-2)      │
                       └──────┬──────┘   └──────┬──────┘
                              │                  │
                              └────────┬─────────┘
                                       │
                              ┌────────┴────────┐
                              │   Amazon RDS    │  PostgreSQL (Multi-AZ)
                              │   (Primary)     │
                              └────────┬────────┘
                                       │
                              ┌────────┴────────┐
                              │   RDS Standby   │  Auto-failover replica
                              └─────────────────┘
```

### AWS Services Mapping

| Component | AWS Service | Purpose |
|---|---|---|
| React static files | S3 + CloudFront | Host and serve the React SPA globally |
| Spring Boot app | ECS Fargate (or EC2) | Run Docker containers without managing servers |
| PostgreSQL | RDS PostgreSQL | Managed database with backups, patching, failover |
| Image uploads | S3 | Store user-uploaded images (replaces local filesystem) |
| DNS | Route 53 | Domain name management |
| SSL | ACM (Certificate Manager) | Free SSL certificates, auto-renewed |
| Load balancing | ALB | Distribute traffic across multiple containers |
| Secrets | Secrets Manager | Store database credentials, API keys |
| Monitoring | CloudWatch | Logs, metrics, alerts |
| CI/CD | CodePipeline + CodeBuild | Automated build and deploy on git push |

### Migration Steps (VPS → AWS)

1. **Containerize** — Already done (Dockerfiles exist from Phase 4)
2. **Set up RDS** — Create PostgreSQL RDS instance, migrate data with pg_dump/pg_restore
3. **Set up ECS** — Create ECS Fargate service with the Spring Boot Docker image
4. **Set up S3 + CloudFront** — Upload React build to S3, configure CloudFront distribution
5. **Configure ALB** — Point to ECS tasks, health checks on `/actuator/health`
6. **Update image storage** — Switch ImageService from local filesystem to S3 SDK
7. **DNS cutover** — Point domain to CloudFront (React) and ALB (API) via Route 53
8. **Verify and monitor** — Run E2E tests against AWS, set up CloudWatch alerts

### Session Management in Cloud

Since the application already uses Redis-backed sessions (Spring Session Data Redis) from Phase 1, the cloud migration is straightforward:

- **Replace local Redis container with AWS ElastiCache Redis** — same protocol, change connection string only
- Sessions are automatically shared across all ECS containers with no code changes
- ElastiCache provides managed Redis with automatic failover, patching, and monitoring

### Estimated AWS Costs (Few Thousand Users)

| Service | Estimated Monthly Cost |
|---|---|
| ECS Fargate (1-2 small tasks) | $15-30 |
| RDS PostgreSQL (db.t3.micro) | $15-25 |
| S3 + CloudFront | $1-5 |
| Route 53 | $1 |
| ALB | $16 + traffic |
| **Total** | **~$50-80/month** |

These are rough estimates for low traffic. AWS Free Tier covers some of this for the first 12 months.

---

## 12. Deferred Features (YAGNI)

These exist in the original schema or README as future plans. They are deliberately excluded from this design to keep scope manageable:

- **2FA** — Column exists, feature not built
- **User follow system** — Listed in README future enhancements
- **Blog post sharing** — Listed in README future enhancements
- **Archiving old posts** — Listed in README future enhancements
- **Gender in profiles** — Listed in README future enhancements
- **OAuth / social login** — Not needed for initial launch
- **Account deletion / deactivation** — No self-service account deletion mechanism. Known gap — may be required for GDPR compliance if EU users are expected. Will require a policy for handling orphaned content (posts, comments) when implemented.
- **Real-time notifications** — Polling is sufficient at this scale, WebSockets/SSE later if needed
- **VIP payment processing** — The `POST /api/v1/users/{id}/upgrade-vip` endpoint is a stub (returns 501) in Phases 1–4. Phase 5+ will integrate Stripe Checkout: redirect to Stripe → receive webhook confirmation → set VIP flags server-side in the webhook handler. Client-submitted payment data (amount, transaction ID) must never be trusted without server-side verification against the payment processor.
