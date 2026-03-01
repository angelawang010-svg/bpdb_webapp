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

logging:
  level:
    root: WARN
    com.blogplatform: INFO
```

Create `backend/src/main/resources/application-prod.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:blogplatform}
    username: ${DB_USERNAME:blogplatform}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      password: ${REDIS_PASSWORD}

logging:
  level:
    root: WARN
    com.blogplatform: INFO
    org.hibernate.SQL: WARN
    org.springframework.web: WARN
```

**Step 2: Verify the project compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (may need to generate Gradle wrapper first)

**Step 3: Commit**

```bash
git add backend/
git commit -m "feat: initialize Spring Boot project with Gradle and dependencies"
```

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
      - "5432:5432"
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
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD:-devredispassword}", "ping"]
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
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    is_vip BOOLEAN NOT NULL DEFAULT FALSE,
    vip_start_date TIMESTAMP,
    vip_end_date TIMESTAMP,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- UserProfile
CREATE TABLE user_profile (
    profile_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    bio TEXT,
    profile_pic_url VARCHAR(500),
    last_login TIMESTAMP,
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
CREATE TABLE blog_post (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    author_id BIGINT NOT NULL REFERENCES user_account(account_id),
    category_id BIGINT REFERENCES category(category_id),
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    search_vector TSVECTOR,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_blog_post_author ON blog_post(author_id);
CREATE INDEX idx_blog_post_category ON blog_post(category_id);
CREATE INDEX idx_blog_post_search_vector ON blog_post USING GIN(search_vector);

-- PostTags (join table)
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    tag_id BIGINT NOT NULL REFERENCES tag(tag_id),
    PRIMARY KEY (post_id, tag_id)
);

-- PostUpdateLog
CREATE TABLE post_update_log (
    log_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    old_title VARCHAR(255),
    new_title VARCHAR(255),
    old_content TEXT,
    new_content TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_post_update_log_post ON post_update_log(post_id);

-- Comment
CREATE TABLE comment (
    comment_id BIGSERIAL PRIMARY KEY,
    content VARCHAR(250) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    parent_comment_id BIGINT REFERENCES comment(comment_id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comment_post ON comment(post_id);
CREATE INDEX idx_comment_parent ON comment(parent_comment_id);

-- Like
CREATE TABLE post_like (
    like_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, post_id)
);

CREATE INDEX idx_like_post ON post_like(post_id);

-- ReadPost
CREATE TABLE read_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    read_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- SavedPost
CREATE TABLE saved_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    saved_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- Subscriber
CREATE TABLE subscriber (
    subscriber_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    subscribed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expiration_date TIMESTAMP
);

-- Payment
CREATE TABLE payment (
    payment_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(20) NOT NULL,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    payment_date TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_account ON payment(account_id);

-- Notification
CREATE TABLE notification (
    notification_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_account_read_created ON notification(account_id, is_read, created_at);

-- Image
CREATE TABLE image (
    image_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    image_url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(255),
    uploaded_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_post ON image(post_id);

-- PasswordResetToken
CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

-- EmailVerificationToken
CREATE TABLE email_verification_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMP NOT NULL,
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

-- Admin user (password: Admin123! — BCrypt hash with work factor 12)
INSERT INTO user_account (username, email, password_hash, role, email_verified)
VALUES ('admin', 'admin@blogplatform.com',
        '$2a$12$LJ3m4ys3uz0b/tMkgqHUZeJ0SJyKfxBVOKFqW8GbMFmJN7gmPVqtG',
        'ADMIN', TRUE);

INSERT INTO user_profile (account_id, first_name, last_name)
VALUES (1, 'System', 'Admin');
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
    clean_content := regexp_replace(clean_content, '```[^`]*```', '', 'g');
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
git add backend/src/main/resources/db/migration/
git commit -m "feat: add seed data and search vector trigger migrations"
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
- Create: `backend/src/main/java/com/blogplatform/common/audit/AuditableEntity.java`

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

import java.util.List;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

**Step 2: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/common/
git commit -m "feat: add common layer — ApiResponse, exceptions, AuditableEntity"
```

---

> **Next:** Continue to Phase 1B for Tasks 6-10.
