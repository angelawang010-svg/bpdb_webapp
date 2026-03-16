# Phase 2B: Platform Features — Implementation Plan

**Version:** 2.0

(Part 2 of 2 — Tasks 11-22 of 22)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build out the complete REST API — post CRUD, comments, likes, categories, tags, saved posts, authors, subscriptions, notifications, password reset, email verification, image upload — with full test coverage validating all stored procedure business rules.

**Architecture:** Builds on Phase 1 foundation. Each feature follows the package-by-feature layout: Entity → Repository → Service → Controller → DTOs. All endpoints under `/api/v1/`. TDD throughout.

**Tech Stack:** Same as Phase 1. Additionally: Apache Tika (image validation), `@Async` + `@TransactionalEventListener` (notifications), `@Scheduled` (cleanup jobs), Spring Retry (notification resilience).

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Sections 5 (API Endpoints), 6 (Business Logic Migration), and 8 (Testing Strategy).

**Prerequisite:** Phase 2A (Tasks 1-10) must be complete, including Task 5 (save/unsave post endpoints).

## Phase 2 Parts

- **Phase 2A** (`2026-03-01-phase2a-content-crud-implementation.md`): Tasks 1–10 — Category, Tag, Post CRUD, Comments, Likes, Authors
- **Phase 2B** (this file): Tasks 11–22 — Email Service, Subscriptions, Notifications, Profiles, Password Reset, Email Verification, Image Upload, VIP Stub, Admin, Cleanup, OpenAPI, Integration Tests

---

### Task 11: Subscription System

**Files:**
- Create: `backend/src/main/java/com/blogplatform/subscription/SubscriberRepository.java`
- Create: `backend/src/main/java/com/blogplatform/subscription/SubscriptionService.java`
- Create: `backend/src/main/java/com/blogplatform/subscription/SubscriptionController.java`
- Create: `backend/src/test/java/com/blogplatform/subscription/SubscriptionServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/subscription/SubscriptionControllerIT.java`

**Step 1: Write failing unit tests**

- `subscribe_newSubscription_creates`
- `subscribe_alreadySubscribed_throwsBadRequest`
- `unsubscribe_existingSubscription_deletes`

**Step 2: Implement**

`SubscriberRepository`:
- `findByAccount_Id(Long)` → Optional
- `findAllActiveSubscribers(Pageable)` — `@Query("SELECT s FROM Subscriber s JOIN FETCH s.account WHERE s.expirationDate IS NULL OR s.expirationDate > :now")`
- `existsByAccount_Id(Long)`

`SubscriptionController`:
- `POST /api/v1/subscriptions` — authenticated
- `DELETE /api/v1/subscriptions` — authenticated

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Subscription subscribe/unsubscribe"
```

---

### Task 12: Async Notification System — SP_Create_Post_Notifications Migration

**Files:**
- Create: `backend/src/main/java/com/blogplatform/notification/NotificationRepository.java`
- Create: `backend/src/main/java/com/blogplatform/notification/NotificationService.java`
- Create: `backend/src/main/java/com/blogplatform/notification/NotificationController.java`
- Create: `backend/src/main/java/com/blogplatform/notification/dto/NotificationResponse.java`
- Create: `backend/src/main/java/com/blogplatform/notification/event/NewPostEvent.java`
- Create: `backend/src/test/java/com/blogplatform/notification/NotificationServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/notification/NotificationControllerIT.java`
- Modify: `backend/src/main/java/com/blogplatform/post/PostService.java` — publish `NewPostEvent` via `ApplicationEventPublisher`

**Step 1: Define NewPostEvent**

```java
package com.blogplatform.notification.event;

public record NewPostEvent(Long postId, String title, String authorName) {}
```

Wire into `PostService.createPost()`:
```java
applicationEventPublisher.publishEvent(new NewPostEvent(post.getId(), post.getTitle(), author.getDisplayName()));
```

**Step 2: Write failing unit tests — SP_Create_Post_Notifications business rules**

```java
@Test
void notifySubscribers_nActiveSubscribers_createsNNotifications() {
    // 3 active subscribers → 3 notifications created via saveAll()
}

