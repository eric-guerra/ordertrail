package com.ericguerra.ordertrail.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * Events from every channel (web, mobile, store POS, call center, warehouse, carriers) on the
 * `order-events` topic, keyed by order number so all events for an order land on one partition
 * in order. `version` is the producer's per-order sequence (1, 2, 3…) used to detect gaps.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OrderEvent.Placed.class, name = "ORDER_PLACED"),
        @JsonSubTypes.Type(value = OrderEvent.PaymentCaptured.class, name = "PAYMENT_CAPTURED"),
        @JsonSubTypes.Type(value = OrderEvent.LinesShipped.class, name = "LINES_SHIPPED"),
        @JsonSubTypes.Type(value = OrderEvent.CarrierScan.class, name = "CARRIER_SCAN"),
        @JsonSubTypes.Type(value = OrderEvent.ReadyForPickup.class, name = "READY_FOR_PICKUP"),
        @JsonSubTypes.Type(value = OrderEvent.PickedUp.class, name = "PICKED_UP"),
        @JsonSubTypes.Type(value = OrderEvent.LinesReturned.class, name = "LINES_RETURNED"),
        @JsonSubTypes.Type(value = OrderEvent.Cancelled.class, name = "ORDER_CANCELLED")
})
public sealed interface OrderEvent {
    String eventId();

    String orderNo();

    long version();

    Instant occurredAt();

    record Line(String lineId, String sku, String name, int qty, long unitPriceCents, String fulfillment, String storeId) {}

    record Placed(String eventId, String orderNo, long version, Instant occurredAt, String channel, String customerId,
                  String email, List<Line> lines, long totalCents) implements OrderEvent {}

    record PaymentCaptured(String eventId, String orderNo, long version, Instant occurredAt, long amountCents) implements OrderEvent {}

    record LinesShipped(String eventId, String orderNo, long version, Instant occurredAt, List<String> lineIds,
                        String carrier, String trackingNumber, Instant estimatedDelivery) implements OrderEvent {}

    /** Carrier status update for a tracking number (IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION). */
    record CarrierScan(String eventId, String orderNo, long version, Instant occurredAt, String trackingNumber,
                       String status, String location) implements OrderEvent {}

    record ReadyForPickup(String eventId, String orderNo, long version, Instant occurredAt, List<String> lineIds, String storeId) implements OrderEvent {}

    record PickedUp(String eventId, String orderNo, long version, Instant occurredAt, List<String> lineIds, String storeId) implements OrderEvent {}

    record LinesReturned(String eventId, String orderNo, long version, Instant occurredAt, List<String> lineIds, String channel) implements OrderEvent {}

    record Cancelled(String eventId, String orderNo, long version, Instant occurredAt, String reason) implements OrderEvent {}
}
