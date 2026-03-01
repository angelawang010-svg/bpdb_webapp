# Phase 1B: Auth System — Implementation Plan

(Part 2 of 3 — Tasks 6-10 of 15)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Spring Boot project skeleton with PostgreSQL, Redis, Flyway migrations, JPA entities, Spring Security session-based auth, rate limiting, and auth endpoints — producing a running, tested foundation for the full blog platform.

**Architecture:** Monorepo with `backend/` (Spring Boot 3.x, Java 21, Gradle) and `frontend/` (later). PostgreSQL 16 + Redis 7 via Docker Compose. Session-based auth with Redis-backed sessions. Bucket4j rate limiting. All endpoints under `/api/v1/`.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL 16, Redis 7, Spring Data JPA, Flyway, Spring Security, Spring Session Data Redis, Bucket4j, Testcontainers, JUnit 5, Mockito

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — the authoritative source for all schema, API, security, and business logic decisions.

## Phase 1 Parts

- **Phase 1A: Project Setup & Infrastructure** — Tasks 1-5 (`2026-03-01-phase1a-project-setup-implementation.md`)
- **Phase 1B: Auth System** — Tasks 6-10 (`2026-03-01-phase1b-auth-security-implementation.md`)
- **Phase 1C: Rate Limiting, Entities & Verification** — Tasks 11-15 (`2026-03-01-phase1c-ratelimit-entities-implementation.md`)

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

> **Next:** Continue to Phase 1C for Tasks 11-15.