@Test
void notifySubscribers_expiredSubscribersSkipped() {
    // 2 active + 1 expired → 2 notifications
}

@Test
void notifySubscribers_messageIncludesTitleAndAuthor() {
    // Notification message = "New post: {title} by {author}"
}

@Test
void notifySubscribers_batchFailure_logsErrorWithPostId() {
    // saveAll throws → ERROR logged with postId and subscriber count
}

@Test
void notifySubscribers_retriesOnTransientFailure() {
    // First saveAll throws DataAccessException → retries → succeeds on 2nd attempt
}
```

**Step 3: Implement NotificationService**

Key: `notifySubscribers()` method with batched processing and retry:
```java
@Async
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2),
           retryFor = DataAccessException.class)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void notifySubscribers(NewPostEvent event) {
    int batchSize = 500;
    int page = 0;
    while (true) {
        Page<Subscriber> subscribers = subscriberRepository.findAllActiveSubscribers(
                Instant.now(), PageRequest.of(page, batchSize));
        if (subscribers.isEmpty()) break;

        List<Notification> notifications = subscribers.stream()
                .map(sub -> {
                    Notification n = new Notification();
                    n.setAccount(sub.getAccount());
                    n.setMessage("New post: " + event.title() + " by " + event.authorName());
                    return n;
                })
                .toList();
        notificationRepository.saveAll(notifications);
        page++;
    }
}

@Recover
public void notifySubscribersFallback(DataAccessException e, NewPostEvent event) {
    log.error("All retries exhausted for post {} notification. Manual intervention required.",
            event.postId(), e);
    // TODO: Persist to dead-letter table for reprocessing
}
```

Also implement:
- `markAsRead(Long notificationId, Long accountId)` — with ownership check
- `markAllAsRead(Long accountId)` — scoped `WHERE is_read = false`

Note: Notification cleanup is handled by Task 19 (ScheduledJobs), not here.

`NotificationController`:
- `GET /api/v1/notifications` — authenticated, extracts `account_id` from security context (NEVER from request params)
- `PUT /api/v1/notifications/{id}/read` — owner only
- `PUT /api/v1/notifications/read-all` — authenticated

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add async notification system with subscriber notifications"
```

---

### Task 13: User Profile Endpoints + Saved Posts

**Prerequisite:** Phase 2A Task 5 must be complete (provides `POST/DELETE /api/v1/posts/{id}/save` endpoints and `SavedPostRepository`).

**Files:**
- Create: `backend/src/main/java/com/blogplatform/user/UserService.java`
- Create: `backend/src/main/java/com/blogplatform/user/UserController.java`
- Create: `backend/src/main/java/com/blogplatform/user/dto/UserProfileResponse.java`
- Create: `backend/src/main/java/com/blogplatform/user/dto/UpdateProfileRequest.java`
- Create: `backend/src/test/java/com/blogplatform/user/UserControllerIT.java`

**Step 1: Write integration tests**

- `GET /api/v1/users/{id}` — authenticated, returns public profile
- `PUT /api/v1/users/{id}` — owner only, updates profile
- `PUT /api/v1/users/{id}` — non-owner returns 403
- `GET /api/v1/users/{id}/saved-posts` — owner only, paginated

**Step 2: Implement**

`UserService`: profile CRUD, delegates to `UserRepository` + `UserProfile`.

`UserController` uses `@PreAuthorize("@ownershipVerifier.isOwnerOrAdmin(#id, authentication)")` on owner-only endpoints.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add User profile endpoints and saved posts listing"
```

---

### Task 14: Email Service Interface + Dev Logging Implementation

**Files:**
- Create: `backend/src/main/java/com/blogplatform/common/email/EmailService.java`
- Create: `backend/src/main/java/com/blogplatform/common/email/LoggingEmailService.java`

**Step 1: Create interface and dev implementation**

```java
package com.blogplatform.common.email;

