# Phase 2B: Platform Features — Implementation Plan

(Part 2 of 2 — Tasks 11-23 of 23)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build out the complete REST API — post CRUD, comments, likes, categories, tags, saved posts, authors, subscriptions, notifications, password reset, email verification, image upload — with full test coverage validating all stored procedure business rules.

**Architecture:** Builds on Phase 1 foundation. Each feature follows the package-by-feature layout: Entity → Repository → Service → Controller → DTOs. All endpoints under `/api/v1/`. TDD throughout.

**Tech Stack:** Same as Phase 1. Additionally: Apache Tika (image validation), `@Async` + `@TransactionalEventListener` (notifications), `@Scheduled` (cleanup jobs).

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Sections 5 (API Endpoints), 6 (Business Logic Migration), and 8 (Testing Strategy).

**Prerequisite:** Phase 2A (Tasks 1-10) must be complete.

## Phase 2 Parts

- **Phase 2A** (`2026-03-01-phase2a-content-crud-implementation.md`): Tasks 1–10 — Category, Tag, Post CRUD, Comments, Likes, Authors
- **Phase 2B** (this file): Tasks 11–23 — Subscriptions, Notifications, Profiles, Password Reset, Email, Image Upload, Admin, Cleanup, OpenAPI, Integration Tests

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
- `findByAccountId(Long)` → Optional
- `findAllActiveSubscribers()` — `@Query` where `expiration_date IS NULL OR expiration_date > NOW()`
- `existsByAccountId(Long)`

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
- Create: `backend/src/test/java/com/blogplatform/notification/NotificationServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/notification/NotificationControllerIT.java`

**Step 1: Write failing unit tests — SP_Create_Post_Notifications business rules**

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
```

**Step 2: Implement NotificationService**

Key: `notifySubscribers()` method:
```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void notifySubscribers(NewPostEvent event) {
    try {
        List<Subscriber> subscribers = subscriberRepository.findAllActiveSubscribers();
        List<Notification> notifications = subscribers.stream()
                .map(sub -> {
                    Notification n = new Notification();
                    n.setAccountId(sub.getAccountId());
                    n.setMessage("New post: " + event.title() + " by " + event.authorName());
                    return n;
                })
                .toList();
        notificationRepository.saveAll(notifications);
    } catch (Exception e) {
        log.error("Failed to notify subscribers for post {}: {} subscribers affected",
                event.postId(), e.getMessage(), e);
    }
}
```

Also implement:
- `markAsRead(Long notificationId, Long accountId)` — with ownership check
- `markAllAsRead(Long accountId)` — scoped `WHERE is_read = false`
- `cleanupOldNotifications()` — `@Scheduled`, deletes read notifications > 90 days old

`NotificationController`:
- `GET /api/v1/notifications` — authenticated, extracts `account_id` from security context (NEVER from request params)
- `PUT /api/v1/notifications/{id}/read` — owner only
- `PUT /api/v1/notifications/read-all` — authenticated

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add async notification system with subscriber notifications"
```

---

### Task 13: User Profile Endpoints + Saved Posts

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

### Task 14: Password Reset — Forgot Password + Reset Password

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/PasswordResetToken.java` (entity — if not in Task 13 of Phase 1)
- Create: `backend/src/main/java/com/blogplatform/auth/PasswordResetTokenRepository.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/ForgotPasswordRequest.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/ResetPasswordRequest.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthService.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthController.java`
- Create: `backend/src/test/java/com/blogplatform/auth/PasswordResetTest.java`

**Step 1: Write failing unit tests**

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

**Step 2: Implement**

Token spec per design doc:
- 32 bytes cryptographically random (`SecureRandom`)
- URL-safe base64 encoded for email link
- Stored as SHA-256 hash in `password_reset_token` table
- 30-minute expiry
- Single-use (`used` boolean)
- Constant-time response (always 200 on forgot-password regardless of email existence)

Email sending: use a simple interface (`EmailService`) that can be stubbed. Actual implementation (Mailgun/SendGrid) wired later. For now, log the token to console in dev mode.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add password reset with secure token flow"
```

---

### Task 15: Email Verification

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/EmailVerificationToken.java` (entity)
- Create: `backend/src/main/java/com/blogplatform/auth/EmailVerificationTokenRepository.java`
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthService.java` — add verification on register
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthController.java` — add verify-email endpoint
- Create: `backend/src/test/java/com/blogplatform/auth/EmailVerificationTest.java`

**Step 1: Write failing tests**

- `register_sendsVerificationEmail`
- `verifyEmail_validToken_setsEmailVerifiedTrue`
- `verifyEmail_expiredToken_throwsBadRequest`
- `verifyEmail_usedToken_throwsBadRequest`

**Step 2: Implement**

Same token spec as password reset. On registration, create EmailVerificationToken and send email. `POST /api/v1/auth/verify-email` accepts token, marks `email_verified = true`.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add email verification with token flow"
```

---

### Task 16: Image Upload with Constraints

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
```

**Step 2: Implement ImageService**

Key logic:
- Validate Content-Type header AND magic bytes via Apache Tika
- Generate UUID-based filename: `{uuid}.{ext}`
- Save to configurable upload directory (default: `./uploads/`)
- Track cumulative size per user (query sum of file sizes or maintain counter)
- Reject if user quota (100MB) exceeded

`ImageController`:
- `POST /api/v1/posts/{postId}/images` — AUTHOR or ADMIN, multipart upload
- `DELETE /api/v1/images/{id}` — owner or admin

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add image upload with Tika validation and per-user quota"
```

---

### Task 17: VIP Upgrade Stub (501)

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

