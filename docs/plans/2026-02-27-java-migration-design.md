# BlogPlatformDB — Java Migration Design

**Version:** 3.0
**Last Updated:** 2026-02-27
**Status:** Draft — Awaiting Final Approval

---

## Change Log

| Version | Date       | Author | Summary | Details |
|---------|------------|--------|---------|---------|
| 1.0 | 2026-02-27 | Angela + Claude | Initial design | Back-end only design with Spring Boot, PostgreSQL, REST API. Covered 6 sections: project structure, entity model, API endpoints, business logic migration, security & auth, testing strategy. |
| 2.0 | 2026-02-27 | Angela + Claude | Major expansion + front-end | Added React + TypeScript front-end (Vite, Tailwind, React Query). Expanded all 6 original sections with detailed field-level entity descriptions, DTO listings, response format examples. Added: overall architecture diagram with request flow, monorepo structure, front-end file structure with components/pages/hooks, authentication flow diagram, Spring Security filter chain, CSRF/CORS details, testing pyramid with front-end tests (Vitest, React Testing Library, MSW, Cypress), 5 implementation phases, AWS cloud migration plan with architecture diagram and cost estimates, deferred features list. Added changelog and versioning. |
| 3.0 | 2026-02-27 | Angela + Claude | Deployment strategy change | Replaced AWS as primary deployment target with VPS (self-hosted). Rationale: AWS is overkill for a few thousand users (~$50-80/month vs ~$6-12/month for a VPS). Added detailed VPS deployment section covering Docker, Nginx, SSL, backups, monitoring, and firewall. Demoted AWS to a future growth option (Phase 5 → optional). Updated implementation phases to reflect VPS as Phase 4 deployment target. Added Nginx architecture diagram and deployment commands. |

---

## Overview