public interface EmailService {
    void sendEmail(String to, String subject, String body);
}
```

```java
package com.blogplatform.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "test"})
public class LoggingEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("EMAIL TO: {} | SUBJECT: {} | BODY: {}", to, subject, body);
    }
}
```

Wire into AuthService for password reset and email verification token emails.

**Step 2: Verify it compiles and existing tests still pass**

Run: `cd backend && ./gradlew test`

**Step 3: Commit**

```bash
git commit -m "feat: add EmailService interface with dev logging implementation"
```

---

### Task 15: Password Reset — Forgot Password + Reset Password

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/PasswordResetToken.java` (entity)
- Create: `backend/src/main/java/com/blogplatform/auth/PasswordResetTokenRepository.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/ForgotPasswordRequest.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/ResetPasswordRequest.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthService.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthController.java`
- Create: `backend/src/test/java/com/blogplatform/auth/PasswordResetTest.java`

**Step 1: Define PasswordResetToken entity**

```java
@Entity
@Table(name = "password_reset_token", indexes = {
    @Index(name = "idx_password_reset_token_hash", columnList = "token_hash", unique = true)
})
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash; // SHA-256 hex

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
```

**Step 2: Write failing unit tests**

```java
@Test
void forgotPassword_existingEmail_createsTokenAndSendsEmail() {
    // Generates 32-byte random token, stores SHA-256 hash, sends plaintext via email
}

@Test
void forgotPassword_nonExistentEmail_returns200Anyway() {
    // Always returns same response to prevent user enumeration
}

@Test
void resetPassword_validToken_updatesPasswordAndMarksTokenUsed() {
    // Token matches hash, not expired, not used → password updated, token.used = true
}

@Test
void resetPassword_expiredToken_throwsBadRequest() {
    // Token expired (> 30 minutes) → rejected
}

@Test
void resetPassword_usedToken_throwsBadRequest() {
    // Token already used → rejected (single-use)
}

@Test
void resetPassword_unverifiedEmail_throwsBadRequest() {
    // email_verified = false → cannot reset password
}
```

**Step 3: Implement**

Token spec per design doc:
- 32 bytes cryptographically random (`SecureRandom`)
- URL-safe base64 encoded for email link
- Stored as SHA-256 hash in `password_reset_token` table (unique index on `token_hash`)
- 30-minute expiry
- Single-use (`used` boolean)
- Constant-time response (always 200 on forgot-password regardless of email existence)

Email sending: uses `EmailService` interface (implemented in Task 14).

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add password reset with secure token flow"
```

---

### Task 16: Email Verification

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/EmailVerificationToken.java` (entity)
- Create: `backend/src/main/java/com/blogplatform/auth/EmailVerificationTokenRepository.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthService.java` — add verification on register
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthController.java` — add verify-email endpoint
- Create: `backend/src/test/java/com/blogplatform/auth/EmailVerificationTest.java`

**Step 1: Define EmailVerificationToken entity**

Same structure as `PasswordResetToken`:
```java
@Entity
@Table(name = "email_verification_token", indexes = {
    @Index(name = "idx_email_verification_token_hash", columnList = "token_hash", unique = true)
})
public class EmailVerificationToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
```

**Step 2: Write failing tests**

- `register_sendsVerificationEmail`
- `verifyEmail_validToken_setsEmailVerifiedTrue`
- `verifyEmail_expiredToken_throwsBadRequest`
- `verifyEmail_usedToken_throwsBadRequest`

**Step 3: Implement**

Same token spec as password reset. On registration, create EmailVerificationToken and send email via `EmailService`. `POST /api/v1/auth/verify-email` accepts token, marks `email_verified = true`.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add email verification with token flow"
```

---

### Task 17: Image Upload with Constraints

**Files:**
- Create: `backend/src/main/java/com/blogplatform/image/ImageRepository.java`
- Create: `backend/src/main/java/com/blogplatform/image/ImageService.java`
- Create: `backend/src/main/java/com/blogplatform/image/ImageController.java`
- Create: `backend/src/test/java/com/blogplatform/image/ImageServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/image/ImageControllerIT.java`

**Step 1: Write failing unit tests**