### Task 18: Admin Endpoints — Deleted Posts, Restore, Role Assignment

**Files:**
- Create: `backend/src/main/java/com/blogplatform/admin/AdminController.java`
- Create: `backend/src/test/java/com/blogplatform/admin/AdminControllerIT.java`

**Step 1: Write integration tests**

- `GET /api/v1/admin/posts/deleted` — ADMIN only, lists soft-deleted posts (filter disabled)
- `PUT /api/v1/admin/posts/{id}/restore` — ADMIN only, sets `is_deleted = false`
- `PUT /api/v1/admin/users/{id}/role` — ADMIN only, assigns role
- All three endpoints return 403 for non-ADMIN users

**Step 2: Implement AdminController**

Admin controller disables the Hibernate `activePostsFilter` to query soft-deleted posts. Uses `EntityManager` to control the filter.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Admin endpoints for deleted posts, restore, and role assignment"
```

---

### Task 19: Scheduled Cleanup Jobs — Notifications + ReadPost Retention

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

    private final NotificationRepository notificationRepository;
    private final ReadPostRepository readPostRepository;

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    @Transactional
    public void cleanupOldNotifications() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        notificationRepository.deleteReadNotificationsOlderThan(cutoff);
    }

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3 AM
    @Transactional
    public void cleanupOldReadPosts() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(365));
        readPostRepository.deleteOlderThan(cutoff);
    }
}
```

Add custom delete queries to repositories:
- `NotificationRepository`: `@Modifying @Query("DELETE FROM Notification n WHERE n.isRead = true AND n.createdAt < :cutoff")`
- `ReadPostRepository`: `@Modifying @Query("DELETE FROM ReadPost r WHERE r.readAt < :cutoff")`

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add scheduled cleanup for notifications and read posts"
```

---

### Task 20: Account Lockout — Redis-backed Failed Login Tracking

**Files:**
- Modify: `backend/src/main/java/com/blogplatform/auth/AuthService.java`
- Create: `backend/src/main/java/com/blogplatform/auth/LoginAttemptService.java`
- Create: `backend/src/test/java/com/blogplatform/auth/LoginAttemptServiceTest.java`

**Step 1: Write failing unit tests**

```java
@Test
void authenticate_accountLocked_throwsLockedException() {
    // 5 consecutive failures → account locked for 15 minutes
}

@Test
void authenticate_successfulLogin_resetsFailureCounter() {
    // After successful login, failure count back to 0
}

@Test
void recordFailure_incrementsCountInRedis() {
    // Each failure increments counter with 15-minute TTL
}
```

**Step 2: Implement LoginAttemptService**

Uses Spring Data Redis (`StringRedisTemplate`):
- Key: `login:failures:{username}`
- TTL: 15 minutes
- On failure: increment counter
- On 5+ failures: throw `423 Locked`
- On success: delete key

Modify `AuthService.authenticate()` to check/update `LoginAttemptService`.

**Step 3: Run tests, verify pass**

Note: Tests for this may need embedded Redis or can mock `StringRedisTemplate`.

**Step 4: Commit**

```bash
git commit -m "feat: add Redis-backed account lockout after 5 failed logins"
```

---

### Task 21: Email Service Interface + Dev Logging Implementation

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

### Task 22: SpringDoc OpenAPI Configuration

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/OpenApiConfig.java`

**Step 1: Create config**

```java
package com.blogplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
                        .version("1.0"));
    }
}
```

**Step 2: Verify Swagger UI loads**

Run: `cd backend && ./gradlew bootRun`
Open: `http://localhost:8080/swagger-ui.html`
Expected: Swagger UI shows all endpoints grouped by controller.

**Step 3: Commit**

```bash
git commit -m "feat: add SpringDoc OpenAPI configuration"
```

---

### Task 23: Full Phase 2 Integration Test Suite

**Files:**
- Create: `backend/src/test/java/com/blogplatform/PostFlowIT.java`

**Step 1: Write comprehensive integration test**

```java
@Test
void fullPostFlow_create_read_comment_like_save_delete_restore() throws Exception {
    // 1. Login as admin, promote a user to AUTHOR
    // 2. Login as AUTHOR, create post
    // 3. Verify post appears in listing
    // 4. Login as USER, read post (GET /posts/{id})
    // 5. Comment on post
    // 6. Reply to comment
    // 7. Like post
    // 8. Save (bookmark) post
    // 9. Verify saved posts listing
    // 10. Login as AUTHOR, soft-delete post
    // 11. Verify post gone from public listing
    // 12. Login as ADMIN, see deleted posts, restore
    // 13. Verify post back in public listing
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

Phase 2 delivers (23 tasks):
- Category CRUD (admin-only write)
- Tag list and admin-only create
- Post CRUD with soft delete, pagination, filtering, full-text search
- Post audit logging (PostEntityListener + service-level update logging)
- Read tracking (mark posts as read)
- Comment system with threading, read-before-comment, 250-char limit, max depth 3
- Like/unlike (idempotent)
- Author profiles with post counts
- Subscription subscribe/unsubscribe
- Async notification system (SP_Create_Post_Notifications migration)
- Notification retention (90-day cleanup for read)
- ReadPost retention (1-year cleanup)
- User profile endpoints + saved posts
- Password reset (secure token flow)
- Email verification (secure token flow)
- Image upload (Tika validation, per-user quota, UUID filenames)
- VIP upgrade stub (501)
- Admin endpoints (deleted posts, restore, role assignment)
- Account lockout (Redis-backed, 5 failures → 15 min lock)
- Email service interface with dev logging
- SpringDoc OpenAPI
- Full integration test suite

**Next plan:** Phase 3 (Front-End — React + TypeScript SPA)
