# Phase 1A: Project Setup & Infrastructure — Implementation Plan

(Part 1 of 3 — Tasks 1-5 of 15)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Spring Boot project skeleton with PostgreSQL, Redis, Flyway migrations, JPA entities, Spring Security session-based auth, rate limiting, and auth endpoints — producing a running, tested foundation for the full blog platform.

**Architecture:** Monorepo with `backend/` (Spring Boot 3.x, Java 21, Gradle) and `frontend/` (later). PostgreSQL 16 + Redis 7 via Docker Compose. Session-based auth with Redis-backed sessions. Bucket4j rate limiting. All endpoints under `/api/v1/`.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers, JUnit 5, Mockito

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — the authoritative source for all schema, API, security, and business logic decisions.

## Phase 1 Parts

- **Phase 1A: Project Setup & Infrastructure** — Tasks 1-5 (`2026-03-01-phase1a-project-setup-implementation.md`)
- **Phase 1B: Auth System** — Tasks 6-10 (`2026-03-01-phase1b-auth-security-implementation.md`)
- **Phase 1C: Rate Limiting, Entities & Verification** — Tasks 11-15 (`2026-03-01-phase1c-ratelimit-entities-implementation.md`)

---

### Task 1: Initialize Gradle Spring Boot Project

**Files:**
- Create: `backend/build.gradle`
- Create: `backend/settings.gradle`
- Create: `backend/src/main/java/com/blogplatform/BlogPlatformApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-dev.yml`
- Create: `backend/src/main/resources/application-test.yml`
- Create: `backend/src/main/resources/application-prod.yml`

**Step 1: Create the Gradle project structure**

Create `backend/settings.gradle`:
```groovy
rootProject.name = 'blog-platform'
```

Create `backend/build.gradle`:
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.3'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.blogplatform'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.session:spring-session-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'

    // Flyway
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // PostgreSQL
    runtimeOnly 'org.postgresql:postgresql'

    // Rate limiting
    implementation 'com.bucket4j:bucket4j-core:8.10.1'

    // OpenAPI / Swagger
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5'

    // Apache Tika (image magic byte validation)
    implementation 'org.apache.tika:tika-core:3.0.0'

    // Testing
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'com.redis:testcontainers-redis:2.2.4'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

Create `backend/src/main/java/com/blogplatform/BlogPlatformApplication.java`:
```java
package com.blogplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
public class BlogPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(BlogPlatformApplication.class, args);
    }
}
```

Create `backend/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: blog-platform
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  session:
    store-type: redis
    timeout: 30m
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 10MB

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
```

Create `backend/src/main/resources/application-dev.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/blogplatform
    username: blogplatform
    password: blogplatform
  data:
    redis:
      host: localhost
      port: 6379
      password: devredispassword
  jpa:
    show-sql: true

logging:
  level:
    com.blogplatform: DEBUG
    org.hibernate.SQL: DEBUG
```

Create `backend/src/main/resources/application-test.yml`:
```yaml
spring:
  jpa:
    show-sql: false
  session:
    store-type: none
  flyway:
    enabled: true

logging:
  level:
    root: WARN
    com.blogplatform: INFO
```

> **Note:** Integration tests must use an abstract base test class with `@ServiceConnection` and a shared static Testcontainers `PostgreSQLContainer`. This provides the datasource dynamically — no hardcoded JDBC URL is needed in this profile. See Task 5 for the base test class pattern.

Create `backend/src/main/resources/application-prod.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6379
      password: ${REDIS_PASSWORD}

logging:
  level:
    root: WARN
    com.blogplatform: INFO
    org.hibernate.SQL: WARN
    org.springframework.web: WARN
```

**Step 2: Generate the Gradle wrapper**

Run: `cd backend && gradle wrapper --gradle-version 8.12`
Expected: Creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/` directory.

**Step 3: Verify the project compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/
git commit -m "feat: initialize Spring Boot project with Gradle and dependencies"
```

> **Note:** Commit the Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) — these are required for reproducible builds.

---

