# Phase 2A: Content & CRUD — Implementation Plan

(Part 1 of 2 — Tasks 1-9 of 21)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build out the complete REST API — post CRUD, comments, likes, categories, tags, saved posts, authors, subscriptions, notifications, password reset, email verification, image upload — with full test coverage validating all stored procedure business rules.

**Architecture:** Builds on Phase 1 foundation. Each feature follows the package-by-feature layout: Entity → Repository → Service → Controller → DTOs. All endpoints under `/api/v1/`. TDD throughout.

**Tech Stack:** Same as Phase 1. Additionally: Apache Tika (image validation), `@Async` + `@TransactionalEventListener` (notifications), `@Scheduled` (cleanup jobs).

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Sections 5 (API Endpoints), 6 (Business Logic Migration), and 8 (Testing Strategy).

**Prerequisite:** Phase 1 must be complete (all 15 tasks).

## Phase 2 Parts

- **Phase 2A** (this file): Tasks 1–9 — Category, Tag, Post CRUD, Comments, Likes, Authors
- **Phase 2B** (`2026-03-01-phase2b-platform-features-implementation.md`): Tasks 11–22 — Email Service, Subscriptions, Notifications, Profiles, Password Reset, Email Verification, Image Upload, VIP Stub, Admin, Cleanup, OpenAPI, Integration Tests (Task 10 was removed in v2.0 — account lockout already implemented in Phase 1)

## Cross-Cutting Conventions (All Tasks)

These conventions apply to every task in this plan:

- **Logging:** All service classes use `@Slf4j` (Lombok). Log mutations at `info` level with entity IDs (`log.info("Created category id={}", cat.getCategoryId())`). Log business rule rejections at `warn` level (`log.warn("User {} attempted to comment on unread post {}", userId, postId)`).
- **Read-only transactions:** All read-only service methods use `@Transactional(readOnly = true)` for dirty-checking optimization and read-replica routing readiness.
- **Integration test base class:** All `*IT` classes extend `BaseIntegrationTest` (from Phase 1) instead of defining their own Testcontainers setup. Do NOT duplicate `@Container`/`@DynamicPropertySource` boilerplate.
- **Test isolation:** All `*IT` classes are annotated with `@Transactional` for automatic rollback between tests, preventing inter-test data leakage.
- **Concurrency safety on unique constraints:** Any service method that checks uniqueness before saving (e.g., `existsByName` → `save`) must also catch `DataIntegrityViolationException` and translate it to `BadRequestException`. The pre-check provides fast user feedback; the catch guards against TOCTOU races.
- **DTO mapping:** All response DTOs provide a static `from()` factory method for entity-to-DTO conversion (e.g., `CategoryResponse.from(Category c)`). Controllers use this instead of inline `new ResponseDto(...)` calls.
- **Content contract — server-side sanitization (SECURITY):** All content fields (post content, comment content) store raw Markdown. The backend renders Markdown to HTML using commonmark-java and sanitizes the output with OWASP Java HTML Sanitizer (allowlist policy: standard formatting tags, links, images, code blocks — no `<script>`, `<iframe>`, event handlers, `javascript:` URIs). Content responses include both `contentRaw` (original Markdown for editing) and `contentHtml` (sanitized HTML for display). This is a **security-critical path** — all content must flow through `MarkdownSanitizer.sanitize()` before inclusion in any response DTO. See Task 4 for implementation details.
- **Hibernate soft-delete filter activation:** `PostService` methods that should exclude deleted posts (`listPosts()`, `getPost()`, search methods) must explicitly enable the Hibernate `deletedFilter` before executing queries. Admin methods (restore, list deleted) must NOT enable it. Pattern:
  ```java
  private void enableDeletedFilter() {
      Session session = entityManager.unwrap(Session.class);
      session.enableFilter("deletedFilter").setParameter("isDeleted", false);
  }
  ```
  `PostService` requires an injected `EntityManager` for this purpose.
- **Authentication-aware public endpoints:** Public endpoints that perform user-specific side effects (e.g., `getPost()` calling `markAsRead()`) must guard those side effects with an authentication null-check. Pass `Authentication` (nullable) from the controller. When null/anonymous: skip `markAsRead()`, block premium posts with 403.
- **Per-endpoint rate limiting (SECURITY):** Extend the existing `RateLimitFilter` with tighter per-endpoint limits for spam-sensitive endpoints. Limits: comment creation (`POST /api/v1/posts/*/comments`): 10 req/min per user; like/unlike (`POST|DELETE /api/v1/posts/*/likes`): 30 req/min per user. Add these as additional tiers in the existing Caffeine-based filter alongside the current auth/anonymous tiers. See Task 7 (CommentController) and Task 8 (LikeController) for integration points.

---

### Task 1: Category CRUD

