# Phase 2: Core Features (Back-End API) — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build out the complete REST API — post CRUD, comments, likes, categories, tags, saved posts, authors, subscriptions, notifications, password reset, email verification, image upload — with full test coverage validating all stored procedure business rules.

**Architecture:** Builds on Phase 1 foundation. Each feature follows the package-by-feature layout: Entity → Repository → Service → Controller → DTOs. All endpoints under `/api/v1/`. TDD throughout.

**Tech Stack:** Same as Phase 1. Additionally: Apache Tika (image validation), `@Async` + `@TransactionalEventListener` (notifications), `@Scheduled` (cleanup jobs).

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Sections 5 (API Endpoints), 6 (Business Logic Migration), and 8 (Testing Strategy).

**Prerequisite:** Phase 1 must be complete (all 15 tasks).

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

Create `backend/src/main/java/com/blogplatform/category/dto/CategoryResponse.java`:
```java
package com.blogplatform.category.dto;

public record CategoryResponse(Long categoryId, String categoryName, String description) {}
```

Create `backend/src/main/java/com/blogplatform/category/CategoryRepository.java`:
```java
package com.blogplatform.category;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    boolean existsByCategoryName(String categoryName);
}
```

Create `backend/src/main/java/com/blogplatform/category/CategoryService.java`:
```java
package com.blogplatform.category;

import com.blogplatform.category.dto.CreateCategoryRequest;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

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
        return categoryRepository.save(category);
    }

    @Transactional
    public Category update(Long id, CreateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        category.setCategoryName(request.categoryName());
        category.setDescription(request.description());
        return categoryRepository.save(category);
    }

    @Transactional
    public void delete(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category not found");
        }
        categoryRepository.deleteById(id);
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
                .map(c -> new CategoryResponse(c.getCategoryId(), c.getCategoryName(), c.getDescription()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest request) {
        Category cat = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(new CategoryResponse(cat.getCategoryId(), cat.getCategoryName(), cat.getDescription())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(@PathVariable Long id, @Valid @RequestBody CreateCategoryRequest request) {
        Category cat = categoryService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(new CategoryResponse(cat.getCategoryId(), cat.getCategoryName(), cat.getDescription())));
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

import com.blogplatform.category.dto.CreateCategoryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CategoryControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.session.store-type", () -> "none");
    }

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
- Create: `backend/src/test/java/com/blogplatform/tag/TagControllerIT.java`

Follows identical pattern to Category. Key differences:
- `TagRepository` needs `findByTagNameIn(Set<String> names)` for bulk lookup
- Only GET (public) and POST (admin) — no PUT/DELETE per design doc
- Tag entity has `tag_id` and `tag_name` (unique)

**Step 1: Write DTOs, Repository, Service, Controller** (same pattern as Task 1)

**Step 2: Write integration test** verifying:
- GET /api/v1/tags is public
- POST /api/v1/tags requires ADMIN role
- Duplicate tag name returns 400

**Step 3: Run tests, verify pass**

**Step 4: Commit**

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

`UpdatePostRequest`: Same fields, all optional (no `@NotBlank`).

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
- `findAllWithAuthorAndCounts(Pageable)` — JPQL with COUNT subqueries for likes and comments
- `findByAuthorId(Long, Pageable)`
- `countByCategoryId(Long)`
- `findMostLikedByCategory(Long)` — custom `@Query` with JOIN, COUNT, ORDER BY DESC, LIMIT 1
- Full-text search: `@Query(value = "SELECT * FROM blog_post WHERE search_vector @@ plainto_tsquery('english', :query) AND is_deleted = false ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC", nativeQuery = true)`

`ReadPostRepository`: `existsByAccountIdAndPostId(Long, Long)`

`SavedPostRepository`: `findByAccountId(Long, Pageable)`

**Step 3: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`

**Step 4: Commit**

```bash
git commit -m "feat: add Post DTOs and repositories with custom queries"
```

---

### Task 4: PostService — Create with Notification Event

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/NewPostEvent.java`
- Create: `backend/src/main/java/com/blogplatform/post/PostService.java`
- Create: `backend/src/test/java/com/blogplatform/post/PostServiceTest.java`

**Step 1: Write failing unit tests**

Key tests:
- `createPost_savesPostAndPublishesEvent` — verify `ApplicationEventPublisher.publishEvent(NewPostEvent)` called
- `createPost_withCategoryAndTags_setsRelationships`
- `updatePost_capturesOldValuesInLog` — verify PostUpdateLog created with old title/content
- `deletePost_setsSoftDeleteFlag` — `is_deleted = true`, post still exists
- `getPost_whenDeleted_throwsNotFound` (with filter enabled)
- `getPost_premiumPost_nonVipUser_throwsForbidden`
- `getPost_marksAsRead` — verify ReadPost created

**Step 2: Run test to verify it fails**

**Step 3: Write NewPostEvent and PostService**

`NewPostEvent`:
```java
package com.blogplatform.post;

public record NewPostEvent(Long postId, String title, String authorName) {}
```

`PostService` key logic:
- `createPost()`: saves post, publishes `NewPostEvent` via `ApplicationEventPublisher`
- `updatePost()`: loads existing post, captures old title/content, applies changes, writes `PostUpdateLog` with old+new values
- `deletePost()`: sets `is_deleted = true`
- `getPost()`: checks `is_deleted`, checks premium access (VIP only), creates `ReadPost` entry
- `listPosts()`: uses Hibernate `@Filter("activePostsFilter")` to exclude deleted posts, supports pagination, category/tag/author filtering, full-text search

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: add PostService with CRUD, soft delete, read tracking, premium access"
```