Migrate the BlogPlatformDB SQL Server database project into a full-stack modern web application. The back-end is a Spring Boot REST API with PostgreSQL. The front-end is a React single-page application with TypeScript. The application runs on a self-hosted VPS (Virtual Private Server) for a few thousand users, with Docker and Nginx handling containerization and traffic routing. AWS cloud migration is documented as a future growth option if the application outgrows a single server.

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
| Authentication   | Spring Security (session-based) | Simple for single-server, cookie-based sessions        |
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
2. React sends HTTP request to Spring Boot API (`/api/*`)
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
├── docker-compose.yml                (PostgreSQL + full stack for local dev)
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
│   └── RateLimitConfig.java          Rate limiting for auth endpoints
├── user/
│   ├── UserAccount.java              Entity: account_id, username, email, password_hash,
│   │                                   role, is_vip, vip_start_date, vip_end_date,
│   │                                   two_factor_enabled, created_at
│   ├── UserProfile.java              Entity: profile_id, first_name, last_name, bio,
│   │                                   profile_pic_url, last_login, login_count
│   ├── Role.java                     Enum: ADMIN, AUTHOR, USER
│   ├── UserController.java           GET /api/users/{id}, PUT /api/users/{id},
│   │                                   GET /api/users/{id}/saved-posts,
│   │                                   POST /api/users/{id}/upgrade-vip
│   ├── UserService.java              Profile CRUD, VIP upgrade orchestration
│   ├── UserRepository.java           JPA repository, existsByUsername(), existsByEmail()
│   └── dto/
│       ├── UserProfileResponse.java  Public profile data (no password, no internal IDs)
│       ├── UpdateProfileRequest.java Validated input for profile updates
│       └── VipUpgradeRequest.java    Payment details for VIP upgrade
├── auth/
│   ├── AuthController.java           POST /api/auth/register, /login, /logout,
│   │                                   GET /api/auth/me (current user)
│   ├── AuthService.java              Registration, password hashing, session creation
│   └── dto/
│       ├── RegisterRequest.java      username, email, password (validated)
│       ├── LoginRequest.java         username, password
│       └── AuthResponse.java         user info + role returned after login
├── author/
│   ├── AuthorProfile.java            Entity: author_id, biography, social_links (JSON),
│   │                                   expertise, account_id FK
│   ├── AuthorController.java         GET /api/authors, GET /api/authors/{id}
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
│   ├── PostEntityListener.java       @PostPersist/@PostUpdate → writes PostUpdateLog
│   └── dto/
│       ├── PostListResponse.java     Summary: title, author, category, like/comment counts
│       ├── PostDetailResponse.java   Full post with author, tags, like count
│       ├── CreatePostRequest.java    title, content, category_id, tag_ids, is_premium
│       └── UpdatePostRequest.java    Same fields, all optional
├── comment/
│   ├── Comment.java                  Entity: comment_id, content, account_id FK,
│   │                                   post_id FK, parent_comment_id (self-ref),
│   │                                   created_at
│   ├── CommentController.java        GET /api/posts/{id}/comments,
│   │                                   POST /api/posts/{id}/comments,
│   │                                   DELETE /api/comments/{id}
│   ├── CommentService.java           Validates read-before-comment, 250-char limit,
│   │                                   builds threaded response
│   ├── CommentRepository.java        findByPostIdAndParentCommentIsNull() for top-level
│   └── dto/
│       ├── CommentResponse.java      Nested structure with replies list
│       └── CreateCommentRequest.java content, parent_comment_id (optional)
├── like/
│   ├── Like.java                     Entity: like_id, account_id FK, post_id FK,
│   │                                   created_at. Unique(account_id, post_id)
│   ├── LikeController.java          POST + DELETE /api/posts/{id}/likes
│   ├── LikeService.java             Toggle logic, prevents duplicates
│   └── LikeRepository.java          countByPostId(), existsByAccountIdAndPostId()
├── tag/
│   ├── Tag.java                      Entity: tag_id, tag_name (unique)
│   ├── TagController.java           GET /api/tags, POST /api/tags (admin)
│   ├── TagService.java
│   └── TagRepository.java           findByTagNameIn() for bulk lookup
├── category/
│   ├── Category.java                 Entity: category_id, category_name (unique),
│   │                                   description
│   ├── CategoryController.java      GET /api/categories, POST /api/categories (admin)
│   ├── CategoryService.java
│   └── CategoryRepository.java
├── subscription/
│   ├── Subscriber.java               Entity: subscriber_id, account_id FK (unique),
│   │                                   subscribed_at, expiration_date
│   ├── SubscriptionController.java  POST + DELETE /api/subscriptions
│   ├── SubscriptionService.java     Subscribe/unsubscribe, expiration check
│   └── SubscriberRepository.java    findAllActiveSubscribers()
├── payment/
│   ├── Payment.java                  Entity: payment_id, account_id FK, amount,
│   │                                   payment_method (enum), transaction_id (unique),
│   │                                   payment_date
│   ├── PaymentMethod.java           Enum: CREDIT_CARD, PAYPAL, BANK_TRANSFER
│   ├── PaymentController.java       POST /api/users/{id}/upgrade-vip delegates here
│   ├── PaymentService.java          @Transactional: creates payment + sets VIP flags
│   └── PaymentRepository.java
├── notification/
│   ├── Notification.java             Entity: notification_id, account_id FK, message,
│   │                                   is_read, created_at
│   ├── NotificationController.java  GET /api/notifications,
│   │                                   PUT /api/notifications/{id}/read
│   ├── NotificationService.java     notifySubscribers(), markAsRead()
│   └── NotificationRepository.java  findByAccountIdOrderByCreatedAtDesc()
├── image/
│   ├── Image.java                    Entity: image_id, post_id FK, image_url,
│   │                                   alt_text, uploaded_at
│   ├── ImageController.java         POST /api/posts/{id}/images,
│   │                                   DELETE /api/images/{id}
│   ├── ImageService.java            Upload to local filesystem (→ S3 in cloud phase)
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
│   │   ├── PostDetail.tsx            Full post view with content
│   │   ├── PostForm.tsx              Create/edit post form (authors)
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
│   └── useNotifications.ts           React Query hooks + polling for notifications
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
| UserAccount | `UserAccount.java` | password → BCrypt hash, role → enum, is_vip/vip_start/vip_end preserved |
| UserProfile | `UserProfile.java` | `@OneToOne` with UserAccount, cascade ALL |
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
| Comments | `Comment.java` | `@ManyToOne` self-reference (parent_comment_id), `@Size(max=250)` on content |
| Likes | `Like.java` | `@Table(uniqueConstraints)` on (account_id, post_id) |
| ReadPost | `ReadPost.java` | Composite key (account_id, post_id) via `@IdClass` |
| SavedPosts | `SavedPost.java` | Composite key (account_id, post_id) via `@IdClass` |

#### Monetization & Communication

| SQL Server Table | Java Entity | Key Mappings |
|---|---|---|
| AuthorProfile | `AuthorProfile.java` | social_links as `@JdbcTypeCode(SqlTypes.JSON)` for native PostgreSQL JSON |
| Payment | `Payment.java` | payment_method → PaymentMethod enum, `@Positive` on amount, transaction_id unique |
| Subscriber | `Subscriber.java` | `@OneToOne` with UserAccount, expiration_date nullable |
| Notifications | `Notification.java` | Created by NotificationService, is_read boolean |