**Files:**
- Create: `backend/src/main/java/com/blogplatform/category/CategoryRepository.java`
- Create: `backend/src/main/java/com/blogplatform/category/CategoryService.java`
- Create: `backend/src/main/java/com/blogplatform/category/CategoryController.java`
- Create: `backend/src/main/java/com/blogplatform/category/dto/CreateCategoryRequest.java`
- Create: `backend/src/main/java/com/blogplatform/category/dto/CategoryResponse.java`
- Create: `backend/src/test/java/com/blogplatform/category/CategoryServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/category/CategoryControllerIT.java`

**Step 1: Write the failing unit test**

Create `backend/src/test/java/com/blogplatform/category/CategoryServiceTest.java`:
```java
package com.blogplatform.category;

import com.blogplatform.category.dto.CreateCategoryRequest;
import com.blogplatform.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    @Test
    void create_withValidName_savesCategory() {
        var request = new CreateCategoryRequest("Tech", "Technology posts");
        when(categoryRepository.existsByCategoryName("Tech")).thenReturn(false);
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = categoryService.create(request);
        assertThat(result.getCategoryName()).isEqualTo("Tech");
    }

    @Test
    void create_withDuplicateName_throwsBadRequest() {
        var request = new CreateCategoryRequest("Tech", "duplicate");
        when(categoryRepository.existsByCategoryName("Tech")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(BadRequestException.class);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.category.CategoryServiceTest" -i`
Expected: FAIL — classes don't exist.

**Step 3: Write DTOs, Repository, Service, Controller**

Create `backend/src/main/java/com/blogplatform/category/dto/CreateCategoryRequest.java`:
```java
package com.blogplatform.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name must be at most 100 characters")
        String categoryName,
        String description
) {}
```

> **Note:** `CreateCategoryRequest` is reused for PUT updates. This is intentional — PUT is a full-replacement operation, so all fields are required on update as well.

Create `backend/src/main/java/com/blogplatform/category/dto/CategoryResponse.java`:
```java
package com.blogplatform.category.dto;

import com.blogplatform.category.Category;

public record CategoryResponse(Long categoryId, String categoryName, String description) {

    public static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getCategoryId(), c.getCategoryName(), c.getDescription());
    }
}
```

Create `backend/src/main/java/com/blogplatform/category/CategoryRepository.java`:
```java
package com.blogplatform.category;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    boolean existsByCategoryName(String categoryName);
    boolean existsByCategoryNameAndCategoryIdNot(String categoryName, Long categoryId);
}
```

Create `backend/src/main/java/com/blogplatform/category/CategoryService.java`:
```java
package com.blogplatform.category;

import com.blogplatform.category.dto.CreateCategoryRequest;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    @Transactional
    public Category create(CreateCategoryRequest request) {
        if (categoryRepository.existsByCategoryName(request.categoryName())) {
            throw new BadRequestException("Category name already exists");
        }
        Category category = new Category();
        category.setCategoryName(request.categoryName());
        category.setDescription(request.description());
        try {
            Category saved = categoryRepository.save(category);
            log.info("Created category id={} name='{}'", saved.getCategoryId(), saved.getCategoryName());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Category name already exists");
        }
    }

    @Transactional
    public Category update(Long id, CreateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (categoryRepository.existsByCategoryNameAndCategoryIdNot(request.categoryName(), id)) {
            throw new BadRequestException("Category name already exists");
        }
        category.setCategoryName(request.categoryName());
        category.setDescription(request.description());
        try {
            Category saved = categoryRepository.save(category);
            log.info("Updated category id={} name='{}'", saved.getCategoryId(), saved.getCategoryName());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Category name already exists");
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category not found");
        }
        try {
            categoryRepository.deleteById(id);
            log.info("Deleted category id={}", id);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Category has posts assigned — reassign or delete them first");
        }
    }
}
```

Create `backend/src/main/java/com/blogplatform/category/CategoryController.java`:
```java
package com.blogplatform.category;

import com.blogplatform.category.dto.CategoryResponse;
import com.blogplatform.category.dto.CreateCategoryRequest;
import com.blogplatform.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAll() {
        List<CategoryResponse> categories = categoryService.findAll().stream()
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest request) {
        Category cat = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CategoryResponse.from(cat)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(@PathVariable Long id, @Valid @RequestBody CreateCategoryRequest request) {
        Category cat = categoryService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(CategoryResponse.from(cat)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Category deleted"));
    }
}
```

**Step 4: Run unit test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.category.CategoryServiceTest" -i`
Expected: PASS

**Step 5: Write integration test**

Create `backend/src/test/java/com/blogplatform/category/CategoryControllerIT.java`:
```java
package com.blogplatform.category;

import com.blogplatform.BaseIntegrationTest;
import com.blogplatform.category.dto.CreateCategoryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Transactional
class CategoryControllerIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getCategories_public_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCategory_asAdmin_returns201() throws Exception {
        var request = new CreateCategoryRequest("NewCat", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "USER")
    void createCategory_asUser_returns403() throws Exception {
        var request = new CreateCategoryRequest("Nope", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateCategory_asAdmin_returns200() throws Exception {
        // Create first, then update
        var create = new CreateCategoryRequest("Original", "desc");
        var result = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andReturn();
        // Extract ID from response, then PUT update
        var update = new CreateCategoryRequest("Updated", "new desc");
        // PUT /api/v1/categories/{id} with extracted ID → expect 200
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateCategory_duplicateName_returns400() throws Exception {
        // Create two categories, then try to rename second to first's name → 400
    }

    @Test
    @WithMockUser(roles = "USER")
    void updateCategory_asUser_returns403() throws Exception {
        var request = new CreateCategoryRequest("Nope", "desc");
        mockMvc.perform(put("/api/v1/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
```

