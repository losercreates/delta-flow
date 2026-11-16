package io.deltaflow.observability.health;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("customRedisLockHealthIndicator")
public class RedisLockHealthIndicator implements HealthIndicator {

    private final RedissonClient redissonClient;

    public RedisLockHealthIndicator(@Autowired(required = false) RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public Health health() {
        if (redissonClient == null) {
            return Health.unknown().withDetail("redis", "RedissonClient bean not available").build();
        }
        if (redissonClient.isShutdown() || redissonClient.isShuttingDown()) {
            return Health.down().withDetail("redis", "Redisson client is shutting down").build();
        }

        long start = System.currentTimeMillis();
        try {
            redissonClient.getBucket("deltaflow:health-probe").isExists();
            long latencyMs = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("redis", "CONNECTED")
                    .withDetail("latencyMs", latencyMs)
                    .build();
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            return Health.down(e)
                    .withDetail("redis", "UNAVAILABLE")
                    .withDetail("latencyMs", latencyMs)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
