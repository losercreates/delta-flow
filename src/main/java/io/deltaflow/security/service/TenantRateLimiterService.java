package io.deltaflow.security.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed Multi-Tenant Token-Bucket Rate Limiter.
 * Uses Redisson RRateLimiter to enforce rate limits per tenantId across horizontal cluster instances.
 */
@Service
public class TenantRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiterService.class);

    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, Boolean> initializedTenants = new ConcurrentHashMap<>();

    @Value("${deltaflow.security.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${deltaflow.security.rate-limit.default-rate:100}")
    private long defaultRate;

    @Value("${deltaflow.security.rate-limit.default-burst:200}")
    private long defaultBurst;

    public TenantRateLimiterService(@Autowired(required = false) RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public record RateLimitResult(
            boolean allowed,
            long limit,
            long remaining,
            long resetMs
    ) {}

    public RateLimitResult tryConsume(String tenantId, int tokens) {
        if (!enabled || redissonClient == null) {
            return new RateLimitResult(true, defaultBurst, defaultBurst, 0);
        }

        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        String limiterKey = "deltaflow:ratelimit:tenant:" + safeTenant;

        try {
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);

            // Lazy initialization of rate limiter if not already configured in Redis
            initializedTenants.computeIfAbsent(safeTenant, k -> {
                rateLimiter.trySetRate(RateType.OVERALL, defaultBurst, 1, RateIntervalUnit.SECONDS);
                rateLimiter.expire(Duration.ofHours(24));
                return true;
            });

            boolean acquired = rateLimiter.tryAcquire(tokens);
            long available = rateLimiter.availablePermits();

            return new RateLimitResult(
                    acquired,
                    defaultBurst,
                    available,
                    acquired ? 0 : 1000
            );
        } catch (Exception ex) {
            log.warn("Rate limiter evaluation failed for tenant [{}]: {}. Failing-open.", safeTenant, ex.getMessage());
            // Fail open to preserve payment processing resilience
            return new RateLimitResult(true, defaultBurst, defaultBurst, 0);
        }
    }

    public long getDefaultBurst() {
        return defaultBurst;
    }
}
