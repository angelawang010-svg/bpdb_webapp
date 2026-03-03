# Phase 1B: Auth System — Implementation Plan

(Part 2 of 3 — Tasks 6-11 of 16)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Spring Boot project skeleton with PostgreSQL, Redis, Flyway migrations, JPA entities, Spring Security session-based auth, rate limiting, and auth endpoints — producing a running, tested foundation for the full blog platform.

**Architecture:** Monorepo with `backend/` (Spring Boot 3.x, Java 21, Gradle) and `frontend/` (later). PostgreSQL 16 + Redis 7 via Docker Compose. Session-based auth with Redis-backed sessions. Bucket4j rate limiting. All endpoints under `/api/v1/`.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers, JUnit 5, Mockito

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — the authoritative source for all schema, API, security, and business logic decisions.

**Version:** 1.1 — Updated 2026-03-03 per critical review (`2026-03-01-phase1b-auth-security-implementation-critical-review-1.md`). All 15 review findings applied.

## Phase 1 Parts

- **Phase 1A: Project Setup & Infrastructure** — Tasks 1-5 (`2026-03-01-phase1a-project-setup-implementation.md`)
- **Phase 1B: Auth System** — Tasks 6-11 (`2026-03-01-phase1b-auth-security-implementation.md`)
- **Phase 1C: Rate Limiting, Entities & Verification** — Tasks 12-16 (`2026-03-01-phase1c-ratelimit-entities-implementation.md`)