**Step 6: Run integration test**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.category.CategoryControllerIT" -i`
Expected: PASS

**Step 7: Commit**

```bash
git add backend/src/main/java/com/blogplatform/category/ backend/src/test/java/com/blogplatform/category/
git commit -m "feat: add Category CRUD with admin-only create/update/delete"
```

---

### Task 2: Tag CRUD

**Files:**
- Create: `backend/src/main/java/com/blogplatform/tag/TagRepository.java`
- Create: `backend/src/main/java/com/blogplatform/tag/TagService.java`
- Create: `backend/src/main/java/com/blogplatform/tag/TagController.java`
- Create: `backend/src/main/java/com/blogplatform/tag/dto/CreateTagRequest.java`
- Create: `backend/src/main/java/com/blogplatform/tag/dto/TagResponse.java`
- Create: `backend/src/test/java/com/blogplatform/tag/TagServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/tag/TagControllerIT.java`

Follows identical pattern to Category. Key differences:
- `TagRepository` needs `findByTagNameIn(Set<String> names)` for bulk lookup (reserved for future tag-by-name resolution; not used in Phase 2A where posts reference tags by ID)
- Only GET (public) and POST (admin) — no PUT/DELETE per design doc
- Tag entity has `tag_id` and `tag_name` (unique)

**Step 1: Write failing unit tests**

Create `backend/src/test/java/com/blogplatform/tag/TagServiceTest.java` with:
- `create_withValidName_savesTag`
- `create_withDuplicateName_throwsBadRequest` — via pre-check
- `create_concurrentDuplicate_catchesConstraintViolation` — verify `DataIntegrityViolationException` is caught

**Step 2: Write DTOs, Repository, Service, Controller** (same pattern as Task 1)

`TagService.create()` must include both the `existsByTagName` pre-check and the `DataIntegrityViolationException` catch (see Cross-Cutting Conventions).

**Step 3: Write integration test** (`extends BaseIntegrationTest`, `@Transactional`) verifying:
- GET /api/v1/tags is public
- POST /api/v1/tags requires ADMIN role
- Duplicate tag name returns 400

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add Tag list and admin-only create"
```

---

### Task 3: Post DTOs and PostRepository

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/dto/CreatePostRequest.java`
- Create: `backend/src/main/java/com/blogplatform/post/dto/UpdatePostRequest.java`
- Create: `backend/src/main/java/com/blogplatform/post/dto/PostListResponse.java`
- Create: `backend/src/main/java/com/blogplatform/post/dto/PostDetailResponse.java`
- Create: `backend/src/main/java/com/blogplatform/post/PostRepository.java`
- Create: `backend/src/main/java/com/blogplatform/post/PostUpdateLogRepository.java`
- Create: `backend/src/main/java/com/blogplatform/post/ReadPostRepository.java`
- Create: `backend/src/main/java/com/blogplatform/post/SavedPostRepository.java`

**Step 1: Write DTOs**

`CreatePostRequest`:
```java
package com.blogplatform.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CreatePostRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 255)
        String title,

        @NotBlank(message = "Content is required")
        @Size(max = 100000, message = "Content must be at most 100,000 characters")
        String content,

        Long categoryId,
        Set<Long> tagIds,
        boolean isPremium
) {}
```

`UpdatePostRequest`: Same fields as `CreatePostRequest` with all fields required (`@NotBlank` on title and content). This is a full-replacement PUT — all fields must be provided.

```java
package com.blogplatform.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record UpdatePostRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 255)
        String title,

        @NotBlank(message = "Content is required")
        @Size(max = 100000, message = "Content must be at most 100,000 characters")
        String content,

        Long categoryId,
        Set<Long> tagIds,
        boolean isPremium
) {}
```

`PostListResponse`:
```java
package com.blogplatform.post.dto;

import java.time.Instant;
import java.util.Set;

public record PostListResponse(
        Long postId,
        String title,
        String authorName,
        String categoryName,
        Set<String> tags,
        long likeCount,
        long commentCount,
        boolean isPremium,
        Instant createdAt
) {}
```

`PostDetailResponse`: Full post with content, author info, tags, like count, whether current user has liked/read/saved.

**Step 2: Write repositories**

