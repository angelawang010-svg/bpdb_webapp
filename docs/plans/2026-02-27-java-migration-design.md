# BlogPlatformDB — Java Migration Design

## Overview

Migrate the BlogPlatformDB SQL Server database project into a modern Java web application using clean architecture and best practices. The application will be a back-end REST API serving a few thousand users on a self-hosted server.

## Tech Stack

| Component       | Choice                         |
|-----------------|--------------------------------|
| Framework       | Spring Boot                    |
| Language        | Java 21 (LTS)                  |
| Build tool      | Gradle                         |
| Database        | PostgreSQL                     |
| API style       | REST API                       |
| Authentication  | Session-based (Spring Security)|
| Architecture    | Package-by-Feature with Layers |

## 1. Project Structure

```
blog-platform/
├── build.gradle
├── settings.gradle
├── docker-compose.yml                               (PostgreSQL for local dev)
├── src/
│   ├── main/
│   │   ├── java/com/blogplatform/
│   │   │   ├── BlogPlatformApplication.java
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── WebConfig.java
│   │   │   ├── user/
│   │   │   │   ├── UserAccount.java
│   │   │   │   ├── UserProfile.java
│   │   │   │   ├── Role.java                        (enum: ADMIN, AUTHOR, USER)
│   │   │   │   ├── UserController.java
│   │   │   │   ├── UserService.java
│   │   │   │   ├── UserRepository.java
│   │   │   │   └── dto/
│   │   │   ├── auth/
│   │   │   │   ├── AuthController.java
│   │   │   │   └── AuthService.java
│   │   │   ├── author/
│   │   │   │   ├── AuthorProfile.java
│   │   │   │   ├── AuthorController.java
│   │   │   │   ├── AuthorService.java
│   │   │   │   └── AuthorRepository.java
│   │   │   ├── post/
│   │   │   │   ├── BlogPost.java
│   │   │   │   ├── PostController.java
│   │   │   │   ├── PostService.java
│   │   │   │   ├── PostRepository.java
│   │   │   │   ├── PostUpdateLog.java
│   │   │   │   ├── ReadPost.java
│   │   │   │   ├── SavedPost.java
│   │   │   │   └── dto/
│   │   │   ├── comment/
│   │   │   │   ├── Comment.java
│   │   │   │   ├── CommentController.java
│   │   │   │   ├── CommentService.java
│   │   │   │   └── CommentRepository.java
│   │   │   ├── like/
│   │   │   │   ├── Like.java
│   │   │   │   ├── LikeController.java
│   │   │   │   ├── LikeService.java
│   │   │   │   └── LikeRepository.java
│   │   │   ├── tag/
│   │   │   │   ├── Tag.java
│   │   │   │   ├── TagController.java
│   │   │   │   ├── TagService.java
│   │   │   │   └── TagRepository.java
│   │   │   ├── category/
│   │   │   │   ├── Category.java
│   │   │   │   ├── CategoryController.java
│   │   │   │   ├── CategoryService.java
│   │   │   │   └── CategoryRepository.java
│   │   │   ├── subscription/
│   │   │   │   ├── Subscriber.java
│   │   │   │   ├── SubscriptionController.java
│   │   │   │   ├── SubscriptionService.java
│   │   │   │   └── SubscriberRepository.java
│   │   │   ├── payment/
│   │   │   │   ├── Payment.java
│   │   │   │   ├── PaymentController.java
│   │   │   │   ├── PaymentService.java
│   │   │   │   └── PaymentRepository.java
│   │   │   ├── notification/
│   │   │   │   ├── Notification.java
│   │   │   │   ├── NotificationController.java
│   │   │   │   ├── NotificationService.java
│   │   │   │   └── NotificationRepository.java
│   │   │   ├── image/
│   │   │   │   ├── Image.java
│   │   │   │   ├── ImageController.java
│   │   │   │   ├── ImageService.java
│   │   │   │   └── ImageRepository.java
│   │   │   └── common/
│   │   │       ├── exception/
│   │   │       │   ├── GlobalExceptionHandler.java
│   │   │       │   └── ResourceNotFoundException.java
│   │   │       └── dto/
│   │   │           └── ApiResponse.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/                        (Flyway migration scripts)
│   └── test/
│       └── java/com/blogplatform/                   (mirrors main structure)
```

