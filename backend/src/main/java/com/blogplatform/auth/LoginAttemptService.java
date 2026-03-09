package com.blogplatform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String KEY_PREFIX = "login:failures:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1]); " +
            "redis.call('EXPIRE', KEYS[1], ARGV[1]); " +
            "return count",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isBlocked(String username) {
        String attempts = redisTemplate.opsForValue().get(KEY_PREFIX + username);
        return attempts != null && Integer.parseInt(attempts) >= MAX_ATTEMPTS;
    }

    public void recordFailure(String username, String ipAddress) {
        String key = KEY_PREFIX + username;
        redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key),
                String.valueOf(LOCKOUT_DURATION.getSeconds()));
        log.warn("Failed login attempt for username={}, ip={}", username, ipAddress);
    }

    public void resetFailures(String username) {
        redisTemplate.delete(KEY_PREFIX + username);
    }
}