### Task 2: Docker Compose for PostgreSQL and Redis

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`
- Modify: `.gitignore` (add `.env`)

**Step 1: Create Docker Compose file**

Create `docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: blogplatform
      POSTGRES_USER: blogplatform
      POSTGRES_PASSWORD: ${DB_PASSWORD:-blogplatform}
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U blogplatform"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7
    command: redis-server --requirepass ${REDIS_PASSWORD:-devredispassword}
    ports:
      - "127.0.0.1:6379:6379"
    healthcheck:
      test: ["CMD-SHELL", "REDISCLI_AUTH=${REDIS_PASSWORD:-devredispassword} redis-cli ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

Create `.env.example`:
```
DB_PASSWORD=changeme-minimum-24-characters
REDIS_PASSWORD=changeme-minimum-24-characters
```

Add to `.gitignore`:
```
.env
```

**Step 2: Start containers and verify**

Run: `docker compose up -d && docker compose ps`
Expected: Both `postgres` and `redis` containers are `running (healthy)` after ~15 seconds.

**Step 3: Commit**

```bash
git add docker-compose.yml .env.example .gitignore
git commit -m "feat: add Docker Compose for PostgreSQL 16 and Redis 7"
```

---

### Task 3: Flyway Migration — Initial Schema (All Tables)

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__initial_schema.sql`

**Step 1: Write the initial schema migration**

Create `backend/src/main/resources/db/migration/V1__initial_schema.sql`:

```sql
-- UserAccount
CREATE TABLE user_account (
    account_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'AUTHOR', 'ADMIN')),
    is_vip BOOLEAN NOT NULL DEFAULT FALSE,
    vip_start_date TIMESTAMPTZ,
    vip_end_date TIMESTAMPTZ,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- UserProfile
CREATE TABLE user_profile (
    profile_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    bio TEXT,
    profile_pic_url VARCHAR(500),
    last_login TIMESTAMPTZ,
    login_count INTEGER NOT NULL DEFAULT 0
);

-- AuthorProfile
CREATE TABLE author_profile (
    author_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    biography TEXT,
    social_links JSONB,
    expertise VARCHAR(255)
);