## 2. Entity / Data Model

### User & Auth

| SQL Server Table | Java Entity | Changes |
|---|---|---|
| UserAccount | `UserAccount.java` (user/) | IDENTITY → PostgreSQL SERIAL, password hashed with BCrypt |
| UserProfile | `UserProfile.java` (user/) | One-to-one with UserAccount |
| Role | `Role.java` (user/) | Enum (ADMIN, AUTHOR, USER) instead of a table |

### Content

| SQL Server Table | Java Entity | Notes |
|---|---|---|
| BlogPosts | `BlogPost.java` (post/) | Soft delete preserved, JPA auditing for timestamps |
| Categories | `Category.java` (category/) | Simple lookup entity |
| Tags | `Tag.java` (tag/) | Many-to-many with BlogPost |
| PostTags | No separate entity | JPA handles junction table via @ManyToMany |
| Images | `Image.java` (image/) | One-to-many with BlogPost |
| PostUpdateLog | `PostUpdateLog.java` (post/) | JPA event listeners replace SQL triggers |

### Engagement

| SQL Server Table | Java Entity | Notes |
|---|---|---|
| Comments | `Comment.java` (comment/) | Self-referencing parent_comment_id for threading |
| Likes | `Like.java` (like/) | Unique constraint on (account_id, post_id) |
| ReadPost | `ReadPost.java` (post/) | Tracks which users read which posts |
| SavedPosts | `SavedPost.java` (post/) | Bookmarking feature |

### Monetization & Communication

| SQL Server Table | Java Entity | Notes |
|---|---|---|
| AuthorProfile | `AuthorProfile.java` (author/) | social_links as PostgreSQL JSON |
| Payment | `Payment.java` (payment/) | CHECK constraints → Bean Validation |
| Subscriber | `Subscriber.java` (subscription/) | One-to-one with UserAccount |
| Notifications | `Notification.java` (notification/) | Created by service, not trigger |

### Migration Patterns

- SQL IDENTITY → `@GeneratedValue(strategy = IDENTITY)`
- CHECK constraints → Bean Validation (`@Size`, `@Positive`, `@PastOrPresent`)
- SQL triggers → JPA event listeners (`@PostPersist`, `@PostUpdate`)
- SQL views → Repository `@Query` methods
- Timestamps → JPA Auditing (`@CreatedDate`, `@LastModifiedDate`)

## 3. API Endpoints

### Auth
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/auth/register | Create new account |
| POST | /api/auth/login | Log in (creates session) |
| POST | /api/auth/logout | Log out (destroys session) |

### Users
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/users/{id} | Get user profile |
| PUT | /api/users/{id} | Update own profile |
| GET | /api/users/{id}/saved-posts | Get user's saved posts |
| POST | /api/users/{id}/upgrade-vip | Upgrade to VIP (triggers payment) |

### Posts
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/posts | List posts (pagination, filtering by category/tag/author) |
| GET | /api/posts/{id} | Get single post (marks as read) |
| POST | /api/posts | Create post (authors only) |
| PUT | /api/posts/{id} | Update post (author/admin only) |
| DELETE | /api/posts/{id} | Soft-delete post (author/admin only) |
| POST | /api/posts/{id}/save | Bookmark a post |
| DELETE | /api/posts/{id}/save | Remove bookmark |

### Comments
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/posts/{postId}/comments | Get comments (threaded) |
| POST | /api/posts/{postId}/comments | Add comment (must have read the post) |
| DELETE | /api/comments/{id} | Delete own comment |

### Likes
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/posts/{postId}/likes | Like a post |
| DELETE | /api/posts/{postId}/likes | Unlike a post |

### Categories & Tags
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/categories | List all categories |
| POST | /api/categories | Create category (admin only) |
| GET | /api/tags | List all tags |
| POST | /api/tags | Create tag (admin only) |

### Authors
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/authors | List authors |
| GET | /api/authors/{id} | Get author profile with posts |

### Subscriptions
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/subscriptions | Subscribe |
| DELETE | /api/subscriptions | Unsubscribe |