`PostRepository` with custom queries:
- `findAllWithCounts(Pageable)` — JPQL with correlated COUNT subqueries (NOT `JOIN + GROUP BY` — that causes the fan-out problem with inflated counts when joining both likes and comments):
```java
@Query("SELECT new com.blogplatform.post.dto.PostListResponse(" +
       "p.id, p.title, p.author.username, p.category.categoryName, " +
       "(SELECT COUNT(l) FROM Like l WHERE l.post = p), " +
       "(SELECT COUNT(c) FROM Comment c WHERE c.post = p AND c.deleted = false), " +
       "p.premium, p.createdAt) " +
       "FROM BlogPost p WHERE p.deleted = false")
Page<PostListResponse> findAllWithCounts(Pageable pageable);
```
  > **Note on tags:** Tags cannot be projected in a JPQL constructor expression as a `Set<String>`. Fetch post IDs from the page result, then batch-load tags via `findTagsByPostIdIn(Set<Long> postIds)` and merge in the service layer.

- `findByAuthorId(Long, Pageable)`
- `countByCategoryId(Long)`
- `findMostLikedByCategory(Long)` — custom `@Query` with JOIN, COUNT, ORDER BY DESC, LIMIT 1
- Full-text search with pagination support:
```java
@Query(value = "SELECT * FROM blog_post WHERE search_vector @@ plainto_tsquery('english', :query) " +
               "AND is_deleted = false ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC",
       countQuery = "SELECT COUNT(*) FROM blog_post WHERE search_vector @@ plainto_tsquery('english', :query) " +
                    "AND is_deleted = false",
       nativeQuery = true)
Page<BlogPost> searchByText(@Param("query") String query, Pageable pageable);
```

`ReadPostRepository`:
- `existsByAccountIdAndPostId(Long, Long)`
- `markAsRead(Long accountId, Long postId)` — native upsert query:
```java
@Modifying
@Query(value = "INSERT INTO read_post (account_id, post_id, read_at) " +
               "VALUES (:accountId, :postId, NOW()) ON CONFLICT DO NOTHING",
       nativeQuery = true)
void markAsRead(@Param("accountId") Long accountId, @Param("postId") Long postId);
```

`SavedPostRepository`: `findByAccountId(Long, Pageable)`

**Step 3: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`

**Step 4: Commit**

```bash
git commit -m "feat: add Post DTOs and repositories with custom queries"
```

---

### Task 4: PostService — Create with Notification Event and Audit Logging

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/NewPostEvent.java`
- Create: `backend/src/main/java/com/blogplatform/post/PostService.java`
- Create: `backend/src/test/java/com/blogplatform/post/PostServiceTest.java`
- Create: `backend/src/main/resources/db/migration/V3__add_audit_updated_by.sql` — adds `updated_by BIGINT REFERENCES user_account(account_id)`, `ip_address VARCHAR(45)`, and `user_agent VARCHAR(500)` to `post_update_log` table (IP/user-agent for forensic audit trail; subject to 90-day retention policy — cleanup job in Phase 2B)
- Create: `backend/src/main/java/com/blogplatform/common/security/MarkdownSanitizer.java`

**Step 1: Write failing unit tests**

Key tests:
- `createPost_savesPostAndPublishesEvent` — verify `ApplicationEventPublisher.publishEvent(NewPostEvent)` called
- `createPost_savesAuditLog` — verify `PostUpdateLog` created with old values null, new values from post, `updatedBy` set to current user
- `createPost_withCategoryAndTags_setsRelationships`
- `updatePost_capturesOldValuesInLog` — verify PostUpdateLog created with old title/content and `updatedBy`
- `deletePost_setsSoftDeleteFlag` — `is_deleted = true`, post still exists
- `getPost_whenDeleted_throwsNotFound` (with filter enabled)
- `getPost_premiumPost_nonVipUser_throwsForbidden`
- `getPost_premiumPost_asAuthor_allowed` — post author can view their own premium post
- `getPost_premiumPost_asAdmin_allowed` — ADMIN role bypasses premium check
- `getPost_premiumPost_anonymous_throwsForbidden` — unauthenticated user blocked from premium post
- `getPost_marksAsRead` — verify `readPostRepository.markAsRead()` called
- `getPost_alreadyRead_noError` — idempotent, no exception on re-view
- `getPost_anonymous_skipsReadTracking` — verify `markAsRead()` NOT called when authentication is null

**Step 2: Run test to verify it fails**

**Step 3: Write NewPostEvent and PostService**

`NewPostEvent`:
```java
package com.blogplatform.post;

public record NewPostEvent(Long postId, String title, String authorName) {}
```

`PostService` key logic (requires injected `EntityManager` for filter activation — see Cross-Cutting Conventions):
- `createPost()`: saves post, writes `PostUpdateLog` with old values null (creation audit) and `updatedBy` set to current user, publishes `NewPostEvent` via `ApplicationEventPublisher`
- `updatePost()`: loads existing post, captures old title/content, applies changes, writes `PostUpdateLog` with old+new values and `updatedBy` set to current user
- `deletePost()`: sets `is_deleted = true`
- `getPost(Long postId, Authentication auth)`: calls `enableDeletedFilter()`, checks `is_deleted`, checks premium access (VIP, post author, and ADMIN are all exempt; anonymous users get 403 on premium posts). If `auth` is non-null and authenticated (not `AnonymousAuthenticationToken`), calls `readPostRepository.markAsRead(userId, postId)` (idempotent native upsert). If `auth` is null/anonymous, skips read tracking.
- `listPosts()`: calls `enableDeletedFilter()` to exclude deleted posts, supports pagination, category/tag/author filtering, full-text search