---

### Task 5: PostEntityListener — Audit Logging on Create

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/PostEntityListener.java`
- Create: `backend/src/test/java/com/blogplatform/post/PostEntityListenerTest.java`

**Step 1: Write failing test**

Test that `@PostPersist` creates a `PostUpdateLog` entry with the new post's title and content (old values null).

**Step 2: Implement PostEntityListener**

```java
package com.blogplatform.post;

import jakarta.persistence.PostPersist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PostEntityListener {

    private static PostUpdateLogRepository logRepository;

    @Autowired
    public void setLogRepository(PostUpdateLogRepository logRepository) {
        PostEntityListener.logRepository = logRepository;
    }

    @PostPersist
    public void postPersist(BlogPost post) {
        PostUpdateLog log = new PostUpdateLog();
        log.setPostId(post.getPostId());
        log.setOldTitle(null);
        log.setNewTitle(post.getTitle());
        log.setOldContent(null);
        log.setNewContent(post.getContent());
        logRepository.save(log);
    }
}
```

Note: Add `@EntityListeners(PostEntityListener.class)` to `BlogPost.java`.

**Step 3: Run test, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add PostEntityListener for audit logging on post creation"
```

---

### Task 6: PostController — Full CRUD + Save/Unsave

**Files:**
- Create: `backend/src/main/java/com/blogplatform/post/PostController.java`
- Create: `backend/src/test/java/com/blogplatform/post/PostControllerIT.java`

**Step 1: Write integration tests**

Key tests:
- `GET /api/v1/posts` — public, returns paginated list with seed data
- `GET /api/v1/posts?category=1` — filter by category
- `GET /api/v1/posts?search=keyword` — full-text search
- `POST /api/v1/posts` — AUTHOR role creates post, returns 201
- `POST /api/v1/posts` — USER role returns 403
- `PUT /api/v1/posts/{id}` — owner updates, verify PostUpdateLog written
- `DELETE /api/v1/posts/{id}` — owner soft-deletes, post gone from listings
- `POST /api/v1/posts/{id}/save` — authenticated bookmarks post
- `DELETE /api/v1/posts/{id}/save` — remove bookmark
- `GET /api/v1/posts?page=0&size=10` — verify pagination metadata

**Step 2: Implement PostController**

Endpoints per design doc Section 5:
- `GET /api/v1/posts` — public, paginated, filterable
- `GET /api/v1/posts/{id}` — public (premium → VIP only), marks as read
- `POST /api/v1/posts` — `@PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")`
- `PUT /api/v1/posts/{id}` — owner or admin (use `@ownershipVerifier`)
- `DELETE /api/v1/posts/{id}` — owner or admin
- `POST /api/v1/posts/{id}/save` — authenticated
- `DELETE /api/v1/posts/{id}/save` — authenticated

Returns `ApiResponse<PostDetailResponse>` or `ApiResponse<PagedResponse<PostListResponse>>`.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add PostController with CRUD, filtering, search, save/unsave"
```

---

### Task 7: Comment System — Service with Threading and Nesting Depth

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
    // Comment chain: A → B → C (depth 3). Reply to C should be re-parented to C (stays at depth 3)
    // Walk parent chain: C's parent is B, B's parent is A, A has no parent → depth=3
    // Since depth exceeds 3, re-parent to deepest allowed ancestor (C at depth 3? No — re-parent to B)
    // Actually per design: "walk up parent chain, if depth exceeds 3, re-parent to the deepest allowed ancestor"
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
5. Enforce max nesting depth of 3: walk up parent chain counting depth. If > 3, set parent to the deepest ancestor at depth 2 (so the new comment is at depth 3)
6. Save Comment

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add CommentService with threading, read-before-comment, depth limit"
```

---

### Task 8: CommentController + Integration Tests

**Files:**
- Create: `backend/src/main/java/com/blogplatform/comment/CommentController.java`
- Create: `backend/src/test/java/com/blogplatform/comment/CommentControllerIT.java`

**Step 1: Write integration tests**

- `GET /api/v1/posts/{id}/comments` — public, returns threaded structure
- `POST /api/v1/posts/{id}/comments` — authenticated, must have read post
- `POST comment without reading post` — 403
- `DELETE /api/v1/comments/{id}` — owner or admin
- Full thread flow: create post → read it → comment → reply → verify nesting

**Step 2: Implement CommentController**

`CommentResponse` is recursive:
```java
public record CommentResponse(
        Long commentId,
        String content,
        String username,
        Instant createdAt,
        List<CommentResponse> replies
) {}
```

Controller builds threaded response by fetching top-level comments and recursively attaching replies.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add CommentController with threaded comments"
```

---

### Task 9: Like/Unlike

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

`LikeController`:
- `POST /api/v1/posts/{postId}/likes` — authenticated, idempotent
- `DELETE /api/v1/posts/{postId}/likes` — authenticated

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Like/unlike with idempotent toggle"
```

---

### Task 10: Author Profiles

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
