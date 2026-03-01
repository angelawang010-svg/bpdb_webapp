# Phase 1: Foundation (Back-End Core) — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Spring Boot project skeleton with PostgreSQL, Redis, Flyway migrations, JPA entities, Spring Security session-based auth, rate limiting, and auth endpoints — producing a running, tested foundation for the full blog platform.

**Architecture:** Monorepo with `backend/` (Spring Boot 3.x, Java 21, Gradle) and `frontend/` (later). PostgreSQL 16 + Redis 7 via Docker Compose. Session-based auth with Redis-backed sessions. Bucket4j rate limiting. All endpoints under `/api/v1/`.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers, JUnit 5, Mockito

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — the authoritative source for all schema, API, security, and business logic decisions.

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

### Task 6: User & Auth Entities — UserAccount, UserProfile, Role

**Files:**
- Create: `backend/src/main/java/com/blogplatform/user/UserAccount.java`
- Create: `backend/src/main/java/com/blogplatform/user/UserProfile.java`
- Create: `backend/src/main/java/com/blogplatform/user/Role.java`
- Create: `backend/src/main/java/com/blogplatform/user/UserRepository.java`

**Step 1: Write the entities and repository**

Create `backend/src/main/java/com/blogplatform/user/Role.java`:
```java
package com.blogplatform.user;

public enum Role {
    ADMIN, AUTHOR, USER
}
```

Create `backend/src/main/java/com/blogplatform/user/UserAccount.java`:
```java
package com.blogplatform.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(name = "is_vip", nullable = false)
    private boolean isVip = false;

    @Column(name = "vip_start_date")
    private Instant vipStartDate;

    @Column(name = "vip_end_date")
    private Instant vipEndDate;

    @Column(name = "two_factor_enabled", nullable = false)
    private boolean twoFactorEnabled = false;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToOne(mappedBy = "userAccount", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private UserProfile userProfile;

    // Getters and setters
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isVip() { return isVip; }
    public void setVip(boolean vip) { isVip = vip; }
    public Instant getVipStartDate() { return vipStartDate; }
    public void setVipStartDate(Instant vipStartDate) { this.vipStartDate = vipStartDate; }
    public Instant getVipEndDate() { return vipEndDate; }
    public void setVipEndDate(Instant vipEndDate) { this.vipEndDate = vipEndDate; }
    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public void setTwoFactorEnabled(boolean twoFactorEnabled) { this.twoFactorEnabled = twoFactorEnabled; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public Instant getCreatedAt() { return createdAt; }
    public UserProfile getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfile userProfile) { this.userProfile = userProfile; }
}
```

Create `backend/src/main/java/com/blogplatform/user/UserProfile.java`:
```java
package com.blogplatform.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_profile")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private UserAccount userAccount;

    @Column(name = "first_name", length = 50)
    private String firstName;

    @Column(name = "last_name", length = 50)
    private String lastName;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_pic_url", length = 500)
    private String profilePicUrl;

    @Column(name = "last_login")
    private Instant lastLogin;

    @Column(name = "login_count", nullable = false)
    private int loginCount = 0;

    // Getters and setters
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public UserAccount getUserAccount() { return userAccount; }
    public void setUserAccount(UserAccount userAccount) { this.userAccount = userAccount; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getProfilePicUrl() { return profilePicUrl; }
    public void setProfilePicUrl(String profilePicUrl) { this.profilePicUrl = profilePicUrl; }
    public Instant getLastLogin() { return lastLogin; }
    public void setLastLogin(Instant lastLogin) { this.lastLogin = lastLogin; }
    public int getLoginCount() { return loginCount; }
    public void setLoginCount(int loginCount) { this.loginCount = loginCount; }
}
```

Create `backend/src/main/java/com/blogplatform/user/UserRepository.java`:
```java
package com.blogplatform.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    Optional<UserAccount> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
```

**Step 2: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/user/
git commit -m "feat: add UserAccount, UserProfile, Role entities and UserRepository"
```

---

### Task 7: Security Configuration — Spring Security + Redis Sessions

**Files:**
- Create: `backend/src/main/java/com/blogplatform/config/SecurityConfig.java`
- Create: `backend/src/main/java/com/blogplatform/config/WebConfig.java`

**Step 1: Write Spring Security configuration**

Create `backend/src/main/java/com/blogplatform/config/SecurityConfig.java`:
```java
package com.blogplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                    "/api/v1/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/tags").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/authors", "/api/v1/authors/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/posts/{postId}/comments").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionFixation().newSession()
                .maximumSessions(1)
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"success\":false,\"data\":null,\"message\":\"Authentication required\",\"timestamp\":\""
                        + java.time.Instant.now() + "\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"success\":false,\"data\":null,\"message\":\"Access denied\",\"timestamp\":\""
                        + java.time.Instant.now() + "\"}");
                })
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpStatus.OK.value());
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"success\":true,\"data\":null,\"message\":\"Logged out successfully\",\"timestamp\":\""
                        + java.time.Instant.now() + "\"}");
                })
            );

        return http.build();
    }
}
```

Create `backend/src/main/java/com/blogplatform/config/WebConfig.java`:
```java
package com.blogplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

**Step 2: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/config/
git commit -m "feat: add Spring Security config with CSRF, CORS, session management"
```

---

### Task 8: Auth DTOs

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/blogplatform/auth/dto/AuthResponse.java`

**Step 1: Write the DTOs**

Create `backend/src/main/java/com/blogplatform/auth/dto/RegisterRequest.java`:
```java
package com.blogplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase, one lowercase, and one digit")
        String password
) {}
```

