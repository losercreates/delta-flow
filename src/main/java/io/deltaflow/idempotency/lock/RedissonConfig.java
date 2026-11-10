package io.deltaflow.idempotency.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${deltaflow.redis.address:#{null}}")
    private String deltaflowRedisAddress;

    @Value("${spring.data.redis.url:#{null}}")
    private String springDataRedisUrl;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient() {
        String address = resolveAddress();
        log.info("Configuring RedissonClient with address: {}", address);

        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        return Redisson.create(config);
    }

    @Bean
    @Primary
    public DistributedLockManager distributedLockManager(RedissonClient redissonClient) {
        return new RedisDistributedLock(redissonClient);
    }

    private String resolveAddress() {
        if (deltaflowRedisAddress != null && !deltaflowRedisAddress.isBlank()) {
            return ensureRedisPrefix(deltaflowRedisAddress);
        }
        if (springDataRedisUrl != null && !springDataRedisUrl.isBlank()) {
            return ensureRedisPrefix(springDataRedisUrl);
        }
        return ensureRedisPrefix(redisHost + ":" + redisPort);
    }

    private String ensureRedisPrefix(String address) {
        if (address.startsWith("redis://") || address.startsWith("rediss://")) {
            return address;
        }
        return "redis://" + address;
    }
}