> **Prerequisite:** Phase 1A (Tasks 1-5) must be complete.

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

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
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
    private Instant createdAt;

    @OneToOne(mappedBy = "userAccount", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private UserProfile userProfile;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

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

    @Column(name = "bio", columnDefinition = "TEXT")
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
- Create: `backend/src/main/java/com/blogplatform/auth/CustomUserDetailsService.java`

**Step 1: Write the CustomUserDetailsService**

Create `backend/src/main/java/com/blogplatform/auth/CustomUserDetailsService.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new User(
                account.getAccountId().toString(),
                account.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()))
        );
    }
}
```

**Step 2: Write Spring Security configuration**

Create `backend/src/main/java/com/blogplatform/config/SecurityConfig.java`:
```java
package com.blogplatform.config;

import com.blogplatform.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final FindByIndexNameSessionRepository<?> sessionRepository;

    public SecurityConfig(ObjectMapper objectMapper,
                          FindByIndexNameSessionRepository<?> sessionRepository) {
        this.objectMapper = objectMapper;
        this.sessionRepository = sessionRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    @SuppressWarnings("unchecked")
    public SpringSessionBackedSessionRegistry<?> sessionRegistry() {
        return new SpringSessionBackedSessionRegistry(sessionRepository);
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
                .sessionRegistry(sessionRegistry())
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error("Authentication required"));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error("Access denied"));
                })
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpStatus.OK.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.success(null, "Logged out successfully"));
                })
            );

        return http.build();
    }
}
```

Note: `ApiResponse` must have a static `error(String message)` factory method. If not already present from Phase 1A, add it:
```java
public static <T> ApiResponse<T> error(String message) {
    return new ApiResponse<>(false, null, message, Instant.now());
}
```

**Step 3: Write CORS configuration with externalized origins**

Create `backend/src/main/java/com/blogplatform/config/WebConfig.java`:
```java
package com.blogplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

Add to `application.yml` (or `application-dev.yml`):
```yaml
app:
  cors:
    allowed-origins: http://localhost:5173
```

**Step 4: Verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/config/ backend/src/main/java/com/blogplatform/auth/CustomUserDetailsService.java
git commit -m "feat: add Spring Security config with CSRF, CORS, session management, AuthenticationManager, UserDetailsService"
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
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                message = "Username can only contain letters, numbers, underscores, and hyphens")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase, one lowercase, and one digit")
        String password
) {}
```

Create `backend/src/main/java/com/blogplatform/auth/dto/LoginRequest.java`:
```java
package com.blogplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 128, message = "Password must not exceed 128 characters")
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

### Task 9: LoginAttemptService — Brute-Force Protection

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/LoginAttemptService.java`
- Create: `backend/src/test/java/com/blogplatform/auth/LoginAttemptServiceTest.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/blogplatform/auth/LoginAttemptServiceTest.java`:
```java
package com.blogplatform.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        loginAttemptService = new LoginAttemptService(redisTemplate);
    }

    @Test
    void isBlocked_withNoFailures_returnsFalse() {
        when(valueOps.get("login:failures:testuser")).thenReturn(null);
        assertThat(loginAttemptService.isBlocked("testuser")).isFalse();
    }

    @Test
    void isBlocked_withFourFailures_returnsFalse() {
        when(valueOps.get("login:failures:testuser")).thenReturn("4");
        assertThat(loginAttemptService.isBlocked("testuser")).isFalse();
    }

    @Test
    void isBlocked_withFiveFailures_returnsTrue() {
        when(valueOps.get("login:failures:testuser")).thenReturn("5");
        assertThat(loginAttemptService.isBlocked("testuser")).isTrue();
    }

    @Test
    void recordFailure_incrementsCountWithTTL() {
        when(valueOps.increment("login:failures:testuser")).thenReturn(1L);
        loginAttemptService.recordFailure("testuser");
        verify(redisTemplate).expire("login:failures:testuser", Duration.ofMinutes(15));
    }

    @Test
    void resetFailures_deletesKey() {
        loginAttemptService.resetFailures("testuser");
        verify(redisTemplate).delete("login:failures:testuser");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.LoginAttemptServiceTest" -i`
Expected: FAIL — `LoginAttemptService` does not exist yet.

**Step 3: Write the implementation**

Create `backend/src/main/java/com/blogplatform/auth/LoginAttemptService.java`:
```java
package com.blogplatform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String KEY_PREFIX = "login:failures:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isBlocked(String username) {
        String attempts = redisTemplate.opsForValue().get(KEY_PREFIX + username);
        return attempts != null && Integer.parseInt(attempts) >= MAX_ATTEMPTS;
    }

    public void recordFailure(String username) {
        String key = KEY_PREFIX + username;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, LOCKOUT_DURATION);
        log.warn("Failed login attempt for username={}", username);
    }

    public void resetFailures(String username) {
        redisTemplate.delete(KEY_PREFIX + username);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.LoginAttemptServiceTest" -i`
Expected: All 5 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/LoginAttemptService.java backend/src/test/java/com/blogplatform/auth/LoginAttemptServiceTest.java
git commit -m "feat: add LoginAttemptService with Redis-backed brute-force protection"
```

---

### Task 10: AuthService — Registration and Login Logic

**Files:**
- Create: `backend/src/main/java/com/blogplatform/auth/AuthService.java`
- Create: `backend/src/test/java/com/blogplatform/auth/AuthServiceTest.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/blogplatform/auth/AuthServiceTest.java`:
```java
package com.blogplatform.auth;

import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.ResourceNotFoundException;
import com.blogplatform.common.exception.UnauthorizedException;
import com.blogplatform.user.Role;
import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock
    private LoginAttemptService loginAttemptService;

    private AuthService authService;

    private static final String DUMMY_HASH = "$2a$12$dummyhashfortimingequalitypadding000000000000000000000";

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, loginAttemptService);
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
    void register_normalizesEmailToLowercase() {
        var request = new RegisterRequest("testuser", "Test@Example.COM", "Password1");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1")).thenReturn("hashed_password");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserAccount result = authService.register(request);

        assertThat(result.getEmail()).isEqualTo("test@example.com");
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
        when(loginAttemptService.isBlocked("testuser")).thenReturn(false);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed")).thenReturn(true);

        UserAccount result = authService.authenticate("testuser", "Password1");

        assertThat(result.getUsername()).isEqualTo("testuser");
        verify(loginAttemptService).resetFailures("testuser");
    }

    @Test
    void authenticate_withBlockedAccount_throwsLockedException() {
        when(loginAttemptService.isBlocked("testuser")).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("testuser", "Password1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Account temporarily locked");
    }

    @Test
    void authenticate_withWrongPassword_recordsFailureAndThrowsUnauthorized() {
        var user = new UserAccount();
        user.setPasswordHash("hashed");
        when(loginAttemptService.isBlocked("testuser")).thenReturn(false);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("testuser", "wrong"))
                .isInstanceOf(UnauthorizedException.class);
        verify(loginAttemptService).recordFailure("testuser");
    }

    @Test
    void authenticate_withNonexistentUser_performsDummyHashAndThrowsUnauthorized() {
        when(loginAttemptService.isBlocked("nobody")).thenReturn(false);
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate("nobody", "Password1"))
                .isInstanceOf(UnauthorizedException.class);

        // Verify dummy hash comparison was performed (timing side-channel mitigation)
        verify(passwordEncoder).matches(eq("Password1"), anyString());
        verify(loginAttemptService).recordFailure("nobody");
    }

    @Test
    void findById_withExistingUser_returnsUser() {
        var user = new UserAccount();
        user.setAccountId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserAccount result = authService.findById(1L);

        assertThat(result.getAccountId()).isEqualTo(1L);
    }

    @Test
    void findById_withNonexistentUser_throwsResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
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
import com.blogplatform.common.exception.ResourceNotFoundException;
import com.blogplatform.common.exception.UnauthorizedException;
import com.blogplatform.user.Role;
import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Pre-computed BCrypt hash used for constant-time comparison when user is not found,
    // mitigating timing side-channel that could reveal username existence.
    private static final String DUMMY_HASH =
            "$2a$12$dummyhashfortimingequalitypadding000000000000000000000";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public UserAccount register(RegisterRequest request) {
        String normalizedEmail = request.email().toLowerCase();

        if (userRepository.existsByUsername(request.username())) {
            throw new BadRequestException("Username already taken");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BadRequestException("Email already registered");
        }

        UserAccount account = new UserAccount();
        account.setUsername(request.username());
        account.setEmail(normalizedEmail);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(Role.USER);

        UserProfile profile = new UserProfile();
        profile.setUserAccount(account);
        account.setUserProfile(profile);

        return userRepository.save(account);
    }

    public UserAccount authenticate(String username, String password) {
        if (loginAttemptService.isBlocked(username)) {
            log.warn("Login attempt for locked account: username={}", username);
            throw new BadRequestException("Account temporarily locked due to too many failed attempts. Try again later.");
        }

        UserAccount user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            // Perform dummy hash comparison to prevent timing side-channel
            passwordEncoder.matches(password, DUMMY_HASH);
            loginAttemptService.recordFailure(username);
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptService.recordFailure(username);
            throw new UnauthorizedException("Invalid credentials");
        }

        loginAttemptService.resetFailures(username);
        return user;
    }

    public UserAccount findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthServiceTest" -i`