---

## 5. API Endpoints

### Auth
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/auth/register` | Public | Create account (username, email, password) |
| POST | `/api/auth/login` | Public | Authenticate, create session, return user info |
| POST | `/api/auth/logout` | Authenticated | Destroy session, invalidate cookie |
| GET | `/api/auth/me` | Authenticated | Return current user info (used by React on page load) |

### Users
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/users/{id}` | Authenticated | Get user public profile |
| PUT | `/api/users/{id}` | Owner only | Update own profile (first_name, last_name, bio, pic) |
| GET | `/api/users/{id}/saved-posts` | Owner only | List saved/bookmarked posts (paginated) |
| POST | `/api/users/{id}/upgrade-vip` | Owner only | Submit payment and upgrade to VIP |

### Posts
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/posts` | Public | List posts (paginated, filterable) |
| GET | `/api/posts/{id}` | Public* | Get single post (marks as read). *Premium → VIP only |
| POST | `/api/posts` | AUTHOR, ADMIN | Create new post |
| PUT | `/api/posts/{id}` | Owner, ADMIN | Update post |
| DELETE | `/api/posts/{id}` | Owner, ADMIN | Soft-delete post (sets is_deleted = true) |
| POST | `/api/posts/{id}/save` | Authenticated | Bookmark a post |
| DELETE | `/api/posts/{id}/save` | Authenticated | Remove bookmark |

**Query parameters for GET /api/posts:**
- `?page=0&size=20` — pagination (default page 0, size 20)
- `?category=5` — filter by category ID
- `?tag=java` — filter by tag name
- `?author=3` — filter by author ID
- `?search=spring+boot` — full-text search on title and content

### Comments
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/posts/{postId}/comments` | Public | Get threaded comments for a post |
| POST | `/api/posts/{postId}/comments` | Authenticated | Add comment (must have read the post first) |
| DELETE | `/api/comments/{id}` | Owner, ADMIN | Delete a comment |

### Likes
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/posts/{postId}/likes` | Authenticated | Like a post (idempotent, no error if already liked) |
| DELETE | `/api/posts/{postId}/likes` | Authenticated | Unlike a post |

### Categories
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/categories` | Public | List all categories |
| POST | `/api/categories` | ADMIN | Create a category |
| PUT | `/api/categories/{id}` | ADMIN | Update a category |
| DELETE | `/api/categories/{id}` | ADMIN | Delete a category |

### Tags
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/tags` | Public | List all tags |
| POST | `/api/tags` | ADMIN | Create a tag |

### Authors
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/authors` | Public | List all authors with post counts |
| GET | `/api/authors/{id}` | Public | Get author profile + their posts (paginated) |

### Subscriptions
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/subscriptions` | Authenticated | Subscribe to new post notifications |
| DELETE | `/api/subscriptions` | Authenticated | Unsubscribe |

### Notifications
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/notifications` | Authenticated | Get own notifications (paginated, newest first) |
| PUT | `/api/notifications/{id}/read` | Owner only | Mark a notification as read |
| PUT | `/api/notifications/read-all` | Authenticated | Mark all notifications as read |

### Images
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/posts/{postId}/images` | AUTHOR, ADMIN | Upload image for a post |
| DELETE | `/api/images/{id}` | Owner, ADMIN | Delete an image |

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
| `SP_Add_Comment` | `CommentService.addComment()` | 1. Check ReadPost exists for (user, post) → 403 if not. 2. Validate content ≤ 250 chars via `@Size`. 3. If parent_comment_id provided, verify parent exists and belongs to same post. 4. Save Comment entity. |
| `SP_Upgrade_User_To_VIP` | `PaymentService.upgradeToVip()` | `@Transactional`: 1. Validate payment (amount > 0, method valid). 2. Create Payment record with unique transaction_id. 3. Set UserAccount.is_vip = true, vip_start_date = now, vip_end_date = now + 1 year. If any step fails, everything rolls back. |
| `SP_Create_Post_Notifications` | `NotificationService.notifySubscribers()` | Called by PostService.createPost(). Queries all active subscribers (expiration_date null or > now). Creates a Notification for each: "New post: {title} by {author}". |
| `SP_Backup_All_DB` | Not migrated | Database backups handled by pg_dump cron job on the server (or AWS RDS automated backups in cloud phase). |
| `SP_Backup_Database` | Not migrated | Same as above. |

### Triggers → JPA Event Listeners & Service Logic

| SQL Trigger | Java Equivalent | Migration Details |
|---|---|---|
| `TR_BlogPosts_Insert_Log` | `PostEntityListener.postPersist()` | `@PostPersist`: creates a PostUpdateLog entry with the new post's title and content. |
| `TR_BlogPosts_Update_Log` | `PostEntityListener.postUpdate()` | `@PostUpdate`: creates a PostUpdateLog entry with old and new title/content. Uses `@PreUpdate` to capture old values before Hibernate flushes. |
| `TR_BlogPosts_Delete_Log` | `PostService.deletePost()` | No trigger. Service method sets `is_deleted = true` and `updated_at = now`. The post remains in the database but is excluded from all queries via a default `@Where(clause = "is_deleted = false")`. |
| `TR_Notify_Subscribers_On_New_Post` | `PostService.createPost()` | After saving the post, explicitly calls `notificationService.notifySubscribers(post)`. Explicit call is preferable to a hidden trigger. |

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

---

## 7. Authentication & Security

### Authentication Architecture

```
Browser                     Spring Boot
  │                            │
  │  POST /api/auth/login      │
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
  │  GET /api/posts            │
  │  Cookie: JSESSIONID=abc    │
  │ ──────────────────────────>│
  │                            │  1. SessionFilter extracts cookie
  │                            │  2. Look up session in session store
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
  6. Rate limit filter    → Throttle auth endpoints (10 requests/minute/IP)