```java
@Test
void upload_validJpeg_savesImage() {
    // Valid JPEG, under 5MB → saved with UUID filename
}

@Test
void upload_invalidMimeType_throwsBadRequest() {
    // .exe file → rejected
}

@Test
void upload_svgFile_throwsBadRequest() {
    // SVG rejected — can contain <script> tags (stored XSS risk)
}

@Test
void upload_exceedsMaxSize_throwsBadRequest() {
    // > 5MB → rejected (Spring handles via multipart config, but test it)
}

@Test
void upload_magicBytesMismatch_throwsBadRequest() {
    // File claims image/jpeg but magic bytes say PDF → rejected (Apache Tika)
}

@Test
void upload_exceedsUserQuota_throwsBadRequest() {
    // User already at 100MB → rejected
}

@Test
void upload_sanitizesFilename_usesUuid() {
    // Original filename "../../../etc/passwd.jpg" → UUID.jpg
}

@Test
void upload_exceedsMaxDimensions_throwsBadRequest() {
    // Image exceeds 10000x10000 pixels → rejected (decompression bomb protection)
}
```

**Step 2: Implement ImageService**

Key logic:
- **Allowlisted MIME types only:** JPEG (`image/jpeg`), PNG (`image/png`), GIF (`image/gif`), WebP (`image/webp`). **Reject SVG** — it can contain embedded JavaScript.
- Validate Content-Type header AND magic bytes via Apache Tika
- **Decompression bomb protection:** Use `ImageIO.read()` to decode and check dimensions. Reject images exceeding 10000x10000 pixels. Catch `OutOfMemoryError` in the decode path.
- Generate UUID-based filename: `{uuid}.{ext}`
- Save to configurable upload directory (default: `./uploads/`)
- Track cumulative size per user (query sum of file sizes or maintain counter)
- Reject if user quota (100MB) exceeded

`ImageController`:
- `POST /api/v1/posts/{postId}/images` — AUTHOR or ADMIN, multipart upload
- `GET /api/v1/images/{id}` — serves image with `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`, and `Cache-Control` headers. Do NOT serve the upload directory as a static resource — always proxy through this controller.
- `DELETE /api/v1/images/{id}` — owner or admin

<!-- TODO: Add ClamAV/antivirus scanning integration for production deployment (Phase 4+) -->

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add image upload with Tika validation and per-user quota"
```

---

### Task 18: VIP Upgrade Stub (501)

**Files:**
- Create: `backend/src/main/java/com/blogplatform/payment/PaymentService.java`
- Create: `backend/src/main/java/com/blogplatform/payment/PaymentController.java`
- Create: `backend/src/test/java/com/blogplatform/payment/PaymentControllerIT.java`

**Step 1: Write failing test**

```java
@Test
void upgradeToVip_returns501() throws Exception {
    mockMvc.perform(post("/api/v1/users/1/upgrade-vip")
                    .with(user("testuser").roles("USER")))
            .andExpect(status().is(501));
}
```

**Step 2: Implement stub**

```java
@PostMapping("/api/v1/users/{id}/upgrade-vip")
@PreAuthorize("@ownershipVerifier.isOwnerOrAdmin(#id, authentication)")
public ResponseEntity<ApiResponse<Void>> upgradeToVip(@PathVariable Long id) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(ApiResponse.error("VIP upgrade not available. Payment processing deferred to Phase 5+."));
}
```

**Step 3: Run test, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add VIP upgrade stub returning 501"
```

---

### Task 19: Admin Endpoints — Deleted Posts, Restore, Role Assignment

**Files:**
- Create: `backend/src/main/java/com/blogplatform/admin/AdminController.java`
- Create: `backend/src/main/java/com/blogplatform/admin/AdminService.java`
- Create: `backend/src/test/java/com/blogplatform/admin/AdminControllerIT.java`

**Step 1: Write integration tests**

- `GET /api/v1/admin/posts/deleted` — ADMIN only, lists soft-deleted posts (filter disabled)
- `PUT /api/v1/admin/posts/{id}/restore` — ADMIN only, sets `is_deleted = false`
- `PUT /api/v1/admin/users/{id}/role` — ADMIN only, assigns role
- All three endpoints return 403 for non-ADMIN users
- `PUT /api/v1/admin/users/{id}/role` — demoting last ADMIN returns 409 Conflict

**Step 2: Implement AdminController + AdminService**

Admin controller disables the Hibernate `activePostsFilter` to query soft-deleted posts. Uses `EntityManager` to control the filter.

