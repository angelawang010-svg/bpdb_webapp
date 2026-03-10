package com.blogplatform.config;

import com.blogplatform.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting filter with tiered limits:
 * - Auth endpoints: 10 req/min per IP
 * - Authenticated users: 120 req/min per username
 * - Anonymous: 60 req/min per IP
 *
 * NOTE: This is a per-JVM rate limiter. For multi-instance deployments,
 * replace with Bucket4j's Redis-backed ProxyManager.
 *
 * NOTE: IP resolution uses request.getRemoteAddr(). For production behind
 * a reverse proxy, configure server.forward-headers-strategy=NATIVE in
 * application.properties so Spring resolves the real client IP.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    private final ObjectMapper objectMapper;

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key;
        Bucket bucket;

        boolean isAuthEndpoint = request.getRequestURI().startsWith("/api/v1/auth/");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());

        if (isAuthEndpoint) {
            key = "auth:" + request.getRemoteAddr();
            bucket = buckets.get(key, k -> createBucket(10));
        } else if (isAuthenticated) {
            key = "user:" + auth.getName();
            bucket = buckets.get(key, k -> createBucket(120));
        } else {
            key = "anon:" + request.getRemoteAddr();
            bucket = buckets.get(key, k -> createBucket(60));
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(Math.max(1, waitSeconds)));
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error("Rate limit exceeded. Try again in " + waitSeconds + " seconds."));
        }
    }

    private Bucket createBucket(int capacityPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacityPerMinute, Duration.ofMinutes(1)))
                .build();
    }
}