> **Note on MarkdownSanitizer:** Create `MarkdownSanitizer` as a `@Component` wrapping commonmark-java (Markdown → HTML) + OWASP Java HTML Sanitizer (HTML → safe HTML). Single method: `String sanitize(String rawMarkdown)`. Use an allowlist policy permitting: `<p>`, `<h1>`–`<h6>`, `<ul>`, `<ol>`, `<li>`, `<a href>` (http/https only), `<img src alt>`, `<code>`, `<pre>`, `<blockquote>`, `<em>`, `<strong>`, `<br>`, `<hr>`, `<table>`, `<thead>`, `<tbody>`, `<tr>`, `<th>`, `<td>`. All other tags/attributes are stripped. `PostDetailResponse` and `CommentResponse` include both `contentRaw` and `contentHtml` fields. Add unit tests: verify script tags stripped, javascript: URIs stripped, legitimate Markdown rendered correctly.

> **Note on audit logging:** All audit logging (both create and update) is handled in PostService. There is no `@PostPersist` entity listener — this avoids the static injection anti-pattern and keeps audit responsibility in one place. Audit log entries include `ip_address` and `user_agent` populated from `HttpServletRequest` via `RequestContextHolder.getRequestAttributes()`. These fields support incident forensics (e.g., distinguishing compromised account activity). IP/user-agent data is subject to a 90-day retention policy (nulled out by a scheduled cleanup job in Phase 2B).

> **Note on premium access:** The premium check in `getPost()` exempts three cases: (1) the post's own author, (2) users with ADMIN role, (3) users with VIP status. All others receive 403 Forbidden. List endpoints (`listPosts()`) include premium posts in results with the `isPremium` flag set — the `PostListResponse` does not contain full content, so no content is leaked.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add PostService with CRUD, soft delete, audit logging, read tracking, premium access"
```

---

### Task 5: PostController — Full CRUD + Save/Unsave

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/PostController.java`
- Create: `backend/src/test/java/com/blogplatform/post/PostControllerIT.java`

**Step 1: Write integration tests**

Key tests:
- `GET /api/v1/posts` — public, returns paginated list with seed data
- `GET /api/v1/posts?category=1` — filter by category
- `GET /api/v1/posts?search=keyword` — full-text search (search param validated with `@Size(max = 200)`)
- `POST /api/v1/posts` — AUTHOR role creates post, returns 201
- `POST /api/v1/posts` — USER role returns 403
- `PUT /api/v1/posts/{id}` — owner updates, verify PostUpdateLog written
- `PUT /api/v1/posts/{id}` — non-owner returns 403 (IDOR prevention)
- `DELETE /api/v1/posts/{id}` — owner soft-deletes, post gone from listings
- `DELETE /api/v1/posts/{id}` — non-owner returns 403 (IDOR prevention)
- `DELETE /api/v1/posts/{id}` — ADMIN can delete any post
- `POST /api/v1/posts/{id}/save` — authenticated bookmarks post
- `DELETE /api/v1/posts/{id}/save` — remove bookmark
- `GET /api/v1/posts?page=0&size=10` — verify pagination metadata

**Step 2: Implement PostController**

Endpoints per design doc Section 5:
- `GET /api/v1/posts` — public, paginated, filterable. Search query parameter: `@RequestParam @Size(max = 200) String search`. Add code comment on `searchByText` repository method documenting that `plainto_tsquery` is intentional for safety (strips tsquery operators, preventing injection).
- `GET /api/v1/posts/{id}` — public (premium → VIP/author/admin only), marks as read
- `POST /api/v1/posts` — `@PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")`
- `PUT /api/v1/posts/{id}` — load post via service, call `ownershipVerifier.verify(post.getAuthor().getAccountId(), authentication)`, then proceed with update
- `DELETE /api/v1/posts/{id}` — load post via service, call `ownershipVerifier.verify(post.getAuthor().getAccountId(), authentication)`, then proceed with soft delete
- `POST /api/v1/posts/{id}/save` — authenticated
- `DELETE /api/v1/posts/{id}/save` — authenticated

Returns `ApiResponse<PostDetailResponse>` or `ApiResponse<PagedResponse<PostListResponse>>`.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add PostController with CRUD, filtering, search, save/unsave"
```

---

### Task 6: Comment System — Service with Threading and Nesting Depth

**Files:**
- Create: `backend/src/main/java/com/blogplatform/comment/CommentRepository.java`
- Create: `backend/src/main/java/com/blogplatform/comment/CommentService.java`
- Create: `backend/src/main/java/com/blogplatform/comment/dto/CreateCommentRequest.java`
- Create: `backend/src/main/java/com/blogplatform/comment/dto/CommentResponse.java`
- Create: `backend/src/test/java/com/blogplatform/comment/CommentServiceTest.java`

**Step 1: Write failing unit tests — SP_Add_Comment business rules**

These tests validate the stored procedure migration (see design doc Section 6, SP validation checklist):

```java
@Test
void addComment_userHasNotReadPost_throwsForbidden() {
    // ReadPost does not exist for (userId, postId)
    when(readPostRepository.existsByAccountIdAndPostId(1L, 1L)).thenReturn(false);
    assertThatThrownBy(() -> commentService.addComment(1L, 1L, request))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("must read");
}

