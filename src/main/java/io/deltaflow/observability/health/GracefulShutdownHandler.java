package io.deltaflow.observability.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component("drainingHealthIndicator")
public class GracefulShutdownHandler implements SmartLifecycle, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicLong activeSagaCount = new AtomicLong(0);
    private final long startTimeMillis = System.currentTimeMillis();
    private final ApplicationEventPublisher eventPublisher;

    public GracefulShutdownHandler(@Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public GracefulShutdownHandler() {
        this(null);
    }

    public void registerSagaStart() {
        activeSagaCount.incrementAndGet();
    }

    public void registerSagaEnd() {
        activeSagaCount.updateAndGet(count -> Math.max(0, count - 1));
    }

    public boolean isDraining() {
        return draining.get();
    }

    public long getActiveSagaCount() {
        return activeSagaCount.get();
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000L;
    }

    public void startDraining() {
        if (draining.compareAndSet(false, true)) {
            log.warn("Graceful draining initiated. Node marked OUT_OF_SERVICE for traffic shedding.");
            if (eventPublisher != null) {
                AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
            }
        }
    }

    public void cancelDraining() {
        if (draining.compareAndSet(true, false)) {
            if (eventPublisher != null) {
                AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
            }
        }
    }

    public boolean drain(Duration timeout) {
        startDraining();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (activeSagaCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return activeSagaCount.get() == 0;
    }

    @Override
    public Health health() {
        if (draining.get()) {
            return Health.outOfService()
                    .withDetail("draining", true)
                    .withDetail("activeSagas", activeSagaCount.get())
                    .build();
        }
        return Health.up()
                .withDetail("draining", false)
                .withDetail("activeSagas", activeSagaCount.get())
                .build();
    }

    @Override
    public void start() {
        isRunning.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Application context stopping. Draining active sagas within 30s timeout...");
        boolean clean = drain(Duration.ofSeconds(30));
        log.info("Draining finished. Active sagas remaining: {}, clean: {}", activeSagaCount.get(), clean);
        isRunning.set(false);
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public int getPhase() {
        // High phase number to ensure it stops before Database and Redis datasources stop
        return Integer.MAX_VALUE - 100;
    }
}
