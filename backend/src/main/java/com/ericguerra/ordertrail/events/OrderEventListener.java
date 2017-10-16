package com.ericguerra.ordertrail.events;

import com.ericguerra.ordertrail.api.LiveUpdates;
import com.ericguerra.ordertrail.api.OrderDto;
import com.ericguerra.ordertrail.projection.OrderStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumes order-events. Processing is idempotent (OrderStore dedupes by event id and version), so
 * at-least-once delivery and consumer rebalances are safe. Poison messages (undeserializable or
 * failing after retries) go to order-events.DLT instead of blocking the partition.
 */
@Component
@ConditionalOnProperty(name = "ordertrail.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventListener {
    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private final OrderStore store;
    private final LiveUpdates live;
    private final ObjectMapper json;

    public OrderEventListener(OrderStore store, LiveUpdates live, ObjectMapper json) {
        this.store = store;
        this.live = live;
        this.json = json;
    }

    @KafkaListener(topics = "order-events", groupId = "ordertrail", concurrency = "3")
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        OrderEvent event = json.readValue(record.value(), OrderEvent.class);
        OrderStore.Outcome outcome = store.accept(event);
        if (outcome == OrderStore.Outcome.BUFFERED) log.info("buffered {} v{} (waiting for earlier version)", event.orderNo(), event.version());
        if (outcome == OrderStore.Outcome.APPLIED) store.load(event.orderNo()).ifPresent(v -> live.publish(v.orderNo, OrderDto.from(v)));
    }

    @Configuration
    @ConditionalOnProperty(name = "ordertrail.kafka.enabled", havingValue = "true", matchIfMissing = true)
    static class ErrorHandling {
        @Bean
        DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
            ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
            backOff.setMaxElapsedTime(10_000);
            DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
            handler.addNotRetryableExceptions(com.fasterxml.jackson.core.JsonProcessingException.class);
            return handler;
        }
    }
}