### Notifications
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/notifications | Get own notifications |
| PUT | /api/notifications/{id}/read | Mark as read |

All list endpoints support pagination (?page=0&size=20). Posts support filtering (?category=tech&tag=java&author=5). All endpoints return consistent JSON via ApiResponse wrapper.

## 4. Business Logic Migration

### Stored Procedures → Service Methods

| SQL Procedure | Java Method | Notes |
|---|---|---|
| SP_Add_Comment | CommentService.addComment() | Validates read status, 250-char limit, parent comment support |
| SP_Upgrade_User_To_VIP | PaymentService.upgradeToVip() | @Transactional: payment + VIP flags atomically |
| SP_Create_Post_Notifications | NotificationService.notifySubscribers() | Called by PostService after creating a post |
| SP_Backup_* | Not migrated | Handled at infrastructure level (pg_dump) |

### Triggers → JPA Event Listeners

| SQL Trigger | Java Equivalent |
|---|---|
| TR_BlogPosts_Insert_Log | @PostPersist on BlogPost |
| TR_BlogPosts_Update_Log | @PostUpdate on BlogPost |
| TR_BlogPosts_Delete_Log | PostService.deletePost() sets is_deleted = true |
| TR_Notify_Subscribers_On_New_Post | PostService.createPost() calls NotificationService |

### Functions → Repository Queries

| SQL Function | Java Equivalent |
|---|---|
| SF_Get_Like_Count_By_Post | LikeRepository.countByPostId() |
| SF_Get_Post_Count_By_Category | PostRepository.countByCategoryId() |
| SF_Check_Account_Exists | UserRepository.existsByUsername() |
| TF_Get_Most_Like_Post_By_Category | PostRepository custom @Query |
| TF_Get_Posts_By_Author | PostRepository.findByAuthorId() |
| TF_Get_Tags_By_Post | Loaded via @ManyToMany relationship |
| TF_Get_Comment_And_Like_Count | Custom @Query with COUNT projections |

### Views → Repository Queries

Each SQL view becomes a repository method with a custom @Query returning a DTO projection.

## 5. Security & Auth

### Authentication Flow

1. Registration: credentials → BCrypt hash → UserAccount + UserProfile created
2. Login: validate credentials → create HTTP session → send session cookie
3. Requests: browser sends cookie → Spring Security validates session
4. Logout: destroy session, invalidate cookie

### Authorization Matrix

| Action | USER | AUTHOR | ADMIN |
|--------|------|--------|-------|
| View public posts | Yes | Yes | Yes |
| View premium posts | VIP only | Yes | Yes |
| Create/edit/delete own posts | No | Yes | Yes |
| Comment (if read) | Yes | Yes | Yes |
| Like / Save posts | Yes | Yes | Yes |
| Manage categories/tags | No | No | Yes |
| Manage users | No | No | Yes |
| Delete any post/comment | No | No | Yes |

### Implementation Details

- Spring Security for session management, password hashing, role checking
- @PreAuthorize annotations for endpoint-level authorization
- BCrypt password hashing
- CSRF protection enabled
- Rate limiting on login endpoint

### Deferred (YAGNI)

- 2FA (column kept, feature not built)
- OAuth / social login
- Email verification

## 6. Testing Strategy

### Test Levels

1. **Unit Tests** — Service methods in isolation using Mockito mocks
2. **Integration Tests** — Real PostgreSQL via Testcontainers, test repositories and full request cycles
3. **API Tests** — MockMvc for HTTP request/response validation including security rules

### Key Test Areas

| Feature | Tests |
|---|---|
| Auth | Registration, login, logout, duplicate username |
| Posts | CRUD, soft delete, premium access, pagination |
| Comments | Read-before-comment rule, 250-char limit, threading |
| Likes | Like/unlike, no duplicates |
| VIP | Payment + upgrade transaction, expiration |
| Security | Role-based access per endpoint |

### Tools

- JUnit 5 (test framework)
- Mockito (mocking)
- Testcontainers (PostgreSQL in Docker)
- MockMvc (HTTP simulation)
- AssertJ (readable assertions)
