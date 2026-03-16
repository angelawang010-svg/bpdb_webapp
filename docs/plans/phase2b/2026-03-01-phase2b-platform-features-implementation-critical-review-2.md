# Critical Implementation Review — Phase 2B Platform Features

**Reviewed:** `docs/plans/phase2b/2026-03-01-phase2b-platform-features-implementation.md` (v2.0)
**Review Version:** 2
**Date:** 2026-03-15
**Previous Review:** critical-review-1 — all issues addressed in v2.0

---

## 1. Overall Assessment

The v2.0 plan is substantially improved. All critical issues from review-1 (duplicate task, wrong entity API, unbounded queries, missing retry, image security, undefined token entities, last-admin guard, task ordering) have been addressed. The plan is now close to implementation-ready.

**Strengths:**
- Batched notification processing with `@Retryable` + `@Recover` fallback is well-designed
- Token entities (Tasks 15/16) are fully specified with indexes and field definitions
- Image upload security is comprehensive: MIME allowlist, SVG rejection, Tika magic-byte validation, decompression bomb protection, secure serving endpoint
- Last-admin guard on role assignment prevents lockout
- Task ordering is now correct (EmailService before password reset/email verification)

**Remaining Concerns:**
- `@Retryable` on `@TransactionalEventListener` + `@Async` has a subtle interaction that may not work as expected
- Batched delete queries use MySQL `LIMIT` syntax, not PostgreSQL-compatible
- Missing rate limiting on security-sensitive endpoints
- `@Transactional` on `@Scheduled` methods holds a transaction across potentially many batch iterations

---

## 2. Critical Issues

### 2.1 Task 12: `@Retryable` + `@Async` + `@TransactionalEventListener` Annotation Interaction

- **Description:** The `notifySubscribers()` method combines `@Async`, `@Retryable`, and `@TransactionalEventListener`. Spring Retry's `@Retryable` works via AOP proxies. When `@Async` is also present, the method is invoked through the async executor, which may bypass the retry proxy depending on proxy ordering. Additionally, `@Retryable` wraps the entire method — if the first batch succeeds and the second batch fails, retry re-executes from the beginning, re-creating notifications for batch 1 (duplicate notifications).
- **Impact:** Either retry silently doesn't work, or retry creates duplicate notifications for already-processed batches.
- **Fix:**
  1. Separate concerns: keep `@TransactionalEventListener` + `@Async` on a thin dispatcher method that calls a separate `@Retryable` method **per batch**. This ensures retry is scoped to the failing batch only.
  2. Alternatively, make notifications idempotent by adding a unique constraint on `(account_id, post_id)` in the notification table, and use `INSERT ... ON CONFLICT DO NOTHING` or check-before-insert logic.
  3. Verify proxy ordering with an integration test that confirms retry actually fires after a transient failure.

### 2.2 Task 20: Batched Delete Uses MySQL Syntax, Not PostgreSQL

- **Description:** The native queries use `DELETE FROM notification WHERE ... LIMIT :batchSize`. PostgreSQL does not support `LIMIT` on `DELETE` statements. The plan notes the PostgreSQL `ctid` workaround but presents the MySQL syntax as the primary implementation.
- **Impact:** Queries will fail at runtime on PostgreSQL.
- **Fix:** Use the PostgreSQL-compatible syntax as the primary implementation:
  ```sql
  DELETE FROM notification WHERE id IN (
      SELECT id FROM notification WHERE is_read = true AND created_at < :cutoff LIMIT :batchSize
  )
  ```
  This works on both PostgreSQL and (with minor adjustment) MySQL. Same fix needed for the `read_post` cleanup query.

### 2.3 Task 20: `@Transactional` on `@Scheduled` Holds Transaction Across All Batches