@Test
void addComment_contentOver250Chars_throwsBadRequest() {
    // @Size(max=250) on CreateCommentRequest handles this via validation
}

@Test
void addComment_parentCommentOnDifferentPost_throwsBadRequest() {
    // parent_comment_id belongs to a different post
}

@Test
void addComment_atDepth4_reparentsToDepth3() {
    // Depth is 1-indexed: root = depth 1, reply to root = depth 2, reply to depth-2 = depth 3 (max).
    //
    // Example: A(depth 1) → B(depth 2) → C(depth 3). User replies to C.
    // Without re-parenting, new comment D would be at depth 4 (exceeds max of 3).
    // Algorithm: walk up from C. C's parent is B (depth 2). Re-parent D to B.
    // Result: D is at depth 3, a sibling of C under B.
    //
    // Verify: saved comment's parentCommentId == B's commentId
}

@Test
void addComment_atDepth3_noReparenting() {
    // A(depth 1) → B(depth 2). User replies to B.
    // New comment C would be at depth 3 (within max). No re-parenting needed.
    // Verify: saved comment's parentCommentId == B's commentId (unchanged)
}

@Test
void addComment_validReply_createsThreadedComment() {
    // Normal reply to existing comment on same post
}
```

**Step 2: Implement CommentService**

Key logic in `addComment()`:
1. Check post exists and is not deleted
2. Check `ReadPost` exists for (userId, postId) → 403 if not
3. Validate content ≤ 250 chars (Bean Validation)
4. If `parentCommentId` provided: verify parent exists and belongs to same post
5. Enforce max nesting depth of 3 using this algorithm:
   - **Depth definition:** root comment = depth 1, each reply increments depth by 1
   - **Parent loading:** Load the intended parent comment with its ancestor chain in a single query to avoid N+1 lazy loads:
     ```java
     @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.parentComment p " +
            "LEFT JOIN FETCH p.parentComment WHERE c.id = :id")
     Optional<Comment> findByIdWithParentChain(@Param("id") Long id);
     ```
     Add this method to `CommentRepository`.
   - **Depth calculation:** Walk up the in-memory parent chain from the intended parent, counting hops + 1 to get parent's depth. New comment would be at parent's depth + 1.
   - **Re-parenting rule:** If new comment's depth > 3, walk up from the intended parent until finding an ancestor at depth 2. Set that ancestor as the new parent. New comment is now at depth 3.
6. Save Comment

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add CommentService with threading, read-before-comment, depth limit"
```

---

### Task 7: CommentController + Integration Tests

**Files:**
- Create: `backend/src/main/java/com/blogplatform/comment/CommentController.java`
- Create: `backend/src/test/java/com/blogplatform/comment/CommentControllerIT.java`
- Create: `backend/src/main/resources/db/migration/V4__add_comment_is_deleted.sql` — adds `is_deleted BOOLEAN NOT NULL DEFAULT FALSE` to `comment` table

**Step 1: Write integration tests**

- `GET /api/v1/posts/{id}/comments` — public, returns threaded structure, **paginated by top-level comments** (default page size 20). **Must verify post exists and is not soft-deleted** before returning comments (prevents information disclosure for deleted posts — return 404 if post is deleted).
- `POST /api/v1/posts/{id}/comments` — authenticated, must have read post
- `POST comment without reading post` — 403
- `DELETE /api/v1/comments/{id}` — owner soft-deletes (content blanked, `is_deleted = true`, row preserved for thread structure)
- `DELETE /api/v1/comments/{id}` — non-owner returns 403
- `DELETE /api/v1/comments/{id}` — admin can soft-delete any comment
- `GET comments after delete` — deleted comment with replies shows as `"[deleted]"` placeholder; deleted comment without replies is hidden from response
- `GET /api/v1/posts/{id}/comments` where post is soft-deleted — returns 404 (prevents information disclosure)
- Full thread flow: create post → read it → comment → reply → verify nesting

**Step 2: Implement CommentController**

`CommentResponse` is recursive. Deleted comments with replies render as placeholders:
```java
public record CommentResponse(
        Long commentId,
        String content,
        String username,
        Instant createdAt,
        boolean deleted,
        List<CommentResponse> replies
) {
    public static CommentResponse deletedPlaceholder(Long commentId, Instant createdAt, List<CommentResponse> replies) {
        return new CommentResponse(commentId, "[deleted]", "[deleted]", createdAt, true, replies);
    }
}
```

