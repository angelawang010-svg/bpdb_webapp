package com.blogplatform.auth;

import com.blogplatform.auth.dto.RegisterRequest;
import com.blogplatform.common.exception.AccountLockedException;
import com.blogplatform.common.exception.BadRequestException;
import com.blogplatform.common.exception.ResourceNotFoundException;
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
    void checkLockout_withBlockedAccount_throwsAccountLockedException() {
        when(loginAttemptService.isBlocked("testuser")).thenReturn(true);

        assertThatThrownBy(() -> authService.checkLockout("testuser"))
                .isInstanceOf(AccountLockedException.class)
                .hasMessageContaining("Account temporarily locked");
    }

    @Test
    void checkLockout_withUnblockedAccount_doesNotThrow() {
        when(loginAttemptService.isBlocked("testuser")).thenReturn(false);

        authService.checkLockout("testuser"); // should not throw
    }

    @Test
    void recordLoginFailure_delegatesToLoginAttemptService() {
        authService.recordLoginFailure("testuser", "192.168.1.1");

        verify(loginAttemptService).recordFailure("testuser", "192.168.1.1");
    }

    @Test
    void recordLoginSuccess_resetsFailuresAndUpdatesProfile() {
        var user = new UserAccount();
        user.setAccountId(1L);
        var profile = new UserProfile();
        profile.setLoginCount(3);
        profile.setUserAccount(user);
        user.setUserProfile(profile);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserAccount result = authService.recordLoginSuccess(1L, "testuser");

        verify(loginAttemptService).resetFailures("testuser");
        assertThat(result.getUserProfile().getLoginCount()).isEqualTo(4);
        assertThat(result.getUserProfile().getLastLogin()).isNotNull();
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