**Last-admin guard:** `AdminService.assignRole()` must check: if the target user is currently ADMIN and the new role is not ADMIN, query `userRepository.countByRole(Role.ADMIN)`. If count <= 1, throw `409 Conflict` with message "Cannot demote the last admin account."

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Admin endpoints for deleted posts, restore, and role assignment"
```

---

### Task 20: Scheduled Cleanup Jobs — Notifications + ReadPost Retention

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/ScheduledJobs.java`
- Create: `backend/src/test/java/com/blogplatform/config/ScheduledJobsTest.java`

**Step 1: Write failing tests**

```java
@Test
void cleanupOldNotifications_deletesReadNotificationsOlderThan90Days() {
    // Verify repository.deleteReadNotificationsOlderThan(90 days ago) called
}

@Test
void cleanupOldReadPosts_deletesEntriesOlderThan1Year() {
    // Verify readPostRepository.deleteOlderThan(1 year ago) called
}
```

**Step 2: Implement**

```java
@Configuration
@EnableScheduling
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    private static final int DELETE_BATCH_SIZE = 10_000;

    private final NotificationRepository notificationRepository;
    private final ReadPostRepository readPostRepository;

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    @Transactional
    public void cleanupOldNotifications() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = notificationRepository.deleteReadNotificationsOlderThanBatch(cutoff, DELETE_BATCH_SIZE);
            totalDeleted += deleted;
        } while (deleted == DELETE_BATCH_SIZE);
        log.info("Cleaned up {} old read notifications", totalDeleted);
    }

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3 AM
    @Transactional
    public void cleanupOldReadPosts() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(365));
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = readPostRepository.deleteOlderThanBatch(cutoff, DELETE_BATCH_SIZE);
            totalDeleted += deleted;
        } while (deleted == DELETE_BATCH_SIZE);
        log.info("Cleaned up {} old read post entries", totalDeleted);
    }
}
```

Add batched native delete queries to repositories:
- `NotificationRepository`: `@Modifying @Query(value = "DELETE FROM notification WHERE is_read = true AND created_at < :cutoff LIMIT :batchSize", nativeQuery = true)`
- `ReadPostRepository`: `@Modifying @Query(value = "DELETE FROM read_post WHERE read_at < :cutoff LIMIT :batchSize", nativeQuery = true)`

Note: Uses native queries with `LIMIT` since JPQL does not support limit on delete. For PostgreSQL, use `DELETE FROM ... WHERE ctid IN (SELECT ctid FROM ... WHERE ... LIMIT :batchSize)` syntax.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add scheduled cleanup for notifications and read posts"
```

---

### Task 21: SpringDoc OpenAPI Configuration

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/OpenApiConfig.java`

**Step 1: Create config**

```java
package com.blogplatform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI blogPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Blog Platform API")
                        .description("REST API for the Blog Platform")
                        .version("1.0"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
```

**Step 2: Verify Swagger UI loads**

Run: `cd backend && ./gradlew bootRun`
Open: `http://localhost:8080/swagger-ui.html`
Expected: Swagger UI shows all endpoints grouped by controller, with an "Authorize" button for Bearer JWT.

**Step 3: Commit**

```bash
git commit -m "feat: add SpringDoc OpenAPI configuration"
```

---

### Task 22: Full Phase 2 Integration Test Suite

**Files:**
- Create: `backend/src/test/java/com/blogplatform/PostFlowIT.java`

**Step 1: Write comprehensive integration test**

```java
@Test
void fullPostFlow_create_read_comment_like_save_delete_restore() throws Exception {
    // 1. Login as admin, promote a user to AUTHOR
    // 2. Login as AUTHOR, create post
    // 3. Verify post appears in listing
    // 4. Login as USER, subscribe (POST /subscriptions)
    // 5. Verify notification created for subscriber after post creation
    // 6. Read post (GET /posts/{id})
    // 7. Comment on post
    // 8. Reply to comment
    // 9. Like post
    // 10. Save (bookmark) post
    // 11. Verify saved posts listing
    // 12. Mark notification as read
    // 13. Login as AUTHOR, soft-delete post
    // 14. Verify post gone from public listing
    // 15. Login as ADMIN, see deleted posts, restore
    // 16. Verify post back in public listing
    // 17. Verify admin cannot demote last admin (409)
}
```

