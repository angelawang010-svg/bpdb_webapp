package com.blogplatform.auth;

import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.exception.AccountLockedException;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.ResourceNotFoundException;
import com.blogplatform.user.Role;
import com.blogplatform.user.UserAccount;
import com.blogplatform.user.UserProfile;
import com.blogplatform.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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

    public void checkLockout(String username) {
        if (loginAttemptService.isBlocked(username)) {
            log.warn("Login attempt for locked account: username={}", username);
            throw new AccountLockedException(
                    "Account temporarily locked due to too many failed attempts. Try again later.");
        }
    }

    public void recordLoginFailure(String username, String ipAddress) {
        loginAttemptService.recordFailure(username, ipAddress);
    }

    @Transactional
    public UserAccount recordLoginSuccess(Long userId, String username) {
        loginAttemptService.resetFailures(username);

        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UserProfile profile = user.getUserProfile();
        if (profile != null) {
            profile.setLastLogin(Instant.now());
            profile.setLoginCount(profile.getLoginCount() + 1);
        }

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserAccount findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
