package com.ericguerra.ordertrail.projection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read model for one order, rebuilt incrementally from events. Mutable on purpose (applied in place). */
public final class OrderView {
    public enum LineStatus { ORDERED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, READY_FOR_PICKUP, PICKED_UP, RETURNED, CANCELLED, DELIVERY_EXCEPTION }

    public enum OrderStatus { PLACED, PROCESSING, PARTIALLY_SHIPPED, SHIPPED, PARTIALLY_DELIVERED, DELIVERED, READY_FOR_PICKUP, COMPLETED, PARTIALLY_RETURNED, RETURNED, CANCELLED, ATTENTION }

    public static final class Line {
        public String lineId, sku, name, fulfillment, storeId, carrier, trackingNumber;
        public int qty;
        public long unitPriceCents;
        public LineStatus status = LineStatus.ORDERED;
        public Instant estimatedDelivery;
    }

    public record TimelineEntry(Instant at, String type, String message) {}

    public String orderNo, channel, customerId, email;
    public long totalCents, capturedCents, version;
    public Instant placedAt, updatedAt;
    public boolean cancelled;
    public final Map<String, Line> lines = new LinkedHashMap<>();
    public final List<TimelineEntry> timeline = new ArrayList<>();

    /** Order-level status derived from line statuses (what the customer sees as the headline). */
    public OrderStatus status() {
        if (cancelled) return OrderStatus.CANCELLED;
        if (lines.isEmpty()) return OrderStatus.PLACED;
        long total = lines.size();
        long exception = count(LineStatus.DELIVERY_EXCEPTION);
        long returned = count(LineStatus.RETURNED);
        long delivered = count(LineStatus.DELIVERED) + count(LineStatus.PICKED_UP);
        long shippedOrBeyond = delivered + count(LineStatus.SHIPPED) + count(LineStatus.OUT_FOR_DELIVERY) + exception;
        long ready = count(LineStatus.READY_FOR_PICKUP);

        if (exception > 0) return OrderStatus.ATTENTION;
        if (returned == total) return OrderStatus.RETURNED;
        if (returned > 0) return OrderStatus.PARTIALLY_RETURNED;
        if (delivered == total) return lines.values().stream().anyMatch(l -> l.status == LineStatus.PICKED_UP) ? OrderStatus.COMPLETED : OrderStatus.DELIVERED;
        if (delivered > 0) return OrderStatus.PARTIALLY_DELIVERED;
        if (ready > 0 && ready + shippedOrBeyond == total) return OrderStatus.READY_FOR_PICKUP;
        if (shippedOrBeyond == total) return OrderStatus.SHIPPED;
        if (shippedOrBeyond > 0) return OrderStatus.PARTIALLY_SHIPPED;
        return capturedCents > 0 ? OrderStatus.PROCESSING : OrderStatus.PLACED;
    }

    private long count(LineStatus s) {
        return lines.values().stream().filter(l -> l.status == s).count();
    }
}
