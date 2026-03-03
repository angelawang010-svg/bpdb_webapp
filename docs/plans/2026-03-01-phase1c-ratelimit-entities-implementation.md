# Phase 1C: Rate Limiting, Entities & Verification — Implementation Plan

(Part 3 of 3 — Tasks 11-15 of 15)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Spring Boot project skeleton with PostgreSQL, Redis, Flyway migrations, JPA entities, Spring Security session-based auth, rate limiting, and auth endpoints — producing a running, tested foundation for the full blog platform.

**Architecture:** Monorepo with `backend/` (Spring Boot 3.x, Java 21, Gradle) and `frontend/` (later). PostgreSQL 16 + Redis 7 via Docker Compose. Session-based auth with Redis-backed sessions. Bucket4j rate limiting. All endpoints under `/api/v1/`.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers, JUnit 5, Mockito

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — the authoritative source for all schema, API, security, and business logic decisions.

## Phase 1 Parts

- **Phase 1A: Project Setup & Infrastructure** — Tasks 1-5 (`2026-03-01-phase1a-project-setup-implementation.md`)
- **Phase 1B: Auth System** — Tasks 6-10 (`2026-03-01-phase1b-auth-security-implementation.md`)
- **Phase 1C: Rate Limiting, Entities & Verification** — Tasks 11-15 (`2026-03-01-phase1c-ratelimit-entities-implementation.md`)

> **Prerequisite:** Phase 1B (Tasks 6-10) must be complete.

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-03-01 | Initial plan |
| 1.1 | 2026-03-03 | Critical review fixes: Task 11 rewritten (Caffeine cache, IP spoofing fix, double-filter fix, URL pattern fix, accurate Retry-After, added tests), Task 12 null safety + principal type alignment, Task 13 full entity code for all 16 files, Task 15 git add fix. See `2026-03-01-phase1c-ratelimit-entities-implementation-critical-review-1.md` for review details. |

---

### Task 11: Rate Limiting Configuration — Bucket4j

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/RateLimitFilter.java`
- Create: `backend/src/main/java/com/blogplatform/config/RateLimitConfig.java`
- Create: `backend/src/test/java/com/blogplatform/config/RateLimitFilterTest.java`

**Step 1: Write the rate limit filter**

Create `backend/src/main/java/com/blogplatform/config/RateLimitFilter.java`:
```java
package com.blogplatform.config;

