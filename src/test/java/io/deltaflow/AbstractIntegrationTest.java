package io.deltaflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final PostgreSQLContainer<?> postgres;
    public static final GenericContainer<?> redis;
    public static final boolean DOCKER_AVAILABLE;

    static {
        System.setProperty("api.version", "1.44");
        System.setProperty("docker.api.version", "1.44");
        System.setProperty("docker.io.api.version", "1.44");
        System.setProperty("DOCKER_API_VERSION", "1.44");

        PostgreSQLContainer<?> pg = null;
        GenericContainer<?> rd = null;
        boolean available = false;
        try {
            pg = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("deltaflow_test")
                    .withUsername("test")
                    .withPassword("test");
            pg.start();

            rd = new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);
            rd.start();
            available = true;
        } catch (Throwable t) {
            System.err.println("WARN: Docker is not available in environment. Testcontainers integration tests will be skipped: " + t.getMessage());
        }
        postgres = pg;
        redis = rd;
        DOCKER_AVAILABLE = available;
    }

    @org.junit.jupiter.api.BeforeAll
    static void checkDockerAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker is not available. Skipping Testcontainers integration test.");
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DOCKER_AVAILABLE && postgres != null && redis != null) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", redis::getFirstMappedPort);
            registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
            registry.add("spring.redis.host", redis::getHost);
            registry.add("spring.redis.port", redis::getFirstMappedPort);
            registry.add("spring.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
            registry.add("deltaflow.redis.address", () -> "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
        }
    }
}
