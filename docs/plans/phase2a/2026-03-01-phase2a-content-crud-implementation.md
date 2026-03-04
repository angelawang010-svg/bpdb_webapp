# Phase 2A: Content & CRUD — Implementation Plan

(Part 1 of 2 — Tasks 1-10 of 23)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build out the complete REST API — post CRUD, comments, likes, categories, tags, saved posts, authors, subscriptions, notifications, password reset, email verification, image upload — with full test coverage validating all stored procedure business rules.

**Architecture:** Builds on Phase 1 foundation. Each feature follows the package-by-feature layout: Entity → Repository → Service → Controller → DTOs. All endpoints under `/api/v1/`. TDD throughout.

**Tech Stack:** Same as Phase 1. Additionally: Apache Tika (image validation), `@Async` + `@TransactionalEventListener` (notifications), `@Scheduled` (cleanup jobs).

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Sections 5 (API Endpoints), 6 (Business Logic Migration), and 8 (Testing Strategy).

**Prerequisite:** Phase 1 must be complete (all 15 tasks).

## Phase 2 Parts

- **Phase 2A** (this file): Tasks 1–10 — Category, Tag, Post CRUD, Comments, Likes, Authors
- **Phase 2B** (`2026-03-01-phase2b-platform-features-implementation.md`): Tasks 11–23 — Subscriptions, Notifications, Profiles, Password Reset, Email, Image Upload, Admin, Cleanup, OpenAPI, Integration Tests

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

> **Continue to Phase 2B for Tasks 11-23.**
