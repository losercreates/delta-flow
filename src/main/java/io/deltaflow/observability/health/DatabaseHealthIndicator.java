package io.deltaflow.observability.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("customDatabaseHealthIndicator")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = System.currentTimeMillis() - start;
            if (result != null && result == 1) {
                return Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("latencyMs", latencyMs)
                        .withDetail("status", "AVAILABLE")
                        .build();
            } else {
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", "Unexpected query result")
                        .build();
            }
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            return Health.down(e)
                    .withDetail("database", "PostgreSQL")
                    .withDetail("latencyMs", latencyMs)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