**Comment tree building algorithm** (single query + in-memory assembly):
1. Fetch paginated top-level comments: `findByPostIdAndParentCommentIdIsNull(postId, pageable)` — returns `Page<Comment>` of top-level comments
2. Collect the IDs of the returned top-level comments
3. Fetch all descendants in one query: `findByPostIdAndParentCommentIdIn(postId, topLevelIds)` — this gets depth-2 comments. Repeat for depth-3 using the depth-2 IDs. (Bounded by max depth 3, so exactly 2 additional queries max.)
4. Build tree in-memory: group comments by `parentCommentId` into a `Map<Long, List<Comment>>`, then recursively attach replies to each `CommentResponse`

This ensures O(n) in-memory work with 2-3 DB queries total (bounded, not N+1).

**Ownership verification for delete:** Load comment, call `ownershipVerifier.verify(comment.getAccount().getAccountId(), authentication)` before soft-deleting.

> **Note on delete behavior:** Comment deletion is a soft delete — set `is_deleted = true` and replace `content` with an empty string (or null). The row is preserved to maintain thread structure. When building the comment tree for display: (1) deleted comments that have replies render as `"[deleted]"` placeholders (showing `commentId`, `createdAt`, `replies`, but content = `"[deleted]"` and username = `"[deleted]"`), (2) deleted comments with no replies are omitted entirely from the response. This requires a Flyway migration (`V4__add_comment_is_deleted.sql`) to add `is_deleted BOOLEAN NOT NULL DEFAULT FALSE` to the `comment` table.

> **Note on schema:** The `parent_comment_id` FK in the schema does NOT have `ON DELETE CASCADE` (it uses the default RESTRICT). The soft-delete approach avoids this constraint entirely — rows are never removed.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add CommentController with threaded comments, pagination, ownership checks"
```

---

### Task 8: Like/Unlike

**Files:**
- Create: `backend/src/main/java/com/blogplatform/like/LikeRepository.java`
- Create: `backend/src/main/java/com/blogplatform/like/LikeService.java`
- Create: `backend/src/main/java/com/blogplatform/like/LikeController.java`
- Create: `backend/src/test/java/com/blogplatform/like/LikeServiceTest.java`
- Create: `backend/src/test/java/com/blogplatform/like/LikeControllerIT.java`

**Step 1: Write failing unit tests**

- `like_newLike_savesSuccessfully`
- `like_duplicateLike_idempotent` — no error if already liked
- `unlike_existingLike_deletes`
- `unlike_nonExistentLike_noError`

**Step 2: Implement LikeRepository, LikeService, LikeController**

`LikeRepository`:
- `countByPostId(Long)`
- `existsByAccountIdAndPostId(Long, Long)`
- `findByAccountIdAndPostId(Long, Long)` → Optional
- Idempotent like via native upsert (matching the ReadPost pattern):
```java
@Modifying
@Query(value = "INSERT INTO post_like (account_id, post_id, created_at) " +
               "VALUES (:accountId, :postId, NOW()) ON CONFLICT (account_id, post_id) DO NOTHING",
       nativeQuery = true)
void likePost(@Param("accountId") Long accountId, @Param("postId") Long postId);
```

`LikeController`:
- `POST /api/v1/posts/{postId}/likes` — authenticated, idempotent
- `DELETE /api/v1/posts/{postId}/likes` — authenticated

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Like/unlike with idempotent toggle"
```

---

### Task 9: Author Profiles

**Files:**
- Create: `backend/src/main/java/com/blogplatform/author/AuthorRepository.java`
- Create: `backend/src/main/java/com/blogplatform/author/AuthorService.java`
- Create: `backend/src/main/java/com/blogplatform/author/AuthorController.java`
- Create: `backend/src/main/java/com/blogplatform/author/dto/AuthorResponse.java`
- Create: `backend/src/test/java/com/blogplatform/author/AuthorControllerIT.java`

**Step 1: Write integration tests**

- `GET /api/v1/authors` — public, returns list with post counts
- `GET /api/v1/authors/{id}` — public, returns author profile + their posts (paginated)

**Step 2: Implement**

`AuthorRepository`:
- `findAuthorsWithMinPosts(int minPosts)` — `@Query`
- Standard JPA queries