import com.blogplatform.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting filter with tiered limits:
 * - Auth endpoints: 10 req/min per IP
 * - Authenticated users: 120 req/min per username
 * - Anonymous: 60 req/min per IP
 *
 * NOTE: This is a per-JVM rate limiter. For multi-instance deployments,
 * replace with Bucket4j's Redis-backed ProxyManager. See:
 * https://bucket4j.com/8.0.0/toc.html#bucket4j-redis
 *
 * NOTE: IP resolution uses request.getRemoteAddr(). For production behind
 * a reverse proxy, configure server.forward-headers-strategy=NATIVE in
 * application.properties so Spring resolves the real client IP.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    private final ObjectMapper objectMapper;

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key;
        Bucket bucket;

        boolean isAuthEndpoint = request.getRequestURI().startsWith("/api/v1/auth/");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        if (isAuthEndpoint) {
            key = "auth:" + request.getRemoteAddr();
            bucket = buckets.get(key, k -> createBucket(10));
        } else if (isAuthenticated) {
            key = "user:" + auth.getName();
            bucket = buckets.get(key, k -> createBucket(120));
        } else {
            key = "anon:" + request.getRemoteAddr();
            bucket = buckets.get(key, k -> createBucket(60));
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(Math.max(1, waitSeconds)));
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error("Rate limit exceeded. Try again in " + waitSeconds + " seconds."));
        }
    }

    private Bucket createBucket(int capacityPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacityPerMinute, Duration.ofMinutes(1)))
                .build();
    }
}
```

Create `backend/src/main/java/com/blogplatform/config/RateLimitConfig.java`:
```java
package com.blogplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(ObjectMapper objectMapper) {
        return new RateLimitFilter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
```

**Step 2: Write rate limiting tests**

Create `backend/src/test/java/com/blogplatform/config/RateLimitFilterTest.java`:
```java
package com.blogplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new RateLimitFilter(objectMapper);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldNotFilter_nonApiPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldFilter_apiPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void anonymousUser_returns429_afterLimitExhausted() throws Exception {
        // Anonymous limit is 60/min
        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
            request.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        // 61st request should be 429
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
        request.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void authEndpoint_hasStricterLimit() throws Exception {
        // Auth limit is 10/min
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("5.6.7.8");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        // 11th request should be 429
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("5.6.7.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void authenticatedUser_usesUsernameKey() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                "testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Authenticated limit is 120/min — just verify first request works and has header
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Rate-Limit-Remaining")).isNotNull();
    }

    @Test
    void differentIps_haveSeparateBuckets() throws Exception {
        // Exhaust limit for IP 1
        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
            request.setRemoteAddr("10.0.0.1");
            filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        // IP 2 should still work
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts");
        request.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
```

**Step 3: Verify it compiles**

Run: `cd backend && ./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

**Step 4: Run tests**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.config.RateLimitFilterTest" -i`
Expected: All 5 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/config/RateLimitFilter.java backend/src/main/java/com/blogplatform/config/RateLimitConfig.java backend/src/test/java/com/blogplatform/config/RateLimitFilterTest.java
git commit -m "feat: add Bucket4j rate limiting — tiered by user type with Caffeine cache"
```

---

### Task 12: OwnershipVerifier Service (IDOR Prevention)

**Files:**
- Create: `backend/src/main/java/com/blogplatform/common/security/OwnershipVerifier.java`
- Create: `backend/src/test/java/com/blogplatform/common/security/OwnershipVerifierTest.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/blogplatform/common/security/OwnershipVerifierTest.java`:
```java
package com.blogplatform.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnershipVerifierTest {

    private OwnershipVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new OwnershipVerifier();
    }

    @Test
    void isOwnerOrAdmin_whenOwner_returnsTrue() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "owneruser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin("owneruser", auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenAdmin_returnsTrue() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "adminuser", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(verifier.isOwnerOrAdmin("otheruser", auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenNeitherOwnerNorAdmin_returnsFalse() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "otheruser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin("owneruser", auth)).isFalse();
    }

    @Test
    void verify_whenNotOwnerOrAdmin_throwsAccessDenied() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "otheruser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(() -> verifier.verify("owneruser", auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void isOwnerOrAdmin_nullAuthentication_returnsFalse() {
        assertThat(verifier.isOwnerOrAdmin("owneruser", null)).isFalse();
    }

    @Test
    void verify_nullAuthentication_throwsAccessDenied() {
        assertThatThrownBy(() -> verifier.verify("owneruser", null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.common.security.OwnershipVerifierTest" -i`
Expected: FAIL — class does not exist.

**Step 3: Write the implementation**

Create `backend/src/main/java/com/blogplatform/common/security/OwnershipVerifier.java`:
```java
package com.blogplatform.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("ownershipVerifier")
public class OwnershipVerifier {

    /**
     * Checks if the authenticated user is the resource owner or an admin.
     * Uses auth.getName() which returns the username (aligned with Phase 1B's
     * CustomUserDetailsService which stores username as the principal name).
     *
     * @param resourceOwnerUsername the username of the resource owner
     * @param authentication the current authentication, may be null
     * @return true if the user is the owner or has ROLE_ADMIN
     */
    public boolean isOwnerOrAdmin(String resourceOwnerUsername, Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return false;
        }
        String currentUsername = authentication.getName();
        if (currentUsername.equals(resourceOwnerUsername)) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public void verify(String resourceOwnerUsername, Authentication authentication) {
        if (!isOwnerOrAdmin(resourceOwnerUsername, authentication)) {
            throw new AccessDeniedException("You do not have permission to access this resource");
        }
    }
}
```

> **Note:** This uses `String` username for ownership checks, aligned with Phase 1B's `CustomUserDetailsService` which stores the username as the principal name. Service methods that need ownership verification should look up the resource's owner username and pass it here.

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.common.security.OwnershipVerifierTest" -i`
Expected: All 6 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/common/security/ backend/src/test/java/com/blogplatform/common/security/
git commit -m "feat: add OwnershipVerifier for IDOR prevention"
```

---

### Task 13: Remaining JPA Entities (All Feature Entities)

**Files:**
- Create: `backend/src/main/java/com/blogplatform/category/Category.java`
- Create: `backend/src/main/java/com/blogplatform/tag/Tag.java`
- Create: `backend/src/main/java/com/blogplatform/post/BlogPost.java`
- Create: `backend/src/main/java/com/blogplatform/post/PostUpdateLog.java`
- Create: `backend/src/main/java/com/blogplatform/post/ReadPost.java`
- Create: `backend/src/main/java/com/blogplatform/post/ReadPostId.java`
- Create: `backend/src/main/java/com/blogplatform/post/SavedPost.java`
- Create: `backend/src/main/java/com/blogplatform/post/SavedPostId.java`
- Create: `backend/src/main/java/com/blogplatform/comment/Comment.java`
- Create: `backend/src/main/java/com/blogplatform/like/Like.java`
- Create: `backend/src/main/java/com/blogplatform/author/AuthorProfile.java`
- Create: `backend/src/main/java/com/blogplatform/subscription/Subscriber.java`
- Create: `backend/src/main/java/com/blogplatform/payment/Payment.java`
- Create: `backend/src/main/java/com/blogplatform/payment/PaymentMethod.java`
- Create: `backend/src/main/java/com/blogplatform/notification/Notification.java`
- Create: `backend/src/main/java/com/blogplatform/image/Image.java`

> **Convention notes:**
> - All `@ManyToOne` relationships use `fetch = FetchType.LAZY` to avoid N+1 queries (overriding Hibernate's EAGER default).
> - Only `BlogPost` extends `AuditableEntity` (has both `created_at` and `updated_at`).
> - All enum fields use `@Enumerated(EnumType.STRING)`.
> - Cascade types are only set where the design doc implies parent-child lifecycle coupling.
> - Soft delete `@FilterDef`/`@Filter` on `BlogPost` — filter wiring in `PostService` deferred to Phase 2.

**Step 1: Create all entity classes**

Create `backend/src/main/java/com/blogplatform/category/Category.java`:
```java
package com.blogplatform.category;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    @NotBlank
    @Size(max = 50)
    @Column(name = "category_name", nullable = false, unique = true, length = 50)
    private String categoryName;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    public Category() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
```

Create `backend/src/main/java/com/blogplatform/tag/Tag.java`:
```java
package com.blogplatform.tag;

import com.blogplatform.post.BlogPost;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tags")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @NotBlank
    @Size(max = 50)
    @Column(name = "tag_name", nullable = false, unique = true, length = 50)
    private String tagName;

    @ManyToMany(mappedBy = "tags")
    private Set<BlogPost> posts = new HashSet<>();

    public Tag() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public Set<BlogPost> getPosts() { return posts; }
    public void setPosts(Set<BlogPost> posts) { this.posts = posts; }
}
```

Create `backend/src/main/java/com/blogplatform/post/BlogPost.java`:
```java
package com.blogplatform.post;

import com.blogplatform.category.Category;
import com.blogplatform.common.audit.AuditableEntity;
import com.blogplatform.tag.Tag;
import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "blog_posts")
@FilterDef(name = "deletedFilter", parameters = @ParamDef(name = "isDeleted", type = Boolean.class))
@Filter(name = "deletedFilter", condition = "is_deleted = :isDeleted")
public class BlogPost extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long id;

    @NotBlank
    @Size(max = 255)
    @Column(name = "post_title", nullable = false, unique = true, length = 255)
    private String title;

    @NotBlank
    @Size(max = 100000)
    @Column(name = "post_content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private UserAccount author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToMany
    @JoinTable(
            name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @Column(name = "is_premium", nullable = false)
    private boolean premium = false;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    public BlogPost() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public UserAccount getAuthor() { return author; }
    public void setAuthor(UserAccount author) { this.author = author; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Set<Tag> getTags() { return tags; }
    public void setTags(Set<Tag> tags) { this.tags = tags; }
    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
```

Create `backend/src/main/java/com/blogplatform/post/PostUpdateLog.java`:
```java
package com.blogplatform.post;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "post_update_log")
public class PostUpdateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private BlogPost post;

    @Column(name = "old_title")
    private String oldTitle;

    @Column(name = "new_title")
    private String newTitle;

    @Column(name = "old_content", columnDefinition = "TEXT")
    private String oldContent;

    @Column(name = "new_content", columnDefinition = "TEXT")
    private String newContent;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PostUpdateLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public String getOldTitle() { return oldTitle; }
    public void setOldTitle(String oldTitle) { this.oldTitle = oldTitle; }
    public String getNewTitle() { return newTitle; }
    public void setNewTitle(String newTitle) { this.newTitle = newTitle; }
    public String getOldContent() { return oldContent; }
    public void setOldContent(String oldContent) { this.oldContent = oldContent; }
    public String getNewContent() { return newContent; }
    public void setNewContent(String newContent) { this.newContent = newContent; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

Create `backend/src/main/java/com/blogplatform/post/ReadPostId.java`:
```java
package com.blogplatform.post;

import java.io.Serializable;
import java.util.Objects;

public class ReadPostId implements Serializable {

    private Long account;
    private Long post;

    public ReadPostId() {}

    public ReadPostId(Long account, Long post) {
        this.account = account;
        this.post = post;
    }

    public Long getAccount() { return account; }
    public void setAccount(Long account) { this.account = account; }
    public Long getPost() { return post; }
    public void setPost(Long post) { this.post = post; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReadPostId that)) return false;
        return Objects.equals(account, that.account) && Objects.equals(post, that.post);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, post);
    }
}
```

Create `backend/src/main/java/com/blogplatform/post/ReadPost.java`:
```java
package com.blogplatform.post;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "read_posts")
@IdClass(ReadPostId.class)
public class ReadPost {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private UserAccount account;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private BlogPost post;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    public ReadPost() {}

    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
```

Create `backend/src/main/java/com/blogplatform/post/SavedPostId.java`:
```java
package com.blogplatform.post;

import java.io.Serializable;
import java.util.Objects;

public class SavedPostId implements Serializable {

    private Long account;
    private Long post;

    public SavedPostId() {}

    public SavedPostId(Long account, Long post) {
        this.account = account;
        this.post = post;
    }

    public Long getAccount() { return account; }
    public void setAccount(Long account) { this.account = account; }
    public Long getPost() { return post; }
    public void setPost(Long post) { this.post = post; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedPostId that)) return false;
        return Objects.equals(account, that.account) && Objects.equals(post, that.post);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, post);
    }
}
```

Create `backend/src/main/java/com/blogplatform/post/SavedPost.java`:
```java
package com.blogplatform.post;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "saved_posts", uniqueConstraints =
    @UniqueConstraint(columnNames = {"account_id", "post_id"}))
@IdClass(SavedPostId.class)
public class SavedPost {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private UserAccount account;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private BlogPost post;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    public SavedPost() {}

    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public LocalDateTime getSavedAt() { return savedAt; }
    public void setSavedAt(LocalDateTime savedAt) { this.savedAt = savedAt; }
}
```

Create `backend/src/main/java/com/blogplatform/comment/Comment.java`:
```java
package com.blogplatform.comment;

import com.blogplatform.post.BlogPost;
import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long id;

    @NotBlank
    @Size(max = 250)
    @Column(name = "comment_text", nullable = false, length = 250)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private BlogPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private Comment parentComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Comment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public Comment getParentComment() { return parentComment; }
    public void setParentComment(Comment parentComment) { this.parentComment = parentComment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

Create `backend/src/main/java/com/blogplatform/like/Like.java`:
```java
package com.blogplatform.like;

import com.blogplatform.post.BlogPost;
import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "post_like", uniqueConstraints =
    @UniqueConstraint(columnNames = {"account_id", "post_id"}))
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "like_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private BlogPost post;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Like() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

Create `backend/src/main/java/com/blogplatform/author/AuthorProfile.java`:
```java
package com.blogplatform.author;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "author_profile")
public class AuthorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "author_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private UserAccount account;

    @Size(max = 255)
    @Column(name = "biography")
    private String biography;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", columnDefinition = "jsonb")
    private Map<String, String> socialLinks;

    @NotBlank
    @Size(max = 255)
    @Column(name = "expertise", nullable = false, length = 255)
    private String expertise;

    public AuthorProfile() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public Map<String, String> getSocialLinks() { return socialLinks; }
    public void setSocialLinks(Map<String, String> socialLinks) { this.socialLinks = socialLinks; }
    public String getExpertise() { return expertise; }
    public void setExpertise(String expertise) { this.expertise = expertise; }
}
```

Create `backend/src/main/java/com/blogplatform/subscription/Subscriber.java`:
```java
package com.blogplatform.subscription;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriber")
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscriber_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private UserAccount account;

    @Column(name = "subscription_date", nullable = false)
    private LocalDateTime subscribedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "expiration_date")
    private LocalDateTime expirationDate;

    public Subscriber() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public LocalDateTime getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(LocalDateTime subscribedAt) { this.subscribedAt = subscribedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDateTime expirationDate) { this.expirationDate = expirationDate; }
}
```

Create `backend/src/main/java/com/blogplatform/payment/PaymentMethod.java`:
```java
package com.blogplatform.payment;

public enum PaymentMethod {
    CREDIT_CARD,
    PAYPAL,
    BANK_TRANSFER
}
```

Create `backend/src/main/java/com/blogplatform/payment/Payment.java`:
```java
package com.blogplatform.payment;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment entity — stub for Phase 1. Payment processing is deferred to Phase 5+.
 * Entity exists to match the Flyway schema (ddl-auto: validate).
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @NotNull
    @Positive
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 50)
    private PaymentMethod paymentMethod;

    @NotBlank
    @Size(max = 50)
    @Column(name = "transaction_id", nullable = false, unique = true, length = 50)
    private String transactionId;

    @NotBlank
    @Size(max = 255)
    @Column(name = "payment_description", nullable = false)
    private String paymentDescription;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    public Payment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getPaymentDescription() { return paymentDescription; }
    public void setPaymentDescription(String paymentDescription) { this.paymentDescription = paymentDescription; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }
}
```

Create `backend/src/main/java/com/blogplatform/notification/Notification.java`:
```java
package com.blogplatform.notification;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notification_account_read_created",
           columnList = "account_id, is_read, created_at")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @NotBlank
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Notification() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

Create `backend/src/main/java/com/blogplatform/image/Image.java`:
```java
package com.blogplatform.image;

import com.blogplatform.post.BlogPost;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Entity
@Table(name = "images")
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private BlogPost post;

    @Size(max = 255)
    @Column(name = "image_url", unique = true)
    private String imageUrl;

    @Size(max = 255)
    @Column(name = "alt_text")
    private String altText;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }

    public Image() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
}
```

**Step 2: Verify all entities compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Validate entities against schema (requires Docker)**

Run: `docker compose up -d && cd backend && ./gradlew bootRun`
Expected: Application starts without Hibernate validation errors. Flyway migration already applied, `ddl-auto: validate` confirms entities match schema.

> **Note:** This step requires Docker Compose with PostgreSQL and Redis running. If validation fails, check column name mismatches between the entity `@Column` annotations and the Flyway migration SQL.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/blogplatform/category/ backend/src/main/java/com/blogplatform/tag/ backend/src/main/java/com/blogplatform/post/ backend/src/main/java/com/blogplatform/comment/ backend/src/main/java/com/blogplatform/like/ backend/src/main/java/com/blogplatform/author/ backend/src/main/java/com/blogplatform/subscription/ backend/src/main/java/com/blogplatform/payment/ backend/src/main/java/com/blogplatform/notification/ backend/src/main/java/com/blogplatform/image/
git commit -m "feat: add all JPA entities for blog platform domain model"
```

---

### Task 14: Full Auth Integration Test — Register → Login → /me → Logout → Rejected

**Files:**
- Create: `backend/src/test/java/com/blogplatform/auth/AuthFlowIT.java`

**Step 1: Write the integration test**

Create `backend/src/test/java/com/blogplatform/auth/AuthFlowIT.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.auth.dto.LoginRequest;
import com.blogplatform.auth.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Uses in-memory sessions for test simplicity.
        // Redis-backed session persistence is tested separately if needed
        // (requires Redis Testcontainer). Auth logic is the focus here.
        registry.add("spring.session.store-type", () -> "none");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullAuthFlow_register_login_me_logout_rejected() throws Exception {
        // 1. Register
        var register = new RegisterRequest("flowuser", "flow@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("flowuser"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        // 2. Login
        var login = new LoginRequest("flowuser", "Password1");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("flowuser"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

        // 3. GET /me with session
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("flowuser"));

        // 4. Logout
        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isOk());

        // 5. GET /me after logout — rejected
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
```

**Step 2: Run the test**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthFlowIT" -i`
Expected: PASS — full auth flow works end-to-end.

**Step 3: Commit**

```bash
git add backend/src/test/java/com/blogplatform/auth/AuthFlowIT.java
git commit -m "test: add full auth flow integration test (register→login→me→logout→rejected)"
```

---

### Task 15: Verify Full Phase 1 — Run All Tests, Start Application

**Step 1: Run all tests**

Run: `cd backend && ./gradlew test`
Expected: All tests pass. Both unit tests (AuthServiceTest, OwnershipVerifierTest, RateLimitFilterTest) and integration tests (AuthControllerTest, AuthFlowIT) are green.

**Step 2: Start the full application**

Run: `docker compose up -d && cd backend && ./gradlew bootRun`
Expected: Application starts on port 8080. Flyway migrations applied. Swagger UI accessible at `http://localhost:8080/swagger-ui.html`. Health check at `http://localhost:8080/actuator/health` returns `{"status":"UP"}`.

**Step 3: Smoke test with curl**

```bash
# Register
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@test.com","password":"Password1"}' | jq .

# Login (save session cookie)
curl -s -c cookies.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"Password1"}' | jq .

# /me with session
curl -s -b cookies.txt http://localhost:8080/api/v1/auth/me | jq .
```

Expected: All return `{"success": true, ...}` with appropriate data.

**Step 4: Commit (if any fixes were needed)**

```bash
git add backend/src/main/java/com/blogplatform/ backend/src/test/java/com/blogplatform/
git commit -m "fix: phase 1 smoke test fixes"
```

---

## Summary

Phase 1 delivers:
- Spring Boot 3.x + Java 21 Gradle project
- Docker Compose with PostgreSQL 16 + Redis 7
- Flyway migrations for all 17 tables + seed data + search vector trigger
- All JPA entities with relationships and validation
- Spring Security with session-based auth (Redis-backed)
- CSRF protection with cookie-based tokens
- CORS configuration for React dev server
- Bucket4j rate limiting (tiered: anon 60/min, auth 120/min, auth endpoints 10/min)
- Auth endpoints: register, login, logout, /me
- OwnershipVerifier for IDOR prevention
- GlobalExceptionHandler with consistent ApiResponse format
- SpringDoc OpenAPI / Swagger documentation
- Unit tests (AuthService, OwnershipVerifier, RateLimitFilter)
- Integration tests (AuthController, full auth flow)

### Known Limitations (Phase 1)
- Rate limiting is per-JVM only (not distributed). For multi-instance deployments, migrate to Bucket4j Redis-backed ProxyManager.
- Auth flow integration test uses in-memory sessions (not Redis). Auth logic is covered; Redis session persistence is an infrastructure concern.
- Soft delete `@Filter` annotations on BlogPost are defined but not wired — filter activation in `PostService` is a Phase 2 task.

**Next plan:** Phase 2 (Core Features — full REST API) will be a separate plan document.
