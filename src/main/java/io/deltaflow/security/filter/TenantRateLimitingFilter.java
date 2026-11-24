package io.deltaflow.security.filter;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.security.service.TenantRateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class TenantRateLimitingFilter extends OncePerRequestFilter {

    private final TenantRateLimiterService rateLimiterService;
    private final AuditLogger auditLogger;

    public TenantRateLimitingFilter(TenantRateLimiterService rateLimiterService, AuditLogger auditLogger) {
        this.rateLimiterService = rateLimiterService;
        this.auditLogger = auditLogger;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Skip rate limiting on public health/prometheus and login endpoints
        if (path.startsWith("/actuator") || path.equals("/api/v1/auth/login") || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = (String) request.getAttribute("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getHeader("X-Tenant-ID");
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        TenantRateLimiterService.RateLimitResult result = rateLimiterService.tryConsume(tenantId, 1);

        // Standard RFC rate limit response headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, result.remaining())));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetMs()));

        if (!result.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");

            Map<String, Object> auditDetails = new HashMap<>();
            auditDetails.put("tenantId", tenantId);
            auditDetails.put("clientIp", request.getRemoteAddr());
            auditDetails.put("uri", path);
            auditLogger.logEvent("TENANT_RATE_LIMITED", "system", "rate-limiter", auditDetails);

            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for tenant: " + tenantId + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
