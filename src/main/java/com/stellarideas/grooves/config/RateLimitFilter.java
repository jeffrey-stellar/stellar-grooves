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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Simple in-memory rate limiter for auth endpoints.
 * Tracks requests per IP within a sliding time window.
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

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        evictStaleEntries(now);

        WindowCounter counter = counters.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new WindowCounter(now);
            }
            return existing;
        });

        int count = counter.count.incrementAndGet();

        if (count > maxRequests) {
            long elapsed = now - counter.windowStart;
            long retryAfterSeconds = Math.max(1, (windowMs - elapsed) / 1000);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\",\"retryAfter\":" + retryAfterSeconds + "}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String remoteAddr = request.getRemoteAddr();
            if (trustedProxies.isEmpty() || trustedProxies.contains(remoteAddr)) {
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    // Walk from the rightmost IP leftward, skipping trusted proxies.
                    // The rightmost non-proxy IP is the real client because proxies
                    // append (not prepend), so leftmost entries are attacker-controlled.
                    String[] ips = forwarded.split(",");
                    for (int i = ips.length - 1; i >= 0; i--) {
                        String ip = ips[i].trim();
                        if (!isValidIp(ip)) {
                            continue;
                        }
                        if (!trustedProxies.contains(ip)) {
                            return ip;
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

    private void evictStaleEntries(long now) {
        Iterator<Map.Entry<String, WindowCounter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WindowCounter> entry = it.next();
            if (now - entry.getValue().windowStart > windowMs * 2) {
                it.remove();
            }
        }
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
