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

---

### Task 11: Rate Limiting Configuration — Bucket4j

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/RateLimitConfig.java`
- Create: `backend/src/main/java/com/blogplatform/config/RateLimitFilter.java`

**Step 1: Write the rate limit filter**

Create `backend/src/main/java/com/blogplatform/config/RateLimitFilter.java`:
```java
package com.blogplatform.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

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
            key = "auth:" + getClientIp(request);
            bucket = buckets.computeIfAbsent(key, k -> createBucket(10));
        } else if (isAuthenticated) {
            key = "user:" + auth.getPrincipal();
            bucket = buckets.computeIfAbsent(key, k -> createBucket(120));
        } else {
            key = "anon:" + getClientIp(request);
            bucket = buckets.computeIfAbsent(key, k -> createBucket(60));
        }

        if (bucket.tryConsume(1)) {
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"success\":false,\"data\":null,\"message\":\"Rate limit exceeded\",\"timestamp\":\""
                    + java.time.Instant.now() + "\"}");
        }
    }

    private Bucket createBucket(int capacityPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacityPerMinute, Duration.ofMinutes(1)))
                .build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

Create `backend/src/main/java/com/blogplatform/config/RateLimitConfig.java`:
```java
package com.blogplatform.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
```

**Step 2: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/config/RateLimitFilter.java backend/src/main/java/com/blogplatform/config/RateLimitConfig.java
git commit -m "feat: add Bucket4j rate limiting — tiered by user type"
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
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin(1L, auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenAdmin_returnsTrue() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                2L, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(verifier.isOwnerOrAdmin(1L, auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenNeitherOwnerNorAdmin_returnsFalse() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                2L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin(1L, auth)).isFalse();
    }

    @Test
    void verify_whenNotOwnerOrAdmin_throwsAccessDenied() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                2L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(() -> verifier.verify(1L, auth))
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

    public boolean isOwnerOrAdmin(Long resourceOwnerId, Authentication authentication) {
        Long currentUserId = (Long) authentication.getPrincipal();
        if (currentUserId.equals(resourceOwnerId)) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public void verify(Long resourceOwnerId, Authentication authentication) {
        if (!isOwnerOrAdmin(resourceOwnerId, authentication)) {
            throw new AccessDeniedException("You do not have permission to access this resource");
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.common.security.OwnershipVerifierTest" -i`
Expected: All 4 tests PASS.

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

This is a large task. Each entity maps directly to the schema in V1__initial_schema.sql. The implementer should create each entity class with:
- `@Entity` and `@Table` annotations matching the SQL table name
- `@Id` and `@GeneratedValue(strategy = IDENTITY)` on primary keys
- Appropriate `@ManyToOne`, `@OneToMany`, `@ManyToMany`, `@OneToOne` relationships
- `@Column` annotations matching column names where Java naming differs from SQL
- `@IdClass` for composite keys (ReadPost, SavedPost)
- `@JoinTable` for the post_tags many-to-many

Key entity details from the design doc:

**BlogPost:** Extends AuditableEntity. Has `@ManyToOne` to UserAccount (author), `@ManyToOne` to Category, `@ManyToMany` to Tag via `post_tags` join table. Has `@FilterDef`/`@Filter` for soft delete (`is_deleted = false`). `@Size(max = 100000)` on content.

**Comment:** Self-referencing `@ManyToOne` for parent_comment_id. `@Size(max = 250)` on content.

**Like:** Table name `post_like`. `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "post_id"}))`.

**ReadPost/SavedPost:** `@IdClass` with composite key classes (ReadPostId, SavedPostId).

**AuthorProfile:** `social_links` as `@JdbcTypeCode(SqlTypes.JSON)`.

**Payment:** `@Positive` on amount. PaymentMethod enum (CREDIT_CARD, PAYPAL, BANK_TRANSFER).

**Step 1: Create all entity classes**

(Each entity follows the pattern shown in Task 6 for UserAccount/UserProfile. Implementer creates all files with proper JPA annotations matching the design doc Section 4.)

**Step 2: Verify all entities compile and validate against schema**

Run: `cd backend && ./gradlew bootRun` (with Docker Compose running)
Expected: Application starts without Hibernate validation errors. Flyway migration already applied, `ddl-auto: validate` confirms entities match schema.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/
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
Expected: All tests pass. Both unit tests (AuthServiceTest, OwnershipVerifierTest) and integration tests (AuthControllerTest, AuthFlowIT) are green.

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
git add -A
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
- Unit tests (AuthService, OwnershipVerifier)
- Integration tests (AuthController, full auth flow)

**Next plan:** Phase 2 (Core Features — full REST API) will be a separate plan document.