Expected: All 10 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/AuthService.java backend/src/test/java/com/blogplatform/auth/AuthServiceTest.java
git commit -m "feat: add AuthService with register, authenticate, findById, brute-force protection, timing-safe lookup"
```

---

### Task 11: AuthController — Register, Login, Logout, /me Endpoints

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void register_withValidData_returns201() throws Exception {
        var request = new RegisterRequest("newuser", "new@example.com", "Password1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
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
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_withDuplicateUsername_returns400() throws Exception {
        var request = new RegisterRequest("dupuser", "dup1@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        var duplicate = new RegisterRequest("dupuser", "dup2@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Username already taken"));
    }

    @Test
    void login_afterRegister_returns200WithUserInfo() throws Exception {
        var register = new RegisterRequest("loginuser", "login@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(register)));

        var login = new LoginRequest("loginuser", "Password1");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
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
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;

    public AuthController(AuthService authService,
                          AuthenticationManager authenticationManager,
                          UserRepository userRepository) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toAuthResponse(user), "Registration successful"));
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // AuthenticationManager delegates to CustomUserDetailsService + DaoAuthenticationProvider.
        // This triggers session fixation protection (.newSession()) automatically.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        httpRequest.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // Extract user ID from authentication principal (set as username in UserDetailsService)
        Long userId = Long.valueOf(authentication.getName());
        UserAccount user = authService.findById(userId);

        // Update login tracking
        UserProfile profile = user.getUserProfile();
        if (profile != null) {
            profile.setLastLogin(Instant.now());
            profile.setLoginCount(profile.getLoginCount() + 1);
            userRepository.save(user);
        }

        return ResponseEntity.ok(ApiResponse.success(toAuthResponse(user), "Login successful"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthResponse>> me(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
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

Note: The `AuthenticationManager` bean from `SecurityConfig` delegates to `CustomUserDetailsService` via Spring Security's `DaoAuthenticationProvider`. The `authenticate()` call handles:
- Password verification via BCrypt
- Session fixation protection (new session created automatically)
- `AuthenticationSuccessEvent` / `AuthenticationFailureBadCredentialsEvent` publishing

However, because we also need brute-force protection (lockout checks + failure recording), the `AuthService.authenticate()` method is still called for the lockout pre-check. The login flow is:

1. `AuthService` checks if account is locked (via `LoginAttemptService`)
2. `AuthenticationManager.authenticate()` handles credential verification
3. On success: `LoginAttemptService.resetFailures()`
4. On failure: `LoginAttemptService.recordFailure()`

Update `AuthController.login()` to integrate both:

```java
@PostMapping("/login")
@Transactional
public ResponseEntity<ApiResponse<AuthResponse>> login(
        @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

    // Pre-check: is the account locked?
    // This delegates to AuthService which checks LoginAttemptService
    UserAccount user = authService.authenticate(request.username(), request.password());

    // Now establish the Spring Security session via AuthenticationManager.
    // Since AuthService already verified credentials, this will succeed.
    // This triggers session fixation protection and event publishing.
    Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password())
    );

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    httpRequest.getSession(true).setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

    // Update login tracking
    UserProfile profile = user.getUserProfile();
    if (profile != null) {
        profile.setLastLogin(Instant.now());
        profile.setLoginCount(profile.getLoginCount() + 1);
        userRepository.save(user);
    }

    return ResponseEntity.ok(ApiResponse.success(toAuthResponse(user), "Login successful"));
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.blogplatform.auth.AuthControllerTest" -i`
Expected: All 5 tests PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/blogplatform/auth/ backend/src/test/java/com/blogplatform/auth/
git commit -m "feat: add AuthController with register, login, logout, /me endpoints, login tracking"
```

---

> **Next:** Continue to Phase 1C for Tasks 12-16.
