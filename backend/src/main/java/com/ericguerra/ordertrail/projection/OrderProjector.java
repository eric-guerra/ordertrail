package com.ericguerra.ordertrail.projection;

import com.ericguerra.ordertrail.events.OrderEvent;
import com.ericguerra.ordertrail.projection.OrderView.LineStatus;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure event → read-model logic. Lines can't move backwards (a late "shipped" event can't undo
 * "delivered"), and carrier scans update every line on that tracking number.
 */
public final class OrderProjector {
    private static final Set<LineStatus> TERMINAL = Set.of(LineStatus.DELIVERED, LineStatus.PICKED_UP, LineStatus.RETURNED, LineStatus.CANCELLED);

    private OrderProjector() {}

    public static void apply(OrderView v, OrderEvent e) {
        switch (e) {
            case OrderEvent.Placed p -> {
                v.orderNo = p.orderNo();
                v.channel = p.channel();
                v.customerId = p.customerId();
                v.email = p.email() == null ? null : p.email().trim().toLowerCase(Locale.ROOT);
                v.totalCents = p.totalCents();
                v.placedAt = p.occurredAt();
                for (OrderEvent.Line l : p.lines()) {
                    OrderView.Line line = new OrderView.Line();
                    line.lineId = l.lineId();
                    line.sku = l.sku();
                    line.name = l.name();
                    line.qty = l.qty();
                    line.unitPriceCents = l.unitPriceCents();
                    line.fulfillment = l.fulfillment();
                    line.storeId = l.storeId();
                    v.lines.put(l.lineId(), line);
                }
                timeline(v, e, "Order placed via " + channelLabel(p.channel()));
            }
            case OrderEvent.PaymentCaptured pc -> {
                v.capturedCents += pc.amountCents();
                timeline(v, e, "Payment confirmed");
            }
            case OrderEvent.LinesShipped s -> {
                for (OrderView.Line l : lines(v, s.lineIds())) {
                    if (advance(l, LineStatus.SHIPPED)) {
                        l.carrier = s.carrier();
                        l.trackingNumber = s.trackingNumber();
                        l.estimatedDelivery = s.estimatedDelivery();
                    }
                }
                timeline(v, e, s.lineIds().size() + " item(s) shipped with " + s.carrier() + " (" + s.trackingNumber() + ")");
            }
            case OrderEvent.CarrierScan scan -> {
                LineStatus target = switch (scan.status()) {
                    case "OUT_FOR_DELIVERY" -> LineStatus.OUT_FOR_DELIVERY;
                    case "DELIVERED" -> LineStatus.DELIVERED;
                    case "EXCEPTION" -> LineStatus.DELIVERY_EXCEPTION;
                    default -> LineStatus.SHIPPED;
                };
                v.lines.values().stream().filter(l -> scan.trackingNumber().equals(l.trackingNumber)).forEach(l -> {
                    // An exception can be cleared by a later successful scan.
                    if (l.status == LineStatus.DELIVERY_EXCEPTION && target != LineStatus.DELIVERY_EXCEPTION) l.status = LineStatus.SHIPPED;
                    advance(l, target);
                });
                timeline(v, e, carrierMessage(scan));
            }
            case OrderEvent.ReadyForPickup r -> {
                lines(v, r.lineIds()).forEach(l -> advance(l, LineStatus.READY_FOR_PICKUP));
                timeline(v, e, "Ready for pickup at store " + r.storeId());
            }
            case OrderEvent.PickedUp p -> {
                lines(v, p.lineIds()).forEach(l -> advance(l, LineStatus.PICKED_UP));
                timeline(v, e, "Picked up at store " + p.storeId());
            }
            case OrderEvent.LinesReturned r -> {
                lines(v, r.lineIds()).forEach(l -> l.status = LineStatus.RETURNED); // returns override delivery
                timeline(v, e, r.lineIds().size() + " item(s) returned via " + channelLabel(r.channel()));
            }
            case OrderEvent.Cancelled c -> {
                v.cancelled = true;
                // Only unfulfilled lines are cancelled; anything already shipped or picked up keeps its status.
                v.lines.values().forEach(l -> {
                    if (l.status == LineStatus.ORDERED) l.status = LineStatus.CANCELLED;
                });
                timeline(v, e, "Order cancelled" + (c.reason() == null ? "" : ": " + c.reason()));
            }
        }
        v.version = e.version();
        v.updatedAt = e.occurredAt();
    }

    /** Moves a line forward along its lifecycle; returns false if the event is stale for this line. */
    static boolean advance(OrderView.Line l, LineStatus target) {
        if (TERMINAL.contains(l.status) || l.status == target) return false;
        if (rank(target) < rank(l.status) && target != LineStatus.DELIVERY_EXCEPTION) return false;
        l.status = target;
        return true;
    }

    private static int rank(LineStatus s) {
        return switch (s) {
            case ORDERED -> 0;
            case READY_FOR_PICKUP, SHIPPED -> 1;
            case DELIVERY_EXCEPTION -> 1;
            case OUT_FOR_DELIVERY -> 2;
            case DELIVERED, PICKED_UP -> 3;
            case RETURNED, CANCELLED -> 4;
        };
    }

    private static List<OrderView.Line> lines(OrderView v, List<String> ids) {
        return ids.stream().map(v.lines::get).filter(java.util.Objects::nonNull).toList();
    }

    private static void timeline(OrderView v, OrderEvent e, String message) {
        v.timeline.add(new OrderView.TimelineEntry(e.occurredAt(), e.getClass().getSimpleName(), message));
    }

    private static String channelLabel(String channel) {
        return switch (channel == null ? "" : channel) {
            case "WEB" -> "website";
            case "APP" -> "mobile app";
            case "POS" -> "store";
            case "CALL_CENTER" -> "customer service";
            default -> channel == null ? "unknown channel" : channel.toLowerCase(Locale.ROOT);
        };
    }

    private static String carrierMessage(OrderEvent.CarrierScan s) {
        String where = s.location() == null ? "" : " — " + s.location();
        return switch (s.status()) {
            case "OUT_FOR_DELIVERY" -> "Out for delivery" + where;
            case "DELIVERED" -> "Delivered" + where;
            case "EXCEPTION" -> "Delivery problem reported by carrier" + where;
            default -> "In transit" + where;
        };
    }
}