```

### Password Security

- Hashed with **BCrypt** (work factor 12)
- Never stored or transmitted in plaintext
- Password requirements enforced by Bean Validation:
  - Minimum 8 characters
  - At least one uppercase, one lowercase, one digit

### CSRF Protection

- Spring Security generates a CSRF token per session
- Token sent to React via a cookie (`XSRF-TOKEN`)
- React's Axios interceptor reads the cookie and sends the token in `X-XSRF-TOKEN` header
- All POST/PUT/DELETE requests validated against this token

### CORS Configuration

- Development: allow `http://localhost:5173` (Vite dev server)
- Production: allow only the actual domain
- Credentials (cookies) allowed in cross-origin requests

### Session Management

- Sessions stored in-memory (default Spring Boot HttpSession)
- Session timeout: 30 minutes of inactivity
- Session fixation protection: create new session on login
- Single session per user (optional, can be relaxed)

### Authorization Rules

| Endpoint Pattern | Rule |
|---|---|
| `POST /api/auth/**` | Public (permitAll) |
| `GET /api/posts`, `GET /api/posts/{id}` | Public |
| `GET /api/categories`, `GET /api/tags` | Public |
| `GET /api/authors`, `GET /api/authors/{id}` | Public |
| `POST /api/posts` | `hasRole('AUTHOR')` or `hasRole('ADMIN')` |
| `PUT/DELETE /api/posts/{id}` | Owner or `hasRole('ADMIN')` |
| `POST /api/posts/{id}/comments` | Authenticated + must have read the post |
| `POST/DELETE /api/categories`, `/api/tags` | `hasRole('ADMIN')` |
| `PUT /api/users/{id}` | Owner only |
| Everything else | Authenticated |

### Deferred Security Features (YAGNI)

- 2FA — `two_factor_enabled` column kept in schema but not implemented
- OAuth / social login — not needed for initial launch
- Email verification — not needed for initial launch
- Password reset flow — can be added later

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
| PaymentService | upgradeToVip creates payment + sets VIP flags. Negative amount → validation error. |
| NotificationService | notifySubscribers creates N notifications for N active subscribers. Skip expired subscribers. |

#### Integration Tests (Testcontainers + Spring Boot Test)

Spin up a real PostgreSQL container and test the full stack from controller to database.

| Area | Key Integration Tests |
|---|---|
| Auth flow | Register → login → access protected endpoint → logout → rejected |
| Post CRUD | Create → read (marked as read) → update (log created) → soft delete (not in listings) |
| Comment threading | Create post → read it → comment → reply to comment → verify thread structure |
| VIP upgrade | Create payment → VIP flags set → access premium post → success |
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
- Set up Docker Compose with PostgreSQL 16
- Configure Flyway and write migration scripts for all 17 tables
- Implement JPA entities for all tables with relationships and validation
- Configure Spring Security with session-based auth
- Implement auth endpoints (register, login, logout, /me)
- Implement Role enum and authorization rules
- Set up GlobalExceptionHandler and ApiResponse wrapper
- Write unit and integration tests for auth flow

**Deliverable:** Running Spring Boot app with auth, database schema, and all entities.

### Phase 2: Core Features (Back-End API)

Build out the full REST API.