**Step 2: Run all tests**

Run: `cd backend && ./gradlew test`
Expected: ALL tests pass — unit and integration.

**Step 3: Commit**

```bash
git commit -m "test: add full post flow integration test covering all features"
```

---

## Summary

Phase 2 delivers (22 tasks across 2A + 2B):
- Category CRUD (admin-only write)
- Tag list and admin-only create
- Post CRUD with soft delete, pagination, filtering, full-text search
- Post audit logging (PostEntityListener + service-level update logging)
- Read tracking (mark posts as read)
- Comment system with threading, read-before-comment, 250-char limit, max depth 3
- Like/unlike (idempotent)
- Author profiles with post counts
- Subscription subscribe/unsubscribe
- Async notification system (SP_Create_Post_Notifications migration) with retry and batching
- Notification retention (90-day batched cleanup for read)
- ReadPost retention (1-year batched cleanup)
- User profile endpoints + saved posts listing
- Email service interface with dev logging
- Password reset (secure token flow with defined entity schema)
- Email verification (secure token flow with defined entity schema)
- Image upload (Tika validation, MIME allowlist, dimension limits, per-user quota, UUID filenames)
- VIP upgrade stub (501)
- Admin endpoints (deleted posts, restore, role assignment with last-admin guard)
- SpringDoc OpenAPI with Bearer JWT security scheme
- Full integration test suite covering subscriptions and notifications

**Next plan:** Phase 3 (Front-End — React + TypeScript SPA)

---

## Changelog

### v2.0 (2026-03-15) — Per critical-review-1

- **Removed Task 20 (Account Lockout):** Already implemented in Phase 1. `LoginAttemptService` and `AuthService` integration exist. Renumbered Tasks 21-23 → 20-22.
- **Task 11:** Fixed repository method signatures from `findByAccountId(Long)` to `findByAccount_Id(Long)` to match `Subscriber` entity's `UserAccount account` relationship. Added `JOIN FETCH` and `Pageable` to `findAllActiveSubscribers()`.
- **Task 12:** Fixed `notifySubscribers()` to use `n.setAccount(sub.getAccount())` instead of non-existent `n.setAccountId()`. Added batched processing (500/batch) to prevent OOM on large subscriber counts. Added `@Retryable` with exponential backoff and `@Recover` fallback for transient failures. Defined `NewPostEvent` record and `ApplicationEventPublisher` wiring in `PostService`. Removed `cleanupOldNotifications()` (consolidated in Task 20).
- **Task 13:** Added prerequisite note — depends on Phase 2A Task 5 for save/unsave endpoints.
- **Task 14 (now 15):** Moved after EmailService task. Defined `PasswordResetToken` entity with fields, `@Table` mapping, and unique index on `token_hash`.
- **Task 15 (now 16):** Defined `EmailVerificationToken` entity with same structure as `PasswordResetToken`.
- **Task 16 (now 17):** Added MIME type allowlist (JPEG/PNG/GIF/WebP), explicit SVG rejection, decompression bomb protection via `ImageIO.read()` dimension check (max 10000x10000), `GET /api/v1/images/{id}` serving endpoint with security headers, ClamAV TODO for production.
- **Task 18 (now 19):** Added last-admin guard — prevents demoting the last ADMIN account (returns 409 Conflict). Added `AdminService` to file list.
- **Task 19 (now 20):** Replaced single-transaction bulk deletes with batched native queries (10,000 per batch) to avoid long-held locks. Added PostgreSQL-compatible `LIMIT` syntax note.
- **Task 21 (now 14):** Reordered before Tasks 15/16 which depend on `EmailService`.
- **Task 22 (now 21):** Added Bearer JWT `SecurityScheme` so Swagger UI has an "Authorize" button.
- **Task 23 (now 22):** Added subscription, notification, mark-as-read, and last-admin-guard steps to the integration test flow.
- **Header:** Updated task count from 23 to 22. Added Spring Retry to tech stack. Updated Phase 2B task listing.
