package io.deltaflow.outbox.service;

import io.deltaflow.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Strategy interface for broadcasting committed outbox events to downstream destinations.
 */
public interface DomainEventPublisher {

    void publish(OutboxEvent event);

    @Component
    class DefaultDomainEventPublisher implements DomainEventPublisher {
        private static final Logger log = LoggerFactory.getLogger(DefaultDomainEventPublisher.class);
        private final ApplicationEventPublisher applicationEventPublisher;
        private final List<OutboxEvent> publishedEvents = new CopyOnWriteArrayList<>();
        private Consumer<OutboxEvent> interceptor;

        public DefaultDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
            this.applicationEventPublisher = applicationEventPublisher;
        }

        @Override
        public void publish(OutboxEvent event) {
            if (interceptor != null) {
                interceptor.accept(event);
            }
            log.info("Publishing domain event [id: {}, type: {}, aggregate: {}:{}]",
                    event.getEventId(), event.getEventType(), event.getAggregateType(), event.getAggregateId());
            publishedEvents.add(event);
            applicationEventPublisher.publishEvent(event);
        }

        public void setInterceptor(Consumer<OutboxEvent> interceptor) {
            this.interceptor = interceptor;
        }

        public List<OutboxEvent> getPublishedEvents() {
            return publishedEvents;
        }

        public void clear() {
            publishedEvents.clear();
            this.interceptor = null;
        }
    }
}