- Post CRUD with soft delete, pagination, filtering, full-text search
- PostEntityListener for audit logging (PostUpdateLog)
- Read tracking (mark posts as read on GET)
- Comment system with threading and read-before-comment validation
- Like/unlike functionality
- Category and tag management
- Saved posts / bookmarking
- Author profiles with JSON social links
- Subscription and notification system
- VIP upgrade with payment processing
- Image upload (local filesystem storage)
- SpringDoc OpenAPI / Swagger documentation
- Integration tests for all endpoints

**Deliverable:** Complete, tested REST API with Swagger docs.

### Phase 3: Front-End

Build the React application.

- Initialize Vite + React + TypeScript project
- Set up Tailwind CSS, React Router, React Query, Axios
- Build auth pages (login, register) + AuthContext
- Build post listing page with filters, pagination, search
- Build post detail page with comments, likes, read tracking
- Build post creation/editing for authors
- Build user profile page with edit functionality
- Build saved posts page
- Build notifications page
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
- Health check endpoint (`/actuator/health`)
- Database backup script (pg_dump cron job, daily backups, 7-day retention)
- Rate limiting on all auth endpoints
- Input sanitization for XSS prevention
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
│  │  │  /api/*      → proxy to Spring Boot:8080  │   │  │
│  │  │  /uploads/*  → serves uploaded images     │   │  │
│  │  └──────────────────┬───────────────────────┘   │  │
│  │                     │                            │  │
│  │  ┌──────────────────┴───────────────────────┐   │  │
│  │  │       Spring Boot (container)             │   │  │
│  │  │                                           │   │  │
│  │  │  :8080 (internal only, not exposed)       │   │  │
│  │  │  REST API, business logic, auth           │   │  │
│  │  │  Health check: /actuator/health           │   │  │
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
nano .env  # Set DB password, session secret, domain name

# 5. Obtain SSL certificate
certbot certonly --standalone -d yourdomain.com

# 6. Start everything
docker compose -f docker-compose.prod.yml up -d
```

**Updating the app (on each deploy):**

```bash
# 1. Pull latest code
git pull

# 2. Rebuild and restart containers (zero-downtime with --no-deps)
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
| API reverse proxy | Forwards `/api/*` requests to Spring Boot at `http://backend:8080` |
| Static file caching | Sets `Cache-Control` headers for CSS/JS/images (long cache, fingerprinted) |
| Gzip compression | Compresses text responses for faster page loads |
| Security headers | `X-Frame-Options`, `X-Content-Type-Options`, `Strict-Transport-Security` |
| Client-side routing | Returns `index.html` for all non-API, non-file routes (React Router handles routing) |

### Database Backups

Automated daily backups using a cron job inside the PostgreSQL container or on the host:

```
Schedule: Daily at 3:00 AM
Method: pg_dump → compressed .sql.gz file
Storage: /backups/ directory on VPS + optional offsite copy
Retention: Keep last 7 daily backups, delete older ones
Restore: pg_restore from any backup file
```

### Firewall (UFW)

Only three ports open to the internet:

| Port | Protocol | Purpose |
|---|---|---|
| 22 | TCP | SSH (remote access to the server) |
| 80 | TCP | HTTP (redirects to HTTPS) |
| 443 | TCP | HTTPS (all application traffic) |

All other ports (8080 for Spring Boot, 5432 for PostgreSQL) are internal only — Docker containers communicate over an internal network that is not exposed to the internet.

### Monitoring

Lightweight monitoring appropriate for a small deployment:

| What | How |
|---|---|
| Container health | Docker health checks (`/actuator/health` for Spring Boot) |
| Container restarts | Docker Compose `restart: unless-stopped` policy |
| Disk space | Cron job that alerts if disk usage exceeds 80% |
| Application logs | `docker compose logs -f` for real-time viewing |
| Log persistence | Docker logging driver writes to `/var/log/` with rotation |
| Uptime | Free external monitoring service (e.g., UptimeRobot) pings the health endpoint every 5 minutes |

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

When moving from a single server to multiple ECS containers, sessions can't be stored in-memory. Two options:

| Approach | How It Works | Trade-off |
|---|---|---|
| **Spring Session + Redis (ElastiCache)** | Sessions stored in Redis, shared across all containers | Adds a Redis dependency but is the standard approach |
| **Sticky sessions on ALB** | ALB routes a user to the same container every time | Simpler but less resilient (container restart = logged out) |

**Recommendation:** Start with sticky sessions (simpler). Switch to Redis if you need more resilience or scale beyond a few containers.

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
- **Email verification** — Not needed for initial launch
- **Password reset** — Can be added later
- **Real-time notifications** — Polling is sufficient at this scale, WebSockets later if needed
