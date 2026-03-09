package com.blogplatform.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
    void recordFailure_executesAtomicLuaScript() {
        loginAttemptService.recordFailure("testuser", "192.168.1.1");
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("login:failures:testuser")), eq("900"));
    }

    @Test
    void resetFailures_deletesKey() {
        loginAttemptService.resetFailures("testuser");
        verify(redisTemplate).delete("login:failures:testuser");
    }
}
