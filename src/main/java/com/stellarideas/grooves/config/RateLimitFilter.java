package com.stellarideas.grooves.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rate limiter for auth endpoints.
 * Delegates counting to a {@link RateLimitStore} — in-memory by default,
 * Redis-backed when Spring Data Redis is on the classpath.
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

    @Value("${stellar.grooves.rateLimit.trustProxy:false}")
    private boolean trustProxy;

    @Value("${stellar.grooves.rateLimit.trustedProxies:}")
    private List<String> trustedProxies;

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^[0-9a-fA-F.:]{1,45}$");

    private final RateLimitStore store;

    public RateLimitFilter(RateLimitStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ip = getClientIp(request);
        int count = store.incrementAndGet(ip, windowMs);

        if (count > maxRequests) {
            long retryAfterSeconds = store.secondsUntilReset(ip, windowMs);
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
        return ip != null && !ip.isEmpty() && IP_PATTERN.matcher(ip).matches();
    }
}
