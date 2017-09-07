package com.ericguerra.ordertrail.api;

import com.ericguerra.ordertrail.projection.OrderView;

import java.time.Instant;
import java.util.List;

/** Customer-facing shape: no internal ids or customer PII beyond what the customer already knows. */
public record OrderDto(String orderNo, String status, String channel, long totalCents, Instant placedAt, Instant updatedAt,
                       List<LineDto> lines, List<OrderView.TimelineEntry> timeline) {

    public record LineDto(String lineId, String name, int qty, long unitPriceCents, String status, String fulfillment,
                          String storeId, String carrier, String trackingNumber, Instant estimatedDelivery) {}

    public static OrderDto from(OrderView v) {
        return new OrderDto(v.orderNo, v.status().name(), v.channel, v.totalCents, v.placedAt, v.updatedAt,
                v.lines.values().stream().map(l -> new LineDto(l.lineId, l.name, l.qty, l.unitPriceCents, l.status.name(), l.fulfillment,
                        l.storeId, l.carrier, l.trackingNumber, l.estimatedDelivery)).toList(),
                List.copyOf(v.timeline).reversed());
    }
}