`AuthorResponse` includes: authorId, displayName, biography, expertise, socialLinks (Map), postCount.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Author profile listing with post counts"
```


---

> **Continue to Phase 2B for Tasks 10-22.**

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-03-01 | Initial plan with Tasks 1-10 |
| 2.0 | 2026-03-15 | Revised per critical review (`2026-03-01-phase2a-content-crud-implementation-critical-review-1.md`). Changes: (1) Removed Task 5 (PostEntityListener) — creation audit logging moved into Task 4 PostService to avoid static injection anti-pattern and keep audit in one place. Tasks renumbered 1-9. (2) Added `ReadPostRepository.markAsRead()` native upsert (`INSERT ... ON CONFLICT DO NOTHING`) in Task 3 to prevent duplicate insert errors on re-view. (3) Added `DataIntegrityViolationException` catch on all uniqueness-checked operations (Tasks 1, 2) as TOCTOU race guard. (4) Added `existsByCategoryNameAndCategoryIdNot` duplicate name check on Category update (Task 1). (5) Specified single-query + in-memory tree assembly for comment threading (Task 7) to prevent N+1 queries. (6) Added top-level comment pagination to `GET /api/v1/posts/{id}/comments` (Task 7). (7) Replaced ambiguous re-parenting prose with explicit depth algorithm and concrete A→B→C→D example (Task 6). (8) Added explicit `ownershipVerifier.verify()` calls for post update/delete (Task 5) and comment delete (Task 7) with IDOR prevention tests. (9) Added premium access exemptions for post author and ADMIN role (Task 4). (10) Added Cross-Cutting Conventions section: `@Slf4j` logging, `@Transactional(readOnly=true)`, extend `BaseIntegrationTest`, `@Transactional` on IT classes, `DataIntegrityViolationException` catch pattern. (11) Added explicit Tag unit tests (Task 2). (12) Documented PUT = full-replacement semantics for Category and Post updates. Phase 2B task numbers shifted (now Tasks 10-22). |
| 3.0 | 2026-03-15 | Revised per critical review v2 (`2026-03-01-phase2a-content-crud-implementation-critical-review-2.md`). Changes: (1) Changed comment deletion from hard delete to soft delete with "[deleted]" placeholder — preserves thread structure and other users' replies. Added Flyway migration `V4__add_comment_is_deleted.sql`. Corrected false claim that schema has `ON DELETE CASCADE` on `parent_comment_id` (it uses default RESTRICT). (2) Added `DataIntegrityViolationException` catch on `CategoryService.delete()` to handle FK violation when category has referencing posts — returns 400 with clear message. (3) Added Hibernate `deletedFilter` activation pattern to cross-cutting conventions with `enableDeletedFilter()` helper method using injected `EntityManager`. Specified which `PostService` methods enable the filter and which don't (admin restore). (4) Added `findByIdWithParentChain` JOIN FETCH query to `CommentRepository` — loads full parent chain (max depth 3) in one query, preventing N+1 lazy loads during depth calculation. (5) Made `getPost()` authentication-aware: accepts nullable `Authentication`, guards `markAsRead()` and premium checks against null/anonymous users. Added tests for anonymous access. Added convention for all auth-aware public endpoints. (6) Added Markdown content contract to cross-cutting conventions — content is raw Markdown, frontends must use safe renderer, no server-side HTML sanitization. (7) Added `updated_by` column to `post_update_log` via Flyway migration `V3__add_audit_updated_by.sql` for audit trail actor attribution. (8) Added `CategoryResponse.from()` static factory method and DTO mapping convention — all response DTOs provide `from()` factory. Updated controller to use `CategoryResponse::from`. (9) Added explicit JPQL for `PostRepository.findAllWithCounts()` using correlated COUNT subqueries (not JOIN+GROUP BY) to avoid fan-out problem. Documented separate tag batch-loading strategy. (10) Added `countQuery` and `Pageable` to full-text search native query for proper pagination support. (11) Added idempotent `LikeRepository.likePost()` native upsert (`INSERT ... ON CONFLICT DO NOTHING`), matching ReadPost pattern. (12) Added missing integration tests for category update: `updateCategory_asAdmin_returns200`, `_duplicateName_returns400`, `_asUser_returns403`. (13) Annotated `findByTagNameIn` as reserved for future use. (14) Added note deferring per-endpoint rate limiting to future phase. |
| 4.0 | 2026-03-15 | Revised per security audit (`2026-03-15-phase2a-content-crud-implementation-security-audit-1.md`). Changes: (1) **[High] Server-side Markdown sanitization:** Replaced client-side-only XSS defense with server-side render + sanitize pipeline. Added commonmark-java + OWASP Java HTML Sanitizer. Created `MarkdownSanitizer` component. Content responses now include both `contentRaw` and `contentHtml` fields. Updated content contract convention. (2) **[Medium] Full-text search input constraints:** Added `@Size(max = 200)` validation on search query parameter in PostController. Added code comment documenting `plainto_tsquery` is intentional for safety. (3) **[Medium] Comments endpoint deleted-post check:** `GET /api/v1/posts/{id}/comments` now verifies post is not soft-deleted before returning comments — prevents information disclosure for deleted posts. Added integration test. (4) **[Medium] Per-endpoint rate limiting:** Moved from deferred to Phase 2A. Added comment creation (10 req/min) and like/unlike (30 req/min) per-user limits as additional tiers in existing `RateLimitFilter`. (5) **[Low] Audit log forensic fields:** Added `ip_address VARCHAR(45)` and `user_agent VARCHAR(500)` to `post_update_log` migration (`V3`). Populated from `HttpServletRequest` via `RequestContextHolder`. Subject to 90-day retention policy (cleanup job in Phase 2B). |
| 5.0 | 2026-03-15 | Cross-plan consistency review. Fixed task total from 22 to 21 (Task 10 removed in 2B v2.0). Updated Phase 2B cross-reference to "Tasks 11–22" with note about Task 10 removal. |
