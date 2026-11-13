package io.deltaflow.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public class ResiliencePolicyRegistry {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public ResiliencePolicyRegistry(CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = Objects.requireNonNull(circuitBreakerRegistry, "circuitBreakerRegistry must not be null");
        this.retryRegistry = Objects.requireNonNull(retryRegistry, "retryRegistry must not be null");
    }

    public CircuitBreaker getOrCreateCircuitBreaker(String name) {
        return circuitBreakerRegistry.circuitBreaker(name);
    }

    public CircuitBreaker getOrCreateCircuitBreaker(String name, CircuitBreakerConfig config) {
        return circuitBreakerRegistry.circuitBreaker(name, config);
    }

    public Retry getOrCreateRetry(String name) {
        return retryRegistry.retry(name);
    }

    public Retry getOrCreateRetry(String name, RetryConfig config) {
        return retryRegistry.retry(name, config);
    }

    public <T> Supplier<T> decorateSupplier(String circuitBreakerName, String retryName, Supplier<T> supplier) {
        Supplier<T> decorated = supplier;
        if (circuitBreakerName != null && !circuitBreakerName.isBlank()) {
            CircuitBreaker cb = getOrCreateCircuitBreaker(circuitBreakerName);
            decorated = CircuitBreaker.decorateSupplier(cb, decorated);
        }
        if (retryName != null && !retryName.isBlank()) {
            Retry retry = getOrCreateRetry(retryName);
            decorated = Retry.decorateSupplier(retry, decorated);
        }
        return decorated;
    }

    public CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public RetryRegistry getRetryRegistry() {
        return retryRegistry;
    }
}

