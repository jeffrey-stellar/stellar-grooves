package com.stellarideas.grooves.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * Rate limiter for public endpoints (auth and shared).
 * Delegates counting to a {@link RateLimitStore} — in-memory by default,
 * Redis-backed when Spring Data Redis is on the classpath.
 *
 * <p>Auth and shared endpoints use separate rate-limit buckets so that
 * abuse of one path does not block the other. Shared endpoints default
 * to a stricter limit (5 req / 60 s) because they are unauthenticated
 * and expose user content.</p>
 *
 * <p>Only trusts X-Forwarded-For when {@code stellar.grooves.rateLimit.trustProxy}
 * is {@code true} (default: false). This prevents IP spoofing when not behind a reverse proxy.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${stellar.grooves.rateLimit.maxRequests:10}")
    private int maxRequests;

    @Value("${stellar.grooves.rateLimit.windowMs:60000}")
    private long windowMs;

    @Value("${stellar.grooves.rateLimit.shared.maxRequests:5}")
    private int sharedMaxRequests;

    @Value("${stellar.grooves.rateLimit.shared.windowMs:60000}")
    private long sharedWindowMs;

    @Value("${stellar.grooves.rateLimit.trustProxy:false}")
    private boolean trustProxy;

    @Value("${stellar.grooves.rateLimit.trustedProxies:}")
    private List<String> trustedProxies;

    private final RateLimitStore store;
    private final Counter rateLimitTriggeredCounter;

    public RateLimitFilter(RateLimitStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.rateLimitTriggeredCounter = Counter.builder("grooves.ratelimit.triggered")
                .description("Number of rate-limited requests")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/auth/") && !path.startsWith("/api/v1/shared/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ip = getClientIp(request);
        String path = request.getRequestURI();
        boolean isShared = path.startsWith("/api/v1/shared/");

        // Use separate bucket keys so auth and shared limits are independent
        String bucketKey = isShared ? "shared:" + ip : "auth:" + ip;
        int limit = isShared ? sharedMaxRequests : maxRequests;
        long window = isShared ? sharedWindowMs : windowMs;

        int count = store.incrementAndGet(bucketKey, window);

        if (count > limit) {
            rateLimitTriggeredCounter.increment();
            long retryAfterSeconds = store.secondsUntilReset(bucketKey, window);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\",\"retryAfter\":" + retryAfterSeconds + "}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    String getClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String remoteAddr = request.getRemoteAddr();
            if (!trustedProxies.isEmpty() && trustedProxies.contains(remoteAddr)) {
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    // Walk from the rightmost IP leftward, skipping trusted proxies.
                    // The rightmost non-proxy IP is the real client because proxies
                    // append (not prepend), so leftmost entries are attacker-controlled.
                    String[] ips = forwarded.split(",");
                    for (int i = ips.length - 1; i >= 0; i--) {
                        String candidate = ips[i].trim();
                        if (!isValidIp(candidate)) {
                            continue;
                        }
                        if (!trustedProxies.contains(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45) {
            return false;
        }
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
