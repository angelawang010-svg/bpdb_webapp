package com.blogplatform.auth;

import com.blogplatform.auth.dto.AuthResponse;
import com.blogplatform.auth.dto.LoginRequest;
import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.dto.ApiResponse;
import com.blogplatform.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;

    public AuthController(AuthService authService,
                          AuthenticationManager authenticationManager) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toAuthResponse(user), "Registration successful"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        // 1. Pre-check: is the account locked? Throws 423 if so.
        authService.checkLockout(request.username());

        // 2. Authenticate via Spring Security's AuthenticationManager
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (BadCredentialsException ex) {
            // 3a. On failure: record attempt with IP
            authService.recordLoginFailure(request.username(), httpRequest.getRemoteAddr());
            throw new com.blogplatform.common.exception.UnauthorizedException("Invalid credentials");
        }

        // 3b. On success: store SecurityContext in session
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        httpRequest.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // 4. Record success: reset lockout counter, update login tracking
        Long userId = Long.valueOf(authentication.getName());
        UserAccount user = authService.recordLoginSuccess(userId, request.username());

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