-- Category
CREATE TABLE category (
    category_id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

-- Tag
CREATE TABLE tag (
    tag_id BIGSERIAL PRIMARY KEY,
    tag_name VARCHAR(50) NOT NULL UNIQUE
);

-- BlogPost
-- Note: author_id references user_account(account_id), not author_profile.
-- Any user can author posts; an author_profile is optional supplemental data.
CREATE TABLE blog_post (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    author_id BIGINT NOT NULL REFERENCES user_account(account_id),
    category_id BIGINT REFERENCES category(category_id),
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    search_vector TSVECTOR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_blog_post_author ON blog_post(author_id);
CREATE INDEX idx_blog_post_category ON blog_post(category_id);
CREATE INDEX idx_blog_post_search_vector ON blog_post USING GIN(search_vector);

-- PostTags (join table)
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tag(tag_id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

-- PostUpdateLog
CREATE TABLE post_update_log (
    log_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    old_title VARCHAR(255),
    new_title VARCHAR(255),
    old_content TEXT,
    new_content TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_post_update_log_post ON post_update_log(post_id);

-- Comment
CREATE TABLE comment (
    comment_id BIGSERIAL PRIMARY KEY,
    content VARCHAR(250) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    parent_comment_id BIGINT REFERENCES comment(comment_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comment_post ON comment(post_id);
CREATE INDEX idx_comment_parent ON comment(parent_comment_id);

-- Like
CREATE TABLE post_like (
    like_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, post_id)
);

CREATE INDEX idx_like_post ON post_like(post_id);

-- ReadPost (join table)
CREATE TABLE read_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- SavedPost (join table)
CREATE TABLE saved_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- Subscriber
CREATE TABLE subscriber (
    subscriber_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expiration_date TIMESTAMPTZ
);

-- Payment
CREATE TABLE payment (
    payment_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(20) NOT NULL,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    payment_date TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_account ON payment(account_id);

-- Notification
CREATE TABLE notification (
    notification_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_account_read_created ON notification(account_id, is_read, created_at);

-- Image
CREATE TABLE image (
    image_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    image_url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(255),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_post ON image(post_id);

-- PasswordResetToken
CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

-- EmailVerificationToken
CREATE TABLE email_verification_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);
```

**Step 2: Verify the migration runs**

Run: `cd backend && ./gradlew bootRun` (with Docker Compose running)
Expected: Application starts, Flyway logs show `V1__initial_schema.sql` applied successfully. Then stop the app (Ctrl+C).

**Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V1__initial_schema.sql
git commit -m "feat: add Flyway V1 migration with all 17 tables"
```

---

### Task 4: Flyway Migration — Seed Data and Search Vector Trigger

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__seed_data.sql`
- Create: `backend/src/main/resources/db/migration/R__search_vector_trigger.sql`
- Create: `backend/src/main/java/com/blogplatform/config/DevDataSeeder.java`

**Step 1: Write seed data migration**

Create `backend/src/main/resources/db/migration/V2__seed_data.sql`:
```sql
-- Default categories
INSERT INTO category (category_name, description) VALUES
    ('Technology', 'Posts about software, hardware, and tech trends'),
    ('Science', 'Scientific discoveries and research'),
    ('Lifestyle', 'Health, wellness, and daily living'),
    ('Travel', 'Destinations, tips, and travel stories'),
    ('Food', 'Recipes, reviews, and culinary adventures');
```

> **Note:** The admin seed user has been moved out of this versioned migration. See the `DevDataSeeder` below, which only runs under the `dev` profile.

Create `backend/src/main/java/com/blogplatform/config/DevDataSeeder.java`:
```java
package com.blogplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcTemplate jdbcTemplate;

    public DevDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE username = 'admin'", Integer.class);
        if (count != null && count > 0) {
            log.info("Dev admin user already exists, skipping seed");
            return;
        }

        // BCrypt hash with work factor 12 — dev-only, never use in production
        jdbcTemplate.update("""
                INSERT INTO user_account (username, email, password_hash, role, email_verified)
                VALUES ('admin', 'admin@blogplatform.com',
                        '$2a$12$LJ3m4ys3uz0b/tMkgqHUZeJ0SJyKfxBVOKFqW8GbMFmJN7gmPVqtG',
                        'ADMIN', TRUE)
                """);

        jdbcTemplate.update("""
                INSERT INTO user_profile (account_id, first_name, last_name)
                SELECT account_id, 'System', 'Admin' FROM user_account WHERE username = 'admin'
                """);

        log.info("Dev admin user seeded successfully");
    }
}
```

**Step 2: Write repeatable search vector trigger migration**

Create `backend/src/main/resources/db/migration/R__search_vector_trigger.sql`:
```sql
-- Function to strip Markdown syntax before building tsvector
CREATE OR REPLACE FUNCTION strip_markdown_and_update_search_vector()
RETURNS TRIGGER AS $$
DECLARE
    clean_title TEXT;
    clean_content TEXT;
BEGIN
    clean_title := NEW.title;
    clean_content := NEW.content;

    -- Strip Markdown headings (##, ###, etc.)
    clean_content := regexp_replace(clean_content, '#{1,6}\s+', '', 'g');
    -- Strip bold/italic (**text**, *text*, __text__, _text_)
    clean_content := regexp_replace(clean_content, '\*{1,2}([^*]+)\*{1,2}', '\1', 'g');
    clean_content := regexp_replace(clean_content, '_{1,2}([^_]+)_{1,2}', '\1', 'g');
    -- Strip inline code (`code`)
    clean_content := regexp_replace(clean_content, '`([^`]+)`', '\1', 'g');
    -- Strip code fences (```...```)
    clean_content := regexp_replace(clean_content, '```[\s\S]*?```', '', 'g');
    -- Strip links [text](url) → text
    clean_content := regexp_replace(clean_content, '\[([^\]]+)\]\([^)]+\)', '\1', 'g');
    -- Strip images ![alt](url)
    clean_content := regexp_replace(clean_content, '!\[([^\]]*)\]\([^)]+\)', '\1', 'g');

    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(clean_title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(clean_content, '')), 'B');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_blog_post_search_vector ON blog_post;
CREATE TRIGGER trg_blog_post_search_vector
    BEFORE INSERT OR UPDATE OF title, content ON blog_post
    FOR EACH ROW
    EXECUTE FUNCTION strip_markdown_and_update_search_vector();
```

**Step 3: Verify migrations run**

Run: `cd backend && ./gradlew bootRun`
Expected: Flyway logs show V2 and R__search_vector_trigger applied. Stop the app.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/com/blogplatform/config/DevDataSeeder.java
git commit -m "feat: add seed data, search vector trigger, and dev admin seeder"
```

---

### Task 5: Common Layer — ApiResponse, PagedResponse, Exceptions, AuditableEntity

**Files:**
- Create: `backend/src/main/java/com/blogplatform/common/dto/ApiResponse.java`
- Create: `backend/src/main/java/com/blogplatform/common/dto/PagedResponse.java`
- Create: `backend/src/main/java/com/blogplatform/common/exception/ResourceNotFoundException.java`
- Create: `backend/src/main/java/com/blogplatform/common/exception/UnauthorizedException.java`
- Create: `backend/src/main/java/com/blogplatform/common/exception/ForbiddenException.java`
- Create: `backend/src/main/java/com/blogplatform/common/exception/BadRequestException.java`
- Create: `backend/src/main/java/com/blogplatform/common/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/blogplatform/common/audit/CreatedAtEntity.java`
- Create: `backend/src/main/java/com/blogplatform/common/audit/AuditableEntity.java`
- Create: `backend/src/test/java/com/blogplatform/BaseIntegrationTest.java`

**Step 1: Write the common classes**

Create `backend/src/main/java/com/blogplatform/common/dto/ApiResponse.java`:
```java
package com.blogplatform.common.dto;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/dto/PagedResponse.java`:
```java
package com.blogplatform.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/exception/ResourceNotFoundException.java`:
```java
package com.blogplatform.common.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/exception/UnauthorizedException.java`:
```java
package com.blogplatform.common.exception;

public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/exception/ForbiddenException.java`:
```java
package com.blogplatform.common.exception;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/exception/BadRequestException.java`:
```java
package com.blogplatform.common.exception;

public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/exception/GlobalExceptionHandler.java`:
```java
package com.blogplatform.common.exception;

import com.blogplatform.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // Re-throw Spring Security exceptions so ExceptionTranslationFilter handles them
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthenticationException(AuthenticationException ex) throws AuthenticationException {
        throw ex;
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/audit/CreatedAtEntity.java`:
```java
package com.blogplatform.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

Create `backend/src/main/java/com/blogplatform/common/audit/AuditableEntity.java`:
```java
package com.blogplatform.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

> **Usage:** Entities for tables with both `created_at` and `updated_at` (e.g., `blog_post`) extend `AuditableEntity`. Entities for tables with only `created_at` (e.g., `comment`, `notification`, `post_like`) extend `CreatedAtEntity`.

**Step 2: Create the integration test base class**

Create `backend/src/test/java/com/blogplatform/BaseIntegrationTest.java`:
```java
package com.blogplatform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16");
}
```

> **Note:** All integration tests should extend `BaseIntegrationTest`. The shared static container is reused across all test classes in a single test run.

**Step 3: Verify it compiles**

Run: `cd backend && ./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/blogplatform/common/ backend/src/test/java/com/blogplatform/BaseIntegrationTest.java
git commit -m "feat: add common layer — ApiResponse, exceptions, CreatedAtEntity, AuditableEntity, base test class"
```

---

> **Next:** Continue to Phase 1B for Tasks 6-10.

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| v1.0 | 2026-03-01 | Initial plan |
| v1.1 | 2026-03-03 | Applied critical review 1 fixes (see below) |
| v1.2 | 2026-03-03 | Applied critical review 2 fixes (see below) |
| v1.3 | 2026-03-03 | Applied security audit fixes (see below) |

### v1.1 Changes (Critical Review 1)

**Critical fixes:**
- **C1 — Seed data ID assumption:** Changed `VALUES (1, 'System', 'Admin')` to `SELECT account_id ... FROM user_account WHERE username = 'admin'` (Task 4)
- **C2 — AuditableEntity schema mismatch:** Split into `CreatedAtEntity` (created_at only) and `AuditableEntity` (created_at + updated_at). Added usage guidance. (Task 5)
- **C3 — Test datasource missing:** Added `BaseIntegrationTest` abstract class with `@ServiceConnection` and shared Testcontainers `PostgreSQLContainer`. Added `spring-boot-testcontainers` dependency. Added note in `application-test.yml`. (Tasks 1, 5)
- **C4 — Plaintext admin password:** Removed plaintext password from seed data comment. Added production deployment warning. (Task 4)
- **C5 — No Gradle wrapper:** Added explicit `gradle wrapper` generation step before compilation. Added commit note for wrapper files. (Task 1)

**Minor fixes:**
- **M1 — Missing Testcontainers dependency:** Added `spring-boot-testcontainers` to test dependencies (supersedes generic testcontainers module). (Task 1)
- **M2 — TIMESTAMP vs TIMESTAMPTZ:** Changed all `TIMESTAMP` columns to `TIMESTAMPTZ` for timezone safety. (Task 3)
- **M3 — ON DELETE CASCADE for join tables:** Added `ON DELETE CASCADE` to `post_tags`, `read_post`, and `saved_post` foreign keys. (Task 3)
- **M4 — GlobalExceptionHandler catch-all:** Added `@ExceptionHandler(Exception.class)` with logging and generic 500 response. (Task 5)
- **M5 — PagedResponse factory method:** Added `public static <T> PagedResponse<T> from(Page<T> page)` convenience method. (Task 5)
- **M7 — author_id naming clarity:** Added SQL comment explaining that `blog_post.author_id` references `user_account(account_id)`. (Task 3)

**Review reference:** `docs/plans/2026-03-01-phase1a-project-setup-implementation-critical-review-1.md`

### v1.2 Changes (Critical Review 2)

**Critical fixes:**
- **C1 — Unconstrained role column:** Added `CHECK (role IN ('USER', 'AUTHOR', 'ADMIN'))` constraint to `user_account.role` (Task 3)
- **C2 — post_update_log FK blocks deletes:** Added `ON DELETE CASCADE` to `post_update_log.post_id` foreign key (Task 3)
- **C3 — Multiline code fence regex broken:** Changed `'```[^`]*```'` to `'```[\s\S]*?```'` to match across newlines (Task 4)

**Minor fixes:**
- **M1 — Missing Redis Testcontainers dependency:** Added `com.redis:testcontainers-redis:2.2.4` to test dependencies (Task 1)
- **M3 — Prod profile defaults mask misconfiguration:** Removed defaults for `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `REDIS_HOST` in `application-prod.yml` so app fails fast on missing env vars (Task 1)
- **M6 — Malformed JSON bypasses ApiResponse envelope:** Added `HttpMessageNotReadableException` handler returning 400 with `ApiResponse.error("Malformed request body")` (Task 5)
- **M7 — Redis healthcheck exposes password:** Changed to `REDISCLI_AUTH` environment variable approach instead of `-a` flag (Task 2)

**Skipped (no change needed):**
- M2 — Comments are immutable by design (no `updated_at` needed)
- M4 — `user_account.updated_at` is a scope expansion
- M5 — `ApiResponse.error()` generic type is fine as-is

**Review reference:** `docs/plans/2026-03-01-phase1a-project-setup-implementation-critical-review-2.md`

### v1.3 Changes (Security Audit)

**Fixes applied:**
- **S2 — Admin seed in versioned migration (High):** Removed admin user INSERT from `V2__seed_data.sql`. Created `DevDataSeeder` (`@Profile("dev")` `CommandLineRunner`) that seeds the admin user only in dev environments, with idempotency check. (Task 4)
- **S3 — Docker ports bound to 0.0.0.0 (Medium):** Changed port mappings to `127.0.0.1:5432:5432` and `127.0.0.1:6379:6379` to bind to localhost only. (Task 2)
- **S6 — Catch-all suppresses security exceptions (Medium):** Added explicit `@ExceptionHandler` methods for `AccessDeniedException` and `AuthenticationException` that re-throw to let Spring Security's `ExceptionTranslationFilter` handle them. (Task 5)

**Deferred (out of scope for Phase 1A):**
- S1 — Hardcoded dev credentials: Accepted as Low risk; prod profile already uses env vars without defaults (v1.2 M3).
- S4 — Missing `updated_at` on `user_account`: Scope expansion, track for future phase.
- S5 — No soft-delete filtering at DB level: Address with `@SQLRestriction` when implementing BlogPost entity.
- S7 — Payment amount upper bound: Address when implementing payment business logic.
- S8 — Comment content XSS: Informational; address with output encoding in API layer.

**Review reference:** `docs/plans/2026-03-03-phase1a-project-setup-implementation-security-audit-1.md`