- **Description:** The `cleanupOldNotifications()` and `cleanupOldReadPosts()` methods are annotated with `@Transactional` and run a `do...while` loop deleting 10,000 rows per iteration. The `@Transactional` keeps a single transaction open for the entire loop — potentially millions of rows across many iterations.
- **Impact:** Long-running transaction holds locks, blocks writes to the notification/read_post tables, and risks transaction timeout. Defeats the purpose of batching.
- **Fix:** Remove `@Transactional` from the `@Scheduled` methods. Instead, put `@Transactional` (with `REQUIRES_NEW` propagation) on the repository batch-delete methods themselves, so each batch commits independently. The loop in the scheduled method becomes a series of small, independent transactions:
  ```java
  // No @Transactional here
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
  ```
  With `deleteReadNotificationsOlderThanBatch` annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)`.

### 2.4 Task 15: No Rate Limiting on Forgot-Password Endpoint

- **Description:** `POST /api/v1/auth/forgot-password` always returns 200 to prevent user enumeration — good. However, there is no rate limiting. An attacker can flood this endpoint to generate thousands of token rows and trigger mass email sends (even via the logging implementation in dev, this sets a bad pattern).
- **Impact:** Token table bloat, potential email bombing in production, resource exhaustion.
- **Fix:** Add rate limiting: maximum 3 forgot-password requests per email per hour, and a global rate limit per IP (e.g., 10 requests per minute). Use an existing rate-limiting mechanism (e.g., Bucket4j, or a simple `@RateLimiter` if available) or add a check in `AuthService` against the count of recent tokens for the same account. At minimum, document this as a required production hardening step.

### 2.5 Task 15/16: No Cleanup of Expired Tokens

- **Description:** `PasswordResetToken` and `EmailVerificationToken` rows accumulate indefinitely. Expired and used tokens are never deleted. Task 20 (ScheduledJobs) only cleans up notifications and read posts.
- **Impact:** Token tables grow unbounded over time. The unique index on `token_hash` makes this a slow degradation rather than an acute failure, but it's still unbounded growth.
- **Fix:** Add a cleanup job to Task 20's `ScheduledJobs`:
  ```java
  @Scheduled(cron = "0 30 2 * * *")
  public void cleanupExpiredTokens() {
      passwordResetTokenRepository.deleteExpiredOrUsedOlderThan(Instant.now().minus(Duration.ofDays(7)));
      emailVerificationTokenRepository.deleteExpiredOrUsedOlderThan(Instant.now().minus(Duration.ofDays(7)));
  }
  ```

---

## 3. Minor Issues & Improvements

### 3.1 Task 12: `@Recover` Method Signature Must Match

- The `@Recover` method `notifySubscribersFallback(DataAccessException e, NewPostEvent event)` must have the same return type as the retryable method. Since `notifySubscribers` returns `void`, this is fine. However, the `@Recover` method must also be in the same class and the parameter types must match exactly (exception type first, then method parameters). Verify this compiles correctly — Spring Retry is strict about signature matching.

### 3.2 Task 12: Event Published Before Post Fully Visible

- `PostService.createPost()` publishes `NewPostEvent` inside the method. With `@TransactionalEventListener(phase = AFTER_COMMIT)`, the event fires after the post transaction commits — good. However, if `createPost()` is not itself `@Transactional`, the event fires immediately (no transaction to listen to). Verify that `PostService.createPost()` is `@Transactional`.

### 3.3 Task 17: `ImageIO.read()` Loads Entire Image Into Memory

- Using `ImageIO.read()` to check dimensions loads the full decoded image into heap memory. A 10000x10000 RGBA image is ~400MB. The plan mentions catching `OutOfMemoryError`, but this is fragile.
- **Improvement:** Use `ImageIO.createImageInputStream()` + `ImageReader.getWidth()/getHeight()` to read dimensions from the image header without decoding the full bitmap:
  ```java
  try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (readers.hasNext()) {
          ImageReader reader = readers.next();
          reader.setInput(iis);
          int width = reader.getWidth(0);
          int height = reader.getHeight(0);
          reader.dispose();
      }
  }
  ```

### 3.4 Task 17: No Content-Length Header on Image Serving

- The `GET /api/v1/images/{id}` endpoint should set `Content-Length` to enable proper progress bars and caching behavior in clients. Without it, chunked transfer encoding is used, which is less efficient for known-size static resources.

### 3.5 Task 11: Missing `@Param` on Repository Query

- `findAllActiveSubscribers()` uses `@Query` with `:now` parameter but the method signature shows `Pageable` only. The `Instant now` parameter needs to be passed explicitly with `@Param("now")`:
  ```java
  @Query("SELECT s FROM Subscriber s JOIN FETCH s.account WHERE s.expirationDate IS NULL OR s.expirationDate > :now")
  Page<Subscriber> findAllActiveSubscribers(@Param("now") Instant now, Pageable pageable);
  ```

### 3.6 Task 19: Admin Deleted Posts — Filter Disabling Is Session-Scoped

- The plan says AdminController disables the Hibernate `activePostsFilter` via `EntityManager`. Hibernate filters are session-scoped — disabling a filter affects the entire session/transaction, not just the current query. If the same session is reused (e.g., OpenSessionInView), other queries in the same request may also see deleted posts.
- **Improvement:** Use a dedicated `@Query` with explicit `WHERE is_deleted = true` rather than toggling global filters. This is more predictable and thread-safe.

### 3.7 Task 22: Integration Test Timing for Async Notifications

- Step 5 of the integration test ("Verify notification created for subscriber after post creation") depends on the `@Async` notification handler completing. Without an explicit wait/poll mechanism, this assertion may be flaky.
- **Improvement:** Use `Awaitility` (already common in Spring test suites) to poll for the notification within a timeout:
  ```java
  await().atMost(5, SECONDS).until(() -> notificationRepository.countByAccountId(subscriberId) > 0);
  ```

---

## 4. Questions for Clarification

1. **Task 12:** Has the team verified that Spring Retry's `@Retryable` works correctly when stacked with `@Async` + `@TransactionalEventListener` on the same method? This is a non-trivial proxy ordering question that should be validated with a spike.

2. **Task 17:** Is the 100MB per-user quota tracked via a counter column on the user entity, or computed on-the-fly via `SUM(file_size)` query? The former is faster but requires careful synchronization; the latter is simpler but slower for users with many images.

3. **Task 20:** What database is targeted? The plan references both MySQL `LIMIT` syntax and a PostgreSQL `ctid` note. The primary syntax should match the actual database.

---

## 5. Final Recommendation

**Approve with changes.**

The v2.0 plan is fundamentally sound. The five critical issues are targeted fixes, not architectural rework:

1. **Separate retry scope from event listener** in Task 12 — prevent duplicate notifications on retry
2. **Use PostgreSQL-compatible DELETE syntax** in Task 20 — current queries won't execute
3. **Remove `@Transactional` from scheduled methods** in Task 20 — defeats batching purpose
4. **Add rate limiting to forgot-password** in Task 15 — prevent abuse
5. **Add expired token cleanup** to Task 20 — prevent unbounded table growth

All other items are minor improvements that can be addressed during implementation.