Create `backend/src/main/java/com/blogplatform/auth/dto/LoginRequest.java`:
```java
package com.blogplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
) {}
```

Create `backend/src/main/java/com/blogplatform/auth/dto/AuthResponse.java`:
```java
package com.blogplatform.auth.dto;

public record AuthResponse(
        Long accountId,
        String username,
        String email,
        String role,
        boolean isVip,
        boolean emailVerified
) {}
```

**Step 2: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/
git commit -m "feat: add auth DTOs — RegisterRequest, LoginRequest, AuthResponse"
```

---

### Task 9: AuthService — Registration and Login Logic

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/AuthService.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/blogplatform/auth/AuthServiceTest.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.user.Role;
import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder);
    }

    @Test
    void register_withValidData_createsUserWithHashedPassword() {
        var request = new RegisterRequest("testuser", "test@example.com", "Password1");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1")).thenReturn("hashed_password");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserAccount result = authService.register(request);

        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getEmail()).isEqualTo("test@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed_password");
        assertThat(result.getRole()).isEqualTo(Role.USER);
        assertThat(result.getUserProfile()).isNotNull();
    }

    @Test
    void register_withDuplicateUsername_throwsBadRequest() {
        var request = new RegisterRequest("existing", "new@example.com", "Password1");
        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Username already taken");
    }

    @Test
    void register_withDuplicateEmail_throwsBadRequest() {
        var request = new RegisterRequest("newuser", "existing@example.com", "Password1");
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Email already registered");
    }

    @Test
    void authenticate_withValidCredentials_returnsUser() {
        var user = new UserAccount();
        user.setUsername("testuser");
        user.setPasswordHash("hashed");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed")).thenReturn(true);

        UserAccount result = authService.authenticate("testuser", "Password1");

        assertThat(result.getUsername()).isEqualTo("testuser");
    }

    @Test
    void authenticate_withWrongPassword_throwsUnauthorized() {
        var user = new UserAccount();
        user.setPasswordHash("hashed");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("testuser", "wrong"))
                .isInstanceOf(com.blogplatform.common.exception.UnauthorizedException.class);
    }

    @Test
    void authenticate_withNonexistentUser_throwsUnauthorized() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate("nobody", "Password1"))
                .isInstanceOf(com.blogplatform.common.exception.UnauthorizedException.class);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthServiceTest" -i`
Expected: FAIL — `AuthService` does not exist yet.

**Step 3: Write the implementation**

Create `backend/src/main/java/com/blogplatform/auth/AuthService.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.UnauthorizedException;
import com.blogplatform.user.Role;
import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserAccount register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BadRequestException("Username already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("Email already registered");
        }

        UserAccount account = new UserAccount();
        account.setUsername(request.username());
        account.setEmail(request.email());
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(Role.USER);

        UserProfile profile = new UserProfile();
        profile.setUserAccount(account);
        account.setUserProfile(profile);

        return userRepository.save(account);
    }

    public UserAccount authenticate(String username, String password) {
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        return user;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthServiceTest" -i`
Expected: All 5 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/AuthService.java backend/src/test/java/com/blogplatform/auth/AuthServiceTest.java
git commit -m "feat: add AuthService with register and authenticate, with unit tests"
```

---

### Task 10: AuthController — Register, Login, Logout, /me Endpoints

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/AuthController.java`
- Create: `backend/src/test/java/com/blogplatform/auth/AuthControllerTest.java`

**Step 1: Write the failing integration test**

Create `backend/src/test/java/com/blogplatform/auth/AuthControllerTest.java`:
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
class AuthControllerTest {

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
    void register_withValidData_returns201() throws Exception {
        var request = new RegisterRequest("newuser", "new@example.com", "Password1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    void register_withInvalidPassword_returns400() throws Exception {
        var request = new RegisterRequest("user2", "user2@example.com", "weak");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_afterRegister_returns200WithUserInfo() throws Exception {
        var register = new RegisterRequest("loginuser", "login@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(register)));

        var login = new LoginRequest("loginuser", "Password1");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("loginuser"));
    }

    @Test
    void me_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthControllerTest" -i`
Expected: FAIL — `AuthController` does not exist yet.

**Step 3: Write the implementation**

Create `backend/src/main/java/com/blogplatform/auth/AuthController.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.auth.dto.AuthResponse;
import com.blogplatform.auth.dto.LoginRequest;
import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.dto.ApiResponse;
import com.blogplatform.user.UserAccount;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toAuthResponse(user), "Registration successful"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpSession session) {
        UserAccount user = authService.authenticate(request.username(), request.password());

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        var auth = new UsernamePasswordAuthenticationToken(user.getAccountId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
        session.setAttribute("USER_ID", user.getAccountId());

        return ResponseEntity.ok(ApiResponse.success(toAuthResponse(user), "Login successful"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthResponse>> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        UserAccount user = authService.findById(userId);
        return ResponseEntity.ok(ApiResponse.success(toAuthResponse(user)));
    }

    private AuthResponse toAuthResponse(UserAccount user) {
        return new AuthResponse(
                user.getAccountId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.isVip(),
                user.isEmailVerified()
        );
    }
}
```

Add `findById` method to `AuthService`:
```java
// Add this method to AuthService.java
public UserAccount findById(Long id) {
    return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
}
```

Note: The `findById` method requires adding `import com.blogplatform.common.exception.ResourceNotFoundException;` to `AuthService.java`.

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthControllerTest" -i`
Expected: All 4 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/ backend/src/test/java/com/blogplatform/auth/
git commit -m "feat: add AuthController with register, login, logout, /me endpoints"
```

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
